package com.omilator.app

import com.omilator.data.library.GameSystem
import java.io.File

/**
 * Pure routing decision for quick-play / library play, extracted so the
 * macOS standalone-vs-libretro policy is pinnable by tests.
 *
 * The standalone route is a macOS-only safety valve: libretro HW render
 * conflicts with GLFW on the Cocoa main thread (SIGBUS), so PSP /
 * GameCube / Wii content must go to an external emulator there. On other
 * desktops everything runs through libretro.
 *
 * System detection goes through the SAME shared table the scanner and core
 * resolver use: the local-file branch used to hard-code `.iso → PSP`,
 * contradicting `GameSystem`'s documented `.iso → PLAYSTATION` resolution,
 * so a local PS1 disc was routed to PPSSPP (which rejects it) or to a
 * console-only "blocked" print — while the beetle_psx core sat installed
 * and unused.
 */
sealed interface RomRoute {
    /** Play in-process through libretro. */
    data class Libretro(val romPath: String) : RomRoute

    /** Launch the installed standalone emulator for this system. */
    data class Standalone(val romPath: String, val systemId: String) : RomRoute

    /** HW-render-blocked system with no standalone installed: must be
     *  surfaced to the user (the launcher names the app to install). */
    data class Blocked(val systemId: String, val appName: String, val installUrl: String) : RomRoute
}

/** Systems libretro cannot render on macOS (GLFW main-thread conflict):
 *  their content must reach a standalone emulator instead. */
internal fun blockedStandaloneSystem(system: GameSystem): String? = when (system) {
    GameSystem.PSP -> "psp"
    GameSystem.GAMECUBE, GameSystem.WII -> "gamecube_wii"
    else -> null
}

/** User-facing name for a standalone system id. */
internal fun standaloneAppName(systemId: String): String = when (systemId) {
    "psp" -> "PPSSPP"
    "gamecube_wii" -> "Dolphin"
    "ps3" -> "RPCS3"
    "wii_u" -> "Cemu"
    "xbox" -> "xemu"
    else -> "the standalone emulator"
}

/** Where to get the standalone for a system id. */
internal fun standaloneInstallUrl(systemId: String): String = when (systemId) {
    "psp" -> "https://ppsspp.org/downloads"
    "gamecube_wii" -> "https://dolphin-emu.org/download/"
    "ps3" -> "https://rpcs3.net/download"
    "wii_u" -> "https://cemu.info/releases/"
    "xbox" -> "https://xemu.app/releases/"
    else -> ""
}

/**
 * Route a play request. [systemOverride] (server metadata) wins over the
 * filename; unknown extensions route to libretro, whose resolver shows the
 * no-mapping screen — routing policy must not duplicate that decision.
 * [hasStandalone] reports whether a standalone emulator is installed for a
 * system id, keeping this function pure.
 */
internal fun routeRom(
    romPath: String,
    systemOverride: GameSystem?,
    macOs: Boolean,
    hasStandalone: (String) -> Boolean = { false },
): RomRoute {
    if (!macOs) return RomRoute.Libretro(romPath)
    val system = systemOverride
        ?: GameSystem.detectByExtension(File(romPath).extension)
        ?: return RomRoute.Libretro(romPath)
    val blockedId = blockedStandaloneSystem(system) ?: return RomRoute.Libretro(romPath)
    return if (hasStandalone(blockedId)) {
        RomRoute.Standalone(romPath, blockedId)
    } else {
        RomRoute.Blocked(blockedId, standaloneAppName(blockedId), standaloneInstallUrl(blockedId))
    }
}

/**
 * Explicit "launch standalone" mapping (settings → open emulator settings,
 * launch-standalone action). Deliberately keeps the historic `.iso → PSP`
 * assumption: the user explicitly asked for a standalone emulator, and PS1
 * is not among the systems with one — smart-play's shared-table `.iso →
 * PS1` rule lives in [routeRom], not here.
 */
internal fun standaloneSystemIdForExtension(extension: String): String? =
    when (extension.lowercase()) {
        "iso", "cso", "prx" -> "psp"
        "wbfs", "gcz", "wad", "gcm" -> "gamecube_wii"
        "pkg", "rap" -> "ps3"
        "wud", "wux" -> "wii_u"
        "xiso" -> "xbox"
        else -> null
    }
