package com.omilator.app

import com.omilator.data.library.GameSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the smart-play routing policy. The regression this guards: the
 * local-file branch hard-coded `.iso → PSP`, contradicting the shared
 * GameSystem table's documented `.iso → PLAYSTATION` resolution — a local
 * PS1 disc went to PPSSPP (which rejects it) or to a console-only
 * "blocked" print the user never saw, while beetle_psx sat installed.
 */
class RomRoutingTest {

    @Test
    fun localIsoRoutesToLibretroPlaystation() {
        val route = routeRom("/roms/Final Fantasy VII.iso", null, macOs = true)
        assertEquals(RomRoute.Libretro("/roms/Final Fantasy VII.iso"), route)
    }

    @Test
    fun pspDiscImagesRouteToStandalone() {
        val route = routeRom("/roms/game.cso", null, macOs = true, hasStandalone = { it == "psp" })
        assertEquals(RomRoute.Standalone("/roms/game.cso", "psp"), route)
    }

    @Test
    fun wiiDiscImagesRouteToGamecubeWiiStandalone() {
        val route = routeRom("/roms/game.wbfs", null, macOs = true, hasStandalone = { _ -> true })
        assertEquals(RomRoute.Standalone("/roms/game.wbfs", "gamecube_wii"), route)
    }

    @Test
    fun serverPspMetadataRoutesToStandalone() {
        val route = routeRom("/cache/77-1024.rom", GameSystem.PSP, macOs = true, hasStandalone = { _ -> true })
        assertEquals(RomRoute.Standalone("/cache/77-1024.rom", "psp"), route)
    }

    @Test
    fun serverSnesMetadataRoutesToLibretro() {
        val route = routeRom("/cache/77-1024.rom", GameSystem.SNES, macOs = true)
        assertEquals(RomRoute.Libretro("/cache/77-1024.rom"), route)
    }

    @Test
    fun smcRoutesToLibretro() {
        assertEquals(RomRoute.Libretro("/roms/game.smc"), routeRom("/roms/game.smc", null, macOs = true))
    }

    @Test
    fun blockedSystemWithoutStandaloneSurfacesADialogRoute() {
        val route = routeRom("/roms/game.prx", null, macOs = true)
        assertTrue(route is RomRoute.Blocked, "expected Blocked, got $route")
        route as RomRoute.Blocked
        assertEquals("psp", route.systemId)
        assertEquals("PPSSPP", route.appName)
        assertTrue(route.installUrl.isNotBlank(), "blocked dialog must tell the user where to install from")
    }

    @Test
    fun unknownExtensionRoutesToLibretroForTheResolverToReject() {
        // Routing must not duplicate the no-mapping decision.
        assertEquals(
            RomRoute.Libretro("/roms/game.xyz"),
            routeRom("/roms/game.xyz", null, macOs = true),
        )
    }

    @Test
    fun nonMacOsAlwaysRoutesToLibretro() {
        // Even HW-render-blocked systems: the standalone launcher is a
        // macOS .app; on other desktops the libretro path is the only one.
        assertEquals(
            RomRoute.Libretro("/roms/game.wbfs"),
            routeRom("/roms/game.wbfs", null, macOs = false),
        )
        assertEquals(
            RomRoute.Libretro("/roms/game.cso"),
            routeRom("/roms/game.cso", null, macOs = false),
        )
    }

    @Test
    fun explicitStandaloneMappingKeepsIsoPspAssumption() {
        // The explicit "launch standalone" action historically assumes PSP
        // for .iso; smart-play's shared-table rule must not change it.
        assertEquals("psp", standaloneSystemIdForExtension("iso"))
        assertEquals("psp", standaloneSystemIdForExtension("cso"))
        assertEquals("gamecube_wii", standaloneSystemIdForExtension("gcm"))
        assertEquals("ps3", standaloneSystemIdForExtension("pkg"))
        assertEquals("wii_u", standaloneSystemIdForExtension("wud"))
        assertEquals("xbox", standaloneSystemIdForExtension("xiso"))
        assertNull(standaloneSystemIdForExtension("smc"))
    }

    @Test
    fun setupCompletenessTreatsZeroApplicableAsComplete() {
        // Windows/Linux have zero applicable emulator specs; setup must
        // complete there instead of reporting eternally-missing emulators.
        assertNull(setupIncompleteMessage(coresMissing = 0, emulatorsMissing = 0))
        assertTrue(
            setupIncompleteMessage(coresMissing = 2, emulatorsMissing = 0)!!.contains("2 core(s)"),
        )
        assertTrue(
            setupIncompleteMessage(coresMissing = 0, emulatorsMissing = 3)!!.contains("3 emulator(s)"),
        )
    }
}
