package com.omilator.data.settings

import java.io.File

/**
 * One shared desktop path policy. Every OS-specific location decision lives
 * here — settings, player and app code used to each rebuild macOS's
 * ~/Library/Application Support path on every platform.
 *
 * - macOS: ~/Library/Application Support/Omilator
 * - Windows: %APPDATA%\Omilator
 * - Linux: ${XDG_CONFIG_HOME:-~/.config}/omilator and
 *          ${XDG_DATA_HOME:-~/.local/share}/omilator
 */
object DesktopPaths {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val home = System.getProperty("user.home")

    private fun ensure(dir: File): String {
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    val configDir: String by lazy {
        when {
            isMac -> ensure(File(home, "Library/Application Support/Omilator"))
            isWindows -> ensure(File(System.getenv("APPDATA") ?: home, "Omilator"))
            else -> ensure(File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "omilator"))
        }
    }

    val dataDir: String by lazy {
        when {
            isMac -> configDir
            isWindows -> configDir
            else -> ensure(File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "omilator"))
        }
    }

    /** Where the installer puts cores and the player looks for them. One
     *  property for both: installers used to derive it from configDir while
     *  the resolver searched dataDir — identical on macOS/Windows, but on
     *  Linux (XDG_CONFIG_HOME vs XDG_DATA_HOME) a fresh install wrote cores
     *  where the player never looked. */
    val coresDir: java.io.File by lazy {
        java.io.File(dataDir, "cores").apply {
            check(isDirectory || mkdirs()) { "Cannot create core directory: $absolutePath" }
        }
    }
}
