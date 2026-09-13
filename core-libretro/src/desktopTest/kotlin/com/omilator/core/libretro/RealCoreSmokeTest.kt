package com.omilator.core.libretro

import com.omilator.core.libretro.impl.FfmCoreController
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-core smoke: loads real libretro dylibs from ../cores (gitignored
 * downloads - the test self-skips when absent), drives the environment
 * negotiation, option parsing, init, content load, run, serialize and
 * unload against native code.
 */
class RealCoreSmokeTest {

    private fun core(name: String): Path? {
        val p = Path.of(System.getProperty("user.dir")).resolve("../cores/$name")
        return if (java.nio.file.Files.exists(p)) p else null
    }

    @Test
    fun genesisPlusGxEnvironmentNegotiation() {
        val dylib = core("genesis_plus_gx_libretro.dylib") ?: run {
            println("SMOKE-SKIP: genesis_plus_gx core not present")
            return
        }
        val controller = FfmCoreController(systemDirectory = System.getProperty("java.io.tmpdir"))
        runBlocking {
            val info = controller.loadCore(dylib.toString())
            assertTrue(info.libraryName.isNotBlank(), "core library name")
            println("SMOKE core: ${info.libraryName} ${info.libraryVersion}")

            controller.getCoreOptions().let { opts ->
                println("SMOKE options declared: ${opts.size}")
                assertTrue(opts.isNotEmpty(), "core options parsed from a real core")
                assertTrue(opts.all { it.values.isNotEmpty() }, "every option exposes its choices")
                val sample = opts.first()
                println("SMOKE sample: ${sample.key} default=${sample.default} choices=${sample.values.size}")
            }

            // No content: negotiation-only for this core (running without a
            // ROM is not a supported libretro contract).
            controller.unloadGame()
            controller.unloadCore()
            println("SMOKE genesis negotiation + unload clean")
        }
    }

    @Test
    fun snes9xContentLoadRunSerialize() {
        val dylib = core("snes9x_libretro.dylib") ?: run {
            println("SMOKE-SKIP: snes9x core not present")
            return
        }
        // Minimal iNES image: 16-byte header, one 16KB PRG bank and one 8KB
        // CHR bank of zeros. Mesen boots it (to a black screen) exactly like
        // any real cartridge dump.
        val rom = Path.of(System.getProperty("java.io.tmpdir"), "omilator-smoke.sfc").toFile()
        rom.outputStream().use { out ->
            out.write(byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            out.write(ByteArray(16 * 1024))
            out.write(ByteArray(8 * 1024))
        }

        val controller = FfmCoreController(systemDirectory = System.getProperty("java.io.tmpdir"))
        runBlocking {
            val info = controller.loadCore(dylib.toString())
            println("SMOKE snes9x: ${info.libraryName}")
            val av = controller.loadGame(rom.absolutePath)
            assertTrue(av.geometry.baseWidth > 0u, "AV geometry reported")
            println("SMOKE av: ${av.geometry.baseWidth}x${av.geometry.baseHeight} @ ${av.timing.fps}fps ${av.timing.sampleRate}Hz")

            val opts = controller.getCoreOptions()
            println("SMOKE snes9x options: ${opts.size}")

            repeat(30) { controller.runFrame() }
            println("SMOKE 30 frames ran")

            val state = controller.saveStateToMemory()
            assertTrue(state.size > 0, "core serializes")
            println("SMOKE serialize: ${state.size} bytes")
            assertTrue(controller.loadStateFromMemory(state), "core unserializes")

            controller.setOptionValue(opts.first().key, opts.first().default)
            println("SMOKE option set + GET_VARIABLE_UPDATE path exercised")

            repeat(10) { controller.runFrame() }
            controller.unloadGame()
            controller.unloadCore()
            println("SMOKE snes9x content-load run serialize unload CLEAN")
        }
    }
}
