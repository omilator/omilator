package com.omilator.data.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Buildbot artifact mapping: the osx buildbot publishes beetle_psx_hw as
 * `mednafen_psx_hw`, so the canonical-name URL 404s, first-run setup can
 * never complete on macOS, and .iso quick-play stayed broken. This pins
 * the same urlName→core-id mapping the Android downloader already
 * carries, and that every entry's URL stays well-formed.
 */
class CoreDownloaderArtifactMappingTest {

    private val downloader = CoreDownloader(File("build/test-cores"))

    @Test
    fun beetlePsxHwDownloadsUnderItsPublishedArtifactName() {
        val entry = downloader.cores.first { it.name == "beetle_psx_hw_libretro" }
        assertEquals("mednafen_psx_hw_libretro", entry.artifact)
        val url = downloader.zipUrlFor(entry)
        assertTrue(url.endsWith("/mednafen_psx_hw_libretro.${ext()}.zip"), url)
        // The zip's member carries the published stem; the installed file
        // keeps the canonical name the resolver looks for.
        assertEquals("mednafen_psx_hw_libretro.${ext()}", downloader.archiveMemberFor(entry))
        assertEquals("beetle_psx_hw_libretro.${ext()}", downloader.installNameFor(entry))
    }

    @Test
    fun entriesWithoutArtifactUseTheirOwnName() {
        val entry = downloader.cores.first { it.name == "snes9x_libretro" }
        assertEquals(null, entry.artifact)
        assertTrue(downloader.zipUrlFor(entry).endsWith("/snes9x_libretro.${ext()}.zip"))
        assertEquals("snes9x_libretro.${ext()}", downloader.archiveMemberFor(entry))
        assertEquals("snes9x_libretro.${ext()}", downloader.installNameFor(entry))
    }

    @Test
    fun everyEntryHasAUniqueInstallName() {
        val names = downloader.cores.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    private fun ext(): String = System.getProperty("os.name").lowercase().let { os ->
        when {
            os.contains("win") -> "dll"
            os.contains("mac") -> "dylib"
            else -> "so"
        }
    }
}
