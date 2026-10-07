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
     * Install catalog, declared in common so the routing invariant — every
     * `<GameSystem.preferredCore>_libretro` the launcher can request is an
     * install stem here — is pinned by MobileCoreCatalogTest. Entry fields
     * follow the shared [MobileCoreEntry] docs.
     */
    val cores: List<MobileCoreEntry> = AndroidCoreCatalog.entries

    // Current buildbot layout is /android/latest/<abi>/, and artifacts carry
    // an _android infix; the ABI is chosen at runtime so emulators/devices on
    // x86_64 get working cores too.
    private val abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "x86_64"
    } ?: error("Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

    private val buildbotBase = "https://buildbot.libretro.com/nightly/android/latest/$abi"

    /** A core file that does not even carry the dynamic-library magic is
     *  debris (partial historical write, HTML error page, corruption) —
     *  treating it as installed would pin it forever. */
    private fun looksLikeElf(file: File): Boolean {
        val h = ByteArray(4)
        file.inputStream().use { input ->
            var off = 0
            while (off < h.size) {
                val n = input.read(h, off, h.size - off)
                if (n < 0) return false
                off += n
            }
        }
        return h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() &&
            h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
    }

    fun isInstalled(entry: MobileCoreEntry): Boolean =
        File(coresDir, "${entry.name}_libretro.so").let {
            it.exists() && it.length() > 0 && looksLikeElf(it)
        }

    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download one core. Synchronous; call from Dispatchers.IO.
     * Returns true on success. [onProgress] receives status text.
     */
    fun download(entry: MobileCoreEntry, onProgress: (String) -> Unit = {}): Boolean {
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
