package com.omilator.data.library

import kotlinx.serialization.Serializable

@Serializable
enum class GameSystem(
    val displayName: String,
    val manufacturer: String,
    val releaseYear: Int,
    val extensions: List<String>,
    val preferredCore: String,
) {
    NES("Nintendo Entertainment System", "Nintendo", 1985, listOf("nes", "nez", "unf", "unif"), "mesen"),
    SNES("Super Nintendo Entertainment System", "Nintendo", 1990, listOf("sfc", "smc", "fig", "swc"), "snes9x"),
    GAME_BOY("Game Boy", "Nintendo", 1989, listOf("gb"), "sameboy"),
    GAME_BOY_COLOR("Game Boy Color", "Nintendo", 1998, listOf("gbc"), "sameboy"),
    GAME_BOY_ADVANCE("Game Boy Advance", "Nintendo", 2001, listOf("gba"), "mgba"),
    GENESIS("Sega Genesis / Mega Drive", "Sega", 1988, listOf("md", "bin", "smd", "gen"), "genesis_plus_gx"),
    NINTENDO_64("Nintendo 64", "Nintendo", 1996, listOf("n64", "z64", "v64"), "mupen64plus_next"),
    PLAYSTATION("PlayStation", "Sony", 1994, listOf("cue", "chd", "m3u", "pbp", "img"), "beetle_psx_hw"),
    NINTENDO_DS("Nintendo DS", "Nintendo", 2004, listOf("nds", "ids", "app"), "melonds"),
    PSP("PlayStation Portable", "Sony", 2004, listOf("iso", "cso", "prx", "elf"), "ppsspp"),
    GAMECUBE("Nintendo GameCube", "Nintendo", 2001, listOf("gcm", "gci", "ciso"), "dolphin"),
    WII("Nintendo Wii", "Nintendo", 2006, listOf("wbfs", "wad", "gcz", "ciso"), "dolphin"),
    NINTENDO_3DS("Nintendo 3DS", "Nintendo", 2011, listOf("3ds", "3dsx", "cci", "cxI", "cxi", "elf", "app"), "azahar"),
    PLAYSTATION_2("PlayStation 2", "Sony", 2000, listOf("bin", "elf", "nrg", "mdf", "gz"), "play"),
    DREAMCAST("Sega Dreamcast", "Sega", 1998, listOf("cdi", "gdi", "chd", "m3u", "gdl"), "flycast"),
    SATURN("Sega Saturn", "Sega", 1994, listOf("cue", "iso", "bin", "chd", "m3u"), "mednafen_saturn");

    companion object {
        /** All systems that claim an extension (bin/iso/cue/chd/elf and
         *  friends are claimed by several). */
        fun candidatesByExtension(extension: String): List<GameSystem> {
            val ext = extension.lowercase()
            return entries.filter { sys -> sys.extensions.any { it.equals(ext, ignoreCase = true) } }
        }

        /** Documented resolutions for extensions several systems share.
         *  Without this, entry order picked the winner silently — the comment
         *  said PS1 for .iso while the enum order answered PSP. */
        private val sharedExtensionPreference = mapOf(
            "iso" to PLAYSTATION,
            "bin" to PLAYSTATION,
            "chd" to PLAYSTATION,
            "m3u" to PLAYSTATION,
            "cue" to PLAYSTATION,
            "elf" to PSP,
            "app" to NINTENDO_3DS,
            "ciso" to GAMECUBE,
        )

        fun detectByExtension(extension: String): GameSystem? {
            val ext = extension.lowercase()
            sharedExtensionPreference[ext]?.let { return it }
            return entries.firstOrNull { sys -> sys.extensions.any { it.equals(ext, ignoreCase = true) } }
        }
    }
}

@Serializable
data class Game(
    val id: String,
    val title: String,
    val system: GameSystem,
    val filePath: String,
    val fileSizeBytes: Long,
    val coverArtUrl: String? = null,
    val lastPlayedEpochMillis: Long? = null,
)
