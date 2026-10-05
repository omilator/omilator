package com.omilator.data.library

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads libretro cores for Android. Mirrors the desktop CoreDownloader
 * but targets android/arm64-v8a buildbot. No platform conversion needed —
 * Android loads .so directly without code-signing (unlike iOS).
 *
 * @param coresDir absolute path to the app's cores directory (typically
 *   `<filesDir>/cores`). Caller is responsible for ensuring it exists.
 */
class AndroidCoreDownloader(private val coresDir: File) {

    /**
     * @param name canonical local core name — installs as `<name>_libretro.so`,
     *   which is what the launcher's core resolution looks for.
     * @param urlName the buildbot's artifact stem; usually `<name>_libretro`
     *   but a couple of cores (beetle_psx_hw) are uploaded under a different
     *   name than their libretro core id.
     * @param artifact full zip filename on the buildbot when it deviates from
     *   `<urlName>_android.so.zip` (azahar drops the `_android` infix).
     */
    data class CoreEntry(
        val name: String,
        val system: String,
        val urlName: String,
        val artifact: String? = null,
    )

    val cores: List<CoreEntry> = listOf(
        CoreEntry("mgba", "GB / GBC / GBA", "mgba_libretro"),
        CoreEntry("mesen", "NES", "mesen_libretro"),
        CoreEntry("snes9x", "SNES", "snes9x_libretro"),
        CoreEntry("genesis_plus_gx", "Genesis / Mega Drive", "genesis_plus_gx_libretro"),
        CoreEntry("beetle_psx_hw", "PS1 (accurate)", "mednafen_psx_hw_libretro"),
        CoreEntry("pcsx_rearmed", "PS1 (fast)", "pcsx_rearmed_libretro"),
        CoreEntry("melonds", "DS", "melonds_libretro"),
        CoreEntry("mednafen_saturn", "Saturn", "mednafen_saturn_libretro"),
        CoreEntry("nestopia", "NES (alt)", "nestopia_libretro"),
        CoreEntry("gambatte", "GB / GBC (alt)", "gambatte_libretro"),
        CoreEntry("sameboy", "GB / GBC (accurate)", "sameboy_libretro"),
        CoreEntry("fbneo", "Arcade", "fbneo_libretro"),
        CoreEntry("picodrive", "Genesis / 32X", "picodrive_libretro"),
        CoreEntry("mupen64plus_next", "N64 (software render)", "mupen64plus_next_libretro"),
        CoreEntry("azahar", "3DS", "azahar_libretro", artifact = "azahar_libretro.so.zip"),
        CoreEntry("play", "PS2", "play_libretro"),
        // PSP/GC/Wii/Dreamcast: available on buildbot but require Vulkan
        // (Android has native Vulkan — no MoltenVK needed). Untested.
        CoreEntry("ppsspp", "PSP (Vulkan)", "ppsspp_libretro"),
        CoreEntry("flycast", "Dreamcast (Vulkan)", "flycast_libretro"),
        CoreEntry("dolphin", "GameCube / Wii (Vulkan)", "dolphin_libretro"),
    )

    // Current buildbot layout is /android/latest/<abi>/, and artifacts carry
    // an _android infix; the ABI is chosen at runtime so emulators/devices on
    // x86_64 get working cores too.
    private val abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "x86_64"
    } ?: error("Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

    private val buildbotBase = "https://buildbot.libretro.com/nightly/android/latest/$abi"

    fun isInstalled(entry: CoreEntry): Boolean =
        File(coresDir, "${entry.name}_libretro.so").let { it.exists() && it.length() > 0 }

    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download one core. Synchronous; call from Dispatchers.IO.
     * Returns true on success. [onProgress] receives status text.
     */
    fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        coresDir.mkdirs()
        val soName = "${entry.name}_libretro.so"
        val finalFile = File(coresDir, soName)
        if (isInstalled(entry)) {
            onProgress("$soName already installed")
            return true
        }

        val zipName = entry.artifact ?: "${entry.urlName}_android.so.zip"
        val zipUrl = "$buildbotBase/$zipName"
        onProgress("Downloading $soName...")
        return try {
            val conn = URL(zipUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) {
                onProgress("Failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return false
            }
            // Only the exact expected archive member counts — the artifacts
            // carry the zip's own stem as the .so name (azahar drops the
            // `_android` infix), and a stray helper .so must not be installed
            // as the requested core.
            val expectedMember = zipName.removeSuffix(".zip")
            ZipInputStream(conn.inputStream).use { zis ->
                var entry2 = zis.nextEntry
                while (entry2 != null) {
                    if (File(entry2.name).name == expectedMember) {
                        // Every consumer (isInstalled, the launcher's core
                        // resolution) looks for the canonical <core>_libretro.so,
                        // so the extract lands under the canonical name — via
                        // a temp sibling so an interrupted download cannot
                        // leave a truncated core at the final name.
                        val tmp = File(coresDir, ".$soName.part")
                        try {
                            tmp.outputStream().use { output -> zis.copyTo(output) }
                            if (tmp.length() == 0L) {
                                onProgress("Failed: empty core archive member")
                                return false
                            }
                            try {
                                java.nio.file.Files.move(
                                    tmp.toPath(), finalFile.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                )
                            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                java.nio.file.Files.move(
                                    tmp.toPath(), finalFile.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                )
                            }
                            onProgress("Installed $soName (${finalFile.length() / 1024}KB)")
                            conn.disconnect()
                            return true
                        } finally {
                            tmp.delete()
                        }
                    }
                    entry2 = zis.nextEntry
                }
            }
            conn.disconnect()
            false
        } catch (e: Exception) {
            onProgress("Error: ${e.message}")
            false
        }
    }

    fun downloadAll(onProgress: (String) -> Unit = {}): Int {
        var installed = 0
        for (entry in cores) {
            if (download(entry, onProgress)) installed++
        }
        return installed
    }
}
