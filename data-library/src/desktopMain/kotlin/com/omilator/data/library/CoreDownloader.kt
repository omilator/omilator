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

    /** Returns true if a core library already exists in targetDir. */
    fun isInstalled(entry: CoreEntry): Boolean =
        File(targetDir, "${entry.name}.${platform.coreExt}").exists()

    /** Count of installed cores. */
    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download a single core. Returns true on success.
     * Call from Dispatchers.IO. Calls [onProgress] with status text.
     */
    fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        val libName = "${entry.name}.${platform.coreExt}"
        val existingFile = File(targetDir, libName)
        if (existingFile.exists()) {
            onProgress("$libName already installed")
            return true
        }

        val zipUrl = "$buildbotBase/${entry.name}_${platform.coreExt}.zip"
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
            ZipInputStream(conn.inputStream).use { zis ->
                var entry2 = zis.nextEntry
                while (entry2 != null) {
                    if (entry2.name.endsWith(".${platform.coreExt}")) {
                        val outFile = File(targetDir, File(entry2.name).name)
                        outFile.outputStream().use { output -> zis.copyTo(output) }
                        onProgress("Installed $libName (${outFile.length() / 1024}KB)")
                        conn.disconnect()
                        return true
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
