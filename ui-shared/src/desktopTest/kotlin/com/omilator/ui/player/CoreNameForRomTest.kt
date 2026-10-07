package com.omilator.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The single mobile core-resolution seam: the shared GameSystem table,
 * not hand-rolled extension tables. Pins the exact divergences the three
 * private iOS tables had — a library `.iso` used to launch mGBA, the
 * deep-link poller sent it to PPSSPP, and unknown extensions silently
 * launched mGBA.
 */
class CoreNameForRomTest {

    @Test
    fun gbaRoutesToMgba() {
        assertEquals("mgba_libretro", coreNameForRom("/tmp/roms/Pokemon - Emerald.gba"))
    }

    @Test
    fun isoFollowsTheSharedPlaystationPreference() {
        // GameSystem documents `.iso → PLAYSTATION` (sharedExtensionPreference);
        // the iOS deep-link table sent it to PPSSPP and the library/quick-play
        // tables sent it to mGBA.
        assertEquals("beetle_psx_hw_libretro", coreNameForRom("/tmp/roms/Crash Bandicoot.iso"))
    }

    @Test
    fun binRoutesToPlaystationNotMgba() {
        assertEquals("beetle_psx_hw_libretro", coreNameForRom("/tmp/roms/game.bin"))
    }

    @Test
    fun snesAndNesKeepTheirCores() {
        assertEquals("snes9x_libretro", coreNameForRom("/tmp/roms/Chrono Trigger.sfc"))
        assertEquals("mesen_libretro", coreNameForRom("/tmp/roms/Mario.nes"))
    }

    @Test
    fun csoPrxFollowPsp() {
        assertEquals("ppsspp_libretro", coreNameForRom("/tmp/roms/MotorStorm.cso"))
        assertEquals("ppsspp_libretro", coreNameForRom("/tmp/roms/game.prx"))
    }

    @Test
    fun uppercaseExtensionsResolve() {
        assertEquals("mgba_libretro", coreNameForRom("/tmp/roms/GAME.GBA"))
    }

    @Test
    fun unknownExtensionHasNoMapping() {
        // No silent mGBA fallback: the caller must surface this.
        assertNull(coreNameForRom("/tmp/roms/movie.xyz"))
        assertNull(coreNameForRom("/tmp/roms/extensionless"))
        assertNull(coreNameForRom("/tmp/roms/.hidden"))
    }
}
