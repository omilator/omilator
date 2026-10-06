package com.omilator.data.launcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and installs standalone emulator apps from GitHub releases.
 * Installs to ~/Applications/ (no sudo required).
 *
 * macOS-only by construction: every spec selects a macOS asset, extraction
 * shells out to unzip/7z, and the installed artifact is a .app bundle.
 * Running this on Windows/Linux (as first-run setup used to) could never
 * complete — the PPSSPP *mac* zip failed to extract (no unzip.exe), or a
 * mac bundle landed in ~/Applications and never launched. On non-macOS
 * [emulators] is empty and setup treats "0 applicable" as complete.
 */
class EmulatorInstaller(
    /** Injectable so tests can pin the platform filtering. */
    private val osName: String = System.getProperty("os.name"),
) {

    data class EmulatorSpec(
        val systemId: String,
        val displayName: String,
        val githubRepo: String,
        /** Returns true if [assetName] is the macOS download for this emulator. */
        val assetFilter: (String) -> Boolean,
        /** How to extract: "unzip" or "7z" */
        val extractCommand: String,
    )

    val emulators: List<EmulatorSpec> =
        if (osName.contains("Mac", ignoreCase = true)) {
            listOf(
                EmulatorSpec("psp", "PPSSPP", "hrydgard/ppsspp",
                    assetFilter = { it.contains("mac", true) && it.endsWith(".zip") },
                    extractCommand = "unzip",
                ),
                EmulatorSpec("xbox", "xemu", "mborgerson/xemu",
                    assetFilter = { it.contains("mac", true) && it.endsWith(".zip") && !it.contains("dbg") },
                    extractCommand = "unzip",
                ),
                EmulatorSpec("ps3", "RPCS3", "RPCS3/rpcs3-binaries-mac",
                    assetFilter = { it.endsWith(".7z") },
                    extractCommand = "7z",
                ),
            )
        } else {
            emptyList()
        }

    private val targetDir = File(System.getProperty("user.home"), "Applications")

    /** Returns true if any version of [spec]'s app is installed. */
    fun isInstalled(spec: EmulatorSpec): Boolean {
        return findAppBundle(spec) != null
    }

    private fun findAppBundle(spec: EmulatorSpec): File? {
        val names = when (spec.systemId) {
            "psp" -> listOf("PPSSPPSDL.app", "PPSSPP.app")
            "xbox" -> listOf("Xemu.app", "xemu.app")
            "ps3" -> listOf("RPCS3.app")
            "gamecube_wii" -> listOf("Dolphin.app")
            "wii_u" -> listOf("CEmu.app", "Cemu.app")
            else -> emptyList()
        }
        return listOf(targetDir, File("/Applications")).flatMap { dir ->
            names.map { File(dir, it) }
        }.firstOrNull { it.exists() }
    }

    data class DownloadResult(val success: Boolean, val message: String)

    /**
     * Downloads + installs an emulator. Call from Dispatchers.IO.
     * Calls [onProgress] with status updates.
     */
    fun install(spec: EmulatorSpec, onProgress: (String) -> Unit = {}): DownloadResult {
        targetDir.mkdirs()

        if (isInstalled(spec)) {
            return DownloadResult(true, "${spec.displayName} already installed")
        }

        // 1. Query GitHub API for latest release
        onProgress("Finding latest ${spec.displayName} release...")
        val apiUrl = "https://api.github.com/repos/${spec.githubRepo}/releases/latest"
        val assetUrl = findAssetUrl(apiUrl, spec)
            ?: return DownloadResult(false, "No macOS download found for ${spec.displayName}")

        // 2. Download
        val ext = if (spec.extractCommand == "7z") ".7z" else ".zip"
        val archiveFile = File(targetDir, "${spec.displayName}_download$ext")
        onProgress("Downloading ${spec.displayName}...")
        try {
            downloadFile(assetUrl, archiveFile)
            onProgress("Downloaded ${(archiveFile.length() / 1024 / 1024)}MB")
        } catch (e: Exception) {
            return DownloadResult(false, "Download failed: ${e.message}")
        }

        // 3. Extract
        onProgress("Extracting...")
        val extractDir = File(targetDir, "${spec.displayName}_temp")
        extractDir.mkdirs()
        val extractResult = when (spec.extractCommand) {
            "unzip" -> runCommand("unzip", "-o", archiveFile.absolutePath, "-d", extractDir.absolutePath)
            "7z" -> runCommand("7z", "x", archiveFile.absolutePath, "-o${extractDir.absolutePath}", "-y")
            else -> false
        }
        archiveFile.delete()

        if (!extractResult) {
            return DownloadResult(false, "Extraction failed (need '${spec.extractCommand}' installed?)")
        }

        // 4. Find and move the .app
        val appBundle = findAppBundleInDir(extractDir, spec)
            ?: return DownloadResult(false, "No .app found in archive")

        val destApp = File(targetDir, appBundle.name)
        if (destApp.exists()) destApp.deleteRecursively()
        appBundle.renameTo(destApp)
        extractDir.deleteRecursively()

        onProgress("${spec.displayName} installed to ${destApp.absolutePath}")
        return DownloadResult(true, "${spec.displayName} installed successfully")
    }

    private fun findAssetUrl(apiUrl: String, spec: EmulatorSpec): String? {
        return try {
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                // GitHub's API requires a User-Agent; anonymous Java clients get 403.
                conn.setRequestProperty("User-Agent", "omilator-installer")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode != 200) {
                    println("[Omilator] GitHub API HTTP ${conn.responseCode} for $apiUrl")
                    return null
                }
                val body = conn.inputStream.bufferedReader().readText()
                // Parse the release as JSON. Two independent regex scans (all
                // "name" fields vs all "browser_download_url" fields) paired
                // by index — "name" occurs outside asset objects too, so the
                // pairing picked the wrong asset.
                val assets = kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .jsonObject["assets"]?.jsonArray ?: return null
                for (asset in assets) {
                    val obj = asset.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (spec.assetFilter(name)) return url
                }
                null
            } finally {
                // Early returns above used to leak the connection into the
                // JVM keep-alive pool; the setup retry loop multiplies them.
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun runCommand(vararg cmd: String): Boolean {
        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun findAppBundleInDir(dir: File, spec: EmulatorSpec): File? {
        // Search recursively for .app bundles
        return dir.walkTopDown()
            .filter { it.isDirectory && it.name.endsWith(".app") }
            .firstOrNull()
    }

    /** Count of installed emulators out of total. */
    fun installedCount(): Int = emulators.count { isInstalled(it) }
}
