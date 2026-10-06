package com.omilator.ui.player

import com.omilator.data.library.CoreDownloader
import com.omilator.data.library.GameSystem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop core resolution: explicit server platform metadata beats
 * extension detection (.rom cache files carry none), the narrowly scoped
 * GB/GBC mGBA fallback keeps automated setup resolvable (it installs
 * mGBA, not SameBoy), and the preferred core wins across all roots before
 * any compatible fallback is considered.
 */
class DesktopCoreResolverTest {

    private lateinit var roots: List<File>
    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = kotlin.io.path.createTempDirectory("omilator-resolver-test").toFile()
        val installRoot = File(tmp, "data/cores").apply { mkdirs() }
        val devRoot = File(tmp, "dev/cores").apply { mkdirs() }
        roots = listOf(devRoot, installRoot)
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun core(root: File, stem: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File(root, "${stem}_libretro.${coreLibraryExtension()}").apply { writeBytes(bytes) }

    @Test
    fun mgbaAloneSatisfiesGbGbcGbaButNotNes() {
        val install = roots[1]
        core(install, "mgba")
        assertNotNull(findInstalledCore(GameSystem.GAME_BOY, roots, coreLibraryExtension()))
        assertNotNull(findInstalledCore(GameSystem.GAME_BOY_COLOR, roots, coreLibraryExtension()))
        assertNotNull(findInstalledCore(GameSystem.GAME_BOY_ADVANCE, roots, coreLibraryExtension()))
        assertNull(findInstalledCore(GameSystem.NES, roots, coreLibraryExtension()))
    }

    @Test
    fun sameboyStaysPreferredWhenBothAreInstalled() {
        val install = roots[1]
        core(install, "mgba")
        core(install, "sameboy")
        val resolved = findInstalledCore(GameSystem.GAME_BOY, roots, coreLibraryExtension())
        assertEquals("sameboy_libretro.${coreLibraryExtension()}", resolved!!.name)
    }

    @Test
    fun preferredCoreWinsAcrossAllRootsOverDevFallback() {
        // SameBoy installed in the install root, mGBA only in the dev root:
        // the preferred core from ANY root must beat a compatible fallback.
        core(roots[1], "sameboy")
        core(roots[0], "mgba")
        val resolved = findInstalledCore(GameSystem.GAME_BOY, roots, coreLibraryExtension())
        assertEquals("sameboy_libretro.${coreLibraryExtension()}", resolved!!.name)
        assertEquals(roots[1], resolved.parentFile)
    }

    @Test
    fun unknownExtensionWithoutMetadataStillFails() {
        assertNull(resolveCorePath("/cache/123-456.rom", roots = roots))
    }

    @Test
    fun serverMetadataResolvesExtensionlessRom() {
        core(roots[1], "snes9x")
        val path = resolveCorePath("/cache/123-456.rom", systemOverride = GameSystem.SNES, roots = roots)
        assertNotNull(path)
        assertTrue(path.endsWith("snes9x_libretro.${coreLibraryExtension()}"))
    }

    @Test
    fun knownSystemWithoutCoreReturnsInstallPathForNativeLoadError() {
        val path = resolveCorePath("/roms/game.gba", roots = roots)
        assertNotNull(path)
        // No mGBA anywhere: the expected install path under the shared
        // cores dir, so the player surfaces the real load error.
        assertEquals(
            File(com.omilator.data.settings.DesktopPaths.coresDir, "mgba_libretro.${coreLibraryExtension()}").absolutePath,
            path,
        )
    }

    @Test
    fun zeroLengthCoreFilesAreIgnored() {
        core(roots[1], "mgba", bytes = ByteArray(0))
        assertNull(findInstalledCore(GameSystem.GAME_BOY, roots, coreLibraryExtension()))
    }

    @Test
    fun catalogInvariantEveryAutoProvisionedSystemHasACompatibleCore() {
        // Every system advertised as automatically provisioned by the
        // installer must be satisfiable by the resolver's compatible-core
        // table — the GB/GBC gap (setup installs mGBA, resolver wanted
        // SameBoy only) is exactly what this pins.
        val downloader = CoreDownloader(File(tmp, "dl-target"))
        val installed = downloader.cores.map { it.name.removeSuffix("_libretro") }.toSet()
        assertTrue(installed.isNotEmpty())
        for (system in GameSystem.entries) {
            val satisfiable = compatibleCoreStems(system).any { it in installed }
            assertTrue(
                satisfiable,
                "${system} is provisioned by no installed core; stems=" +
                    "${compatibleCoreStems(system)} installed=$installed",
            )
        }
    }
}
