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

    /**
     * @param name canonical local core name — installs as `<name>.<ext>`,
     *   which is what the launcher's core resolution looks for.
     * @param artifact the buildbot's artifact stem when it deviates from
     *   `name`: the osx buildbot publishes beetle_psx_hw as
     *   `mednafen_psx_hw` (same mapping the Android downloader already
     *   carries); without it the macOS entry 404s and first-run setup can
     *   never complete, which also blocked .iso quick-play.
     */
    data class CoreEntry(
        val name: String,
        val system: String,
        val artifact: String? = null,
    )

    val cores: List<CoreEntry> = listOf(
        CoreEntry("mgba_libretro", "GB / GBC / GBA"),
        CoreEntry("mesen_libretro", "NES"),
        CoreEntry("snes9x_libretro", "SNES"),
        CoreEntry("genesis_plus_gx_libretro", "Genesis / Mega Drive"),
        CoreEntry("mupen64plus_next_libretro", "N64"),
        CoreEntry("beetle_psx_hw_libretro", "PS1 (accurate)", artifact = "mednafen_psx_hw_libretro"),
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

    /** Buildbot zip URL for an entry: normally `<name>.<ext>.zip`, but some
     *  platforms publish a core under a different artifact stem (see
     *  [CoreEntry.artifact]). Pure so tests can pin the mapping. */
    internal fun zipUrlFor(entry: CoreEntry): String =
        "$buildbotBase/${entry.artifact ?: entry.name}.${platform.coreExt}.zip"

    /** Canonical on-disk library name — what isInstalled() and the
     *  launcher's core resolution look for, regardless of what the
     *  buildbot calls the artifact. */
    internal fun installNameFor(entry: CoreEntry): String =
        "${entry.name}.${platform.coreExt}"

    /** The zip member that carries the core: archives name the member after
     *  the zip's own stem, which deviates from the canonical name exactly
     *  when [CoreEntry.artifact] does. */
    internal fun archiveMemberFor(entry: CoreEntry): String =
        "${entry.artifact ?: entry.name}.${platform.coreExt}"

    /** A core file that does not even carry the platform's dynamic-library
     *  magic is debris (partial historical write, HTML error page,
     *  corruption) — treating it as installed would pin it forever. */
    private fun looksLikeNativeLibrary(file: File): Boolean {
        val h = ByteArray(4)
        file.inputStream().use { input ->
            var off = 0
            while (off < h.size) {
                val n = input.read(h, off, h.size - off)
                if (n < 0) return false
                off += n
            }
        }
        val magic = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return when (platform.coreExt) {
            "dll" -> h[0] == 'M'.code.toByte() && h[1] == 'Z'.code.toByte()
            "so" -> magic == 0x7f454c46L
            // Mach-O thin magics in either byte order, plus FAT wrappers.
            else -> magic in longArrayOf(
                0xFEEDFACEL, 0xFEEDFACFL, 0xCEFAEDFEL, 0xCFFAEDFEL,
                0xCAFEBABEL, 0xCAFEBABFL, 0xBEBAFECAL, 0xBFBAFECAL,
            )
        }
    }

    /** Returns true if a core library already exists in targetDir. A
     *  zero-length file is a truncated install, not an installed core. */
    fun isInstalled(entry: CoreEntry): Boolean =
        File(targetDir, installNameFor(entry)).let {
            it.exists() && it.length() > 0 && looksLikeNativeLibrary(it)
        }

    /** Count of installed cores. */
    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download a single core. Returns true on success.
     * Call from Dispatchers.IO. Calls [onProgress] with status text.
     */
    fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        // Canonical install name — what isInstalled() and the launcher's
        // core resolution look for.
        val libName = installNameFor(entry)
        val finalFile = File(targetDir, libName)
        if (isInstalled(entry)) {
            onProgress("$libName already installed")
            return true
        }

        val zipUrl = zipUrlFor(entry)
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
            // Only the member carrying the archive's own stem counts (some
            // buildbots publish a core under a different name than its
            // libretro id — see CoreEntry.artifact), and a helper library
            // bundled in the archive must not be installed (and reported)
            // as the requested core.
            val expectedMember = archiveMemberFor(entry)
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
