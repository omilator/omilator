package com.omilator.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared mobile battery-save identity (SramIdentity.kt): sanitized
 * basename + 16-hex-char identity hash, and backup names that never
 * overwrite an existing backup. The iOS store (IosSramStore) and
 * Android's MobileSramStore both derive their file names from these
 * helpers so the scheme cannot drift between the two platforms.
 */
class SramIdentityTest {

    @Test
    fun sanitizesHostileCharactersButKeepsDotsAndDashes() {
        // ':' ';' and the two ' ' are each replaced by '_' (4 total),
        // '.' and '-' survive.
        assertEquals("Pokemon____-_Emerald", sanitizedRomBaseName("/tmp/roms/Pokemon_:; - Emerald.gba"))
        assertEquals("mega_man", sanitizedRomBaseName("/tmp/roms/mega man.gba"))
        assertEquals("a.b-c", sanitizedRomBaseName("/tmp/roms/a.b-c.sfc"))
    }

    @Test
    fun handlesWindowsStylePathsAndHiddenFiles() {
        assertEquals("game", sanitizedRomBaseName("""C:\Roms\game.gba"""))
        assertEquals("", sanitizedRomBaseName("/tmp/.gba"))
    }

    @Test
    fun identityHashIsSixteenLowercaseHexCharsAndStable() {
        val h1 = sramIdentityHash("/stable/rom/identity")
        val h2 = sramIdentityHash("/stable/rom/identity")
        assertEquals(16, h1.length)
        assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' }, "hash must be lowercase hex: $h1")
        assertEquals(h1, h2, "hash must be deterministic")
        assertNotEquals(h1, sramIdentityHash("/other/identity"))
    }

    @Test
    fun identityHashMatchesTheDesktopEngineScheme() {
        // The desktop engine (PlayerEngine.romIdHash) uses the first 8
        // bytes of SHA-256 as %02x hex — the shared helper must agree so a
        // save written by one is addressable under the same scheme.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("/stable/rom/identity".encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, sramIdentityHash("/stable/rom/identity"))
    }

    @Test
    fun saveFileNameComposesBaseAndHash() {
        assertEquals(
            "Emerald-0123456789abcdef.srm",
            sramSaveFileName("/tmp/roms/Emerald.gba", "0123456789abcdef"),
        )
    }

    @Test
    fun backupNamesNumberInsteadOfOverwriting() {
        val existing = mutableSetOf("game-abc.srm.bak", "game-abc.srm.bak2")
        assertEquals(
            "game-abc.srm.bak3",
            nextSramBackupName("game-abc.srm") { existing.contains(it) },
        )
    }

    @Test
    fun firstBackupTakesThePlainBakSuffix() {
        assertEquals(
            "game-abc.srm.bak",
            nextSramBackupName("game-abc.srm") { false },
        )
    }
}
