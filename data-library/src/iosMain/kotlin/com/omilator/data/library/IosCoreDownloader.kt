@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.data.library

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.pclose
import platform.posix.popen
import platform.posix.fread

/**
 * SIMULATOR-ONLY core downloader for iOS.
 *
 * Downloads ios-arm64 cores from the libretro buildbot, then converts them
 * to iossim platform via vtool and re-signs with codesign. The iOS app
 * process runs on the macOS host during simulation, so it can shell out to
 * /usr/bin/curl, /usr/bin/unzip, /usr/bin/vtool, /usr/bin/codesign.
 *
 * On a real iPhone this class is a no-op — there is no curl/vtool/codesign,
 * and App Store rules forbid forking subprocesses. For device builds, cores
 * must be pre-converted and bundled (see Phase 8: static core bundling).
 *
 * @param coresDir absolute path to the Documents/cores directory.
 */
class IosCoreDownloader(private val coresDir: String) {

    /**
     * Install catalog, declared in common so the routing invariant — every
     * `<GameSystem.preferredCore>_libretro` the launcher can request is an
     * install stem here — is pinned by MobileCoreCatalogTest. Entry fields:
     * `name` installs as `<name>_libretro.dylib` (the name
     * coreNameForRom/corePathFor resolve); `urlName` is the buildbot's full
     * artifact stem — mostly `<core>_libretro_ios`, with a few cores
     * dropping the `_ios` and beetle_psx_hw published under its legacy
     * mednafen_psx_hw name (same mapping Android carries).
     */
    val cores: List<MobileCoreEntry> = IosCoreCatalog.entries

    private val buildbotBase = "https://buildbot.libretro.com/nightly/apple/ios-arm64/latest"

    fun isInstalled(entry: MobileCoreEntry): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(finalPath(entry))
    }

    fun installedCount(): Int = cores.count { isInstalled(it) }

    /**
     * Download + convert + sign one core. Synchronous; call from
     * Dispatchers.IO or a coroutine on Dispatchers.Default. Returns true
     * on success. onProgress receives human-readable status updates.
     */
    fun download(entry: MobileCoreEntry, onProgress: (String) -> Unit = {}): Boolean {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(
            coresDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        if (isInstalled(entry)) {
            onProgress("${entry.name} already installed")
            return true
        }

        // Sandbox-safe tmp dir inside Documents/cores/.tmp-<name>
        val tmpDir = "$coresDir/.tmp-${entry.name}"
        cleanup(tmpDir)
        fm.createDirectoryAtPath(tmpDir, true, null, null)

        val zipUrl = "$buildbotBase/${entry.urlName}.dylib.zip"
        val zipPath = "$tmpDir/core.zip"
        val convertedPath = "$tmpDir/core_sim.dylib"

        try {
            onProgress("Downloading ${entry.name}...")
            val dl = runShell(
                "curl -sL --fail --max-time 60 -o '${shellEscape(zipPath)}' '${shellEscape(zipUrl)}'"
            )
            if (dl != 0 || !fm.fileExistsAtPath(zipPath)) {
                onProgress("curl failed (exit=$dl)")
                return false
            }

            onProgress("Extracting...")
            if (runShell("cd '${shellEscape(tmpDir)}' && unzip -o core.zip") != 0) {
                onProgress("unzip failed")
                return false
            }

            // Some buildbot zips use different dylib filenames than the URL
            // stem. Find any .dylib (not the .zip we just wrote).
            val dylibName = (fm.contentsOfDirectoryAtPath(tmpDir, null) as? List<String>)
                ?.firstOrNull { it.endsWith(".dylib") && !it.endsWith(".zip") }
            if (dylibName == null || !fm.fileExistsAtPath("$tmpDir/$dylibName")) {
                onProgress("no dylib in archive")
                return false
            }
            val dylibPath = "$tmpDir/$dylibName"

            onProgress("Converting platform to iossim...")
            val vtoolCmd = "vtool -set-build-version iossim 14.0 14.0 -replace " +
                "-output '${shellEscape(convertedPath)}' '${shellEscape(dylibPath)}'"
            if (runShell(vtoolCmd) != 0) {
                onProgress("vtool failed")
                return false
            }

            onProgress("Signing...")
            if (runShell("codesign -s - --force '${shellEscape(convertedPath)}'") != 0) {
                onProgress("codesign failed")
                return false
            }

            if (!fm.moveItemAtPath(convertedPath, finalPath(entry), null)) {
                onProgress("move to final location failed")
                return false
            }

            onProgress("Installed ${entry.name}")
            return true
        } finally {
            cleanup(tmpDir)
        }
    }

    /** Download every core that isn't already installed. Returns installed count. */
    fun downloadAll(onProgress: (String) -> Unit = {}): Int {
        var installed = 0
        for (entry in cores) {
            if (download(entry, onProgress)) installed++
        }
        return installed
    }

    private fun finalPath(entry: MobileCoreEntry): String = "$coresDir/${entry.name}_libretro.dylib"

    private fun cleanup(path: String) {
        // rm -rf is fine here — paths come from NSSearchPathForDirectoriesInDomains
        // (sandbox), and entry.name is a hardcoded constant.
        runShell("rm -rf '${shellEscape(path)}'")
    }

    /**
     * Run a shell command via popen. Returns the exit code from pclose().
     * The output pipe is drained so the command can't block on a full buffer.
     */
    private fun runShell(cmd: String): Int {
        val fp = popen(cmd, "r") ?: return -1
        try {
            // Drain. We discard output — callers see status via onProgress.
            val buf = ByteArray(4096)
            buf.usePinned { pinned ->
                val bufPtr = pinned.addressOf(0)
                while (true) {
                    val n = fread(bufPtr, 1u, buf.size.toULong(), fp)
                    if (n == 0UL) break
                }
            }
        } finally {
            return pclose(fp).toInt()
        }
    }

    /**
     * Single-quote escape for paths passed into shell commands. The app
     * container path can contain spaces on some simulator builds.
     */
    private fun shellEscape(s: String): String = s.replace("'", "'\"'\"'")
}
