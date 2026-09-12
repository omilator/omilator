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

    data class CoreEntry(val name: String, val system: String, val urlName: String)

    val cores: List<CoreEntry> = listOf(
        CoreEntry("mgba", "GB / GBC / GBA", "mgba_libretro"),
        CoreEntry("mesen", "NES", "mesen_libretro"),
        CoreEntry("snes9x", "SNES", "snes9x_libretro"),
        CoreEntry("genesis_plus_gx", "Genesis / Mega Drive", "genesis_plus_gx_libretro"),
        CoreEntry("mednafen_psx_hw", "PS1 (accurate)", "mednafen_psx_hw_libretro"),
        CoreEntry("pcsx_rearmed", "PS1 (fast)", "pcsx_rearmed_libretro"),
        CoreEntry("melonds", "DS", "melonds_libretro"),
        CoreEntry("mednafen_saturn", "Saturn", "mednafen_saturn_libretro"),
        CoreEntry("nestopia", "NES (alt)", "nestopia_libretro"),
        CoreEntry("gambatte", "GB / GBC (alt)", "gambatte_libretro"),
        CoreEntry("sameboy", "GB / GBC (accurate)", "sameboy_libretro"),
        CoreEntry("fbneo", "Arcade", "fbneo_libretro"),
        CoreEntry("picodrive", "Genesis / 32X", "picodrive_libretro"),
        CoreEntry("mupen64plus_next", "N64 (software render)", "mupen64plus_next_libretro"),
        // PSP/GC/Wii/Dreamcast: available on buildbot but require Vulkan
        // (Android has native Vulkan — no MoltenVK needed). Untested.
        CoreEntry("ppsspp", "PSP (Vulkan)", "ppsspp_libretro"),
        CoreEntry("flycast", "Dreamcast (Vulkan)", "flycast_libretro"),
    )

    // Current buildbot layout is /android/latest/<abi>/, and artifacts carry
    // an _android infix; the ABI is chosen at runtime so emulators/devices on
    // x86_64 get working cores too.
    private val abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "x86_64"
    } ?: error("Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

    private val buildbotBase = "https://buildbot.libretro.com/nightly/android/latest/$abi"

    fun isInstalled(entry: CoreEntry): Boolean =
        File(coresDir, "${entry.name}_libretro.so").exists()

    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download one core. Synchronous; call from Dispatchers.IO.
     * Returns true on success. [onProgress] receives status text.
     */
    fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        coresDir.mkdirs()
        val soName = "${entry.name}_libretro.so"
        val existing = File(coresDir, soName)
        if (existing.exists()) {
            onProgress("$soName already installed")
            return true
        }

        val zipUrl = "$buildbotBase/${entry.urlName}_android.so.zip"
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
            ZipInputStream(conn.inputStream).use { zis ->
                var entry2 = zis.nextEntry
                while (entry2 != null) {
                    if (entry2.name.endsWith(".so")) {
                        val outFile = File(coresDir, File(entry2.name).name)
                        outFile.outputStream().use { output -> zis.copyTo(output) }
                        onProgress("Installed $soName (${outFile.length() / 1024}KB)")
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

    fun downloadAll(onProgress: (String) -> Unit = {}): Int {
        var installed = 0
        for (entry in cores) {
            if (download(entry, onProgress)) installed++
        }
        return installed
    }
}
