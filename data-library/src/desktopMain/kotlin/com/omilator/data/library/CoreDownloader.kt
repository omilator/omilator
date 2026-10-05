package com.omilator.data.library

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads libretro cores from the official buildbot to a target directory.
 * Used by the "Download cores" button in settings for first-run setup.
 */
class CoreDownloader(private val targetDir: File) {

    data class CoreEntry(val name: String, val system: String)

    val cores: List<CoreEntry> = listOf(
        CoreEntry("mgba_libretro", "GB / GBC / GBA"),
        CoreEntry("mesen_libretro", "NES"),
        CoreEntry("snes9x_libretro", "SNES"),
        CoreEntry("genesis_plus_gx_libretro", "Genesis / Mega Drive"),
        CoreEntry("mupen64plus_next_libretro", "N64"),
        CoreEntry("beetle_psx_hw_libretro", "PS1 (accurate)"),
        CoreEntry("pcsx_rearmed_libretro", "PS1 (fast)"),
        CoreEntry("melonds_libretro", "DS"),
        CoreEntry("azahar_libretro", "3DS"),
        CoreEntry("play_libretro", "PS2"),
        CoreEntry("flycast_libretro", "Dreamcast"),
        CoreEntry("mednafen_saturn_libretro", "Saturn"),
        CoreEntry("dolphin_libretro", "GameCube / Wii"),
        CoreEntry("ppsspp_libretro", "PSP"),
    )

    /** Platform-specific buildbot coordinates for the current desktop OS. */
    private data class Platform(val buildbotPath: String, val coreExt: String)

    private val platform: Platform = run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")
        when {
            os.contains("win") -> Platform("windows/x86_64", "dll")
            os.contains("linux") -> Platform("linux/x86_64", "so")
            arch == "aarch64" || arch == "arm64" -> Platform("apple/osx/arm64", "dylib")
            else -> Platform("apple/osx/x86_64", "dylib")
        }
    }

    private val buildbotBase = "https://buildbot.libretro.com/nightly/${platform.buildbotPath}/latest"

    /** Returns true if a core library already exists in targetDir. A
     *  zero-length file is a truncated install, not an installed core. */
    fun isInstalled(entry: CoreEntry): Boolean =
        File(targetDir, "${entry.name}.${platform.coreExt}").let { it.exists() && it.length() > 0 }

    /** Count of installed cores. */
    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download a single core. Returns true on success.
     * Call from Dispatchers.IO. Calls [onProgress] with status text.
     */
    fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        val libName = "${entry.name}.${platform.coreExt}"
        val finalFile = File(targetDir, libName)
        if (isInstalled(entry)) {
            onProgress("$libName already installed")
            return true
        }

        val zipUrl = "$buildbotBase/${entry.name}.${platform.coreExt}.zip"
        onProgress("Downloading $libName...")
        return try {
            targetDir.mkdirs()
            val conn = URL(zipUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) {
                onProgress("Failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return false
            }
            // Only the member carrying the exact expected library name
            // counts — a helper library bundled in the archive must not be
            // installed (and reported) as the requested core.
            val expectedMember = libName
            ZipInputStream(conn.inputStream).use { zis ->
                var entry2 = zis.nextEntry
                while (entry2 != null) {
                    if (File(entry2.name).name == expectedMember) {
                        // Extract to a temp sibling and swap in atomically: a
                        // truncated core left at the final name would be
                        // treated as installed forever.
                        val tmp = File(targetDir, ".$libName.part")
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
                            onProgress("Installed $libName (${finalFile.length() / 1024}KB)")
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

    /** Download all cores that aren't already installed. */
    fun downloadAll(onProgress: (String) -> Unit = {}): Int {
        var installed = 0
        for (entry in cores) {
            if (download(entry, onProgress)) installed++
        }
        return installed
    }
}
