package com.omilator.data.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First-run setup platform gating: every EmulatorInstaller spec selects a
 * macOS asset, extracts via unzip/7z and installs a .app bundle. Running
 * it on Windows/Linux (as first-run setup did) could never complete — the
 * PPSSPP *mac* zip failed to extract on Windows (no unzip.exe), or a mac
 * bundle landed in ~/Applications on Linux and could never launch.
 */
class EmulatorInstallerPlatformTest {

    @Test
    fun windowsHasNoApplicableEmulatorSpecs() {
        assertTrue(EmulatorInstaller(osName = "Windows 11").emulators.isEmpty())
    }

    @Test
    fun linuxHasNoApplicableEmulatorSpecs() {
        assertTrue(EmulatorInstaller(osName = "Linux").emulators.isEmpty())
    }

    @Test
    fun macOsKeepsTheThreeStandaloneSpecs() {
        val emulators = EmulatorInstaller(osName = "Mac OS X").emulators
        assertEquals(listOf("psp", "xbox", "ps3"), emulators.map { it.systemId })
    }

    @Test
    fun emptySpecListMeansZeroInstalledAndZeroMissing() {
        val installer = EmulatorInstaller(osName = "Linux")
        assertEquals(0, installer.installedCount())
        // The setup completeness computation: 0 missing out of 0 applicable
        // is COMPLETE, not "incomplete" — that is what kept the first-run
        // dialog permanently open on Windows/Linux.
        val emulatorsMissing = installer.emulators.count { !installer.isInstalled(it) }
        assertEquals(0, emulatorsMissing)
    }
}
