package com.omilator.ui.player

import com.omilator.core.audio.AudioOutput
import com.omilator.core.libretro.api.AudioSink
import com.omilator.core.libretro.api.AvInfo
import com.omilator.core.libretro.api.CoreController
import com.omilator.core.libretro.api.CoreOption
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.Geometry
import com.omilator.core.libretro.api.InputSource
import com.omilator.core.libretro.api.SystemInfo
import com.omilator.core.libretro.api.Timing
import com.omilator.core.libretro.api.VideoSink
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * stop() racing an in-flight start() (window close during a long
 * loadGame — big need_fullpath ISOs): the old order snapshotted
 * frameLoop (null while start()'s core-thread block hadn't reached its
 * tail), tore the core down, and cancelled the scope only afterwards —
 * letting the just-launched frame task pass its liveness check and run
 * one retro_run against an unloaded core (native use-after-free).
 *
 * The fake controller sequence-logs every lifecycle call; the invariant
 * under test is that no runFrame is ever observed after unloadGame.
 */
class PlayerEngineStopRaceTest {

    private class LoadGateCore : CoreController {
        val ops = java.util.Collections.synchronizedList(mutableListOf<String>())

        /** Blocks loadGame until [releaseLoad] fires, so stop() provably
         *  arrives while start() is still in flight. */
        val enteredLoad = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        override val isLoaded = true
        override val memorySize: UInt = 0u

        override suspend fun loadCore(path: String): SystemInfo {
            ops += "loadCore"
            return SystemInfo("fake", "1.0", emptyList(), false, false)
        }

        override suspend fun loadGame(romPath: String): AvInfo {
            ops += "loadGame:enter"
            enteredLoad.countDown()
            releaseLoad.await()
            ops += "loadGame:exit"
            return AvInfo(
                geometry = Geometry(320u, 240u, 1024u, 768u, 4f / 3f),
                timing = Timing(fps = 60f, sampleRate = 48000.0),
            )
        }

        override fun runFrame() { ops += "runFrame" }
        override fun reset() {}
        override fun unloadGame() { ops += "unloadGame" }
        override fun unloadCore() { ops += "unloadCore" }
        override fun setSystemAvInfoListener(listener: ((AvInfo) -> Unit)?) {}
        override fun attach(video: VideoSink, audio: AudioSink, input: InputSource) {}
        override fun detach() {}
        override fun saveState(path: String) = false
        override fun loadState(path: String) = false
        override fun readMemory(region: UInt, offset: UInt, size: UInt) = ByteArray(size.toInt())
        override fun writeMemory(region: UInt, offset: UInt, data: ByteArray) {}
        override fun readSaveRam(): ByteArray = ByteArray(0)
        override fun writeSaveRam(data: ByteArray) {}
        override fun cheatReset() {}
        override fun saveStateToMemory(): ByteArray = ByteArray(0)
        override fun loadStateFromMemory(bytes: ByteArray) = false
        override fun cheatSet(index: Int, enabled: Boolean, code: String) {}
        override fun getCoreOptions(): List<CoreOption> = emptyList()
        override fun setOptionValue(key: String, value: String) {}
        override fun getOptionSelections(): Map<String, String> = emptyMap()
    }

    private class NoopAudioOutput : AudioOutput {
        override var sampleRate: Double = 48000.0
        override var channels: Int = 2
        override fun configure(sampleRate: Double, channels: Int) {}
        override fun write(samples: ShortArray): Int = samples.size
        override fun flush() {}
        override fun release() {}
    }

    @Test
    fun stopDuringInFlightStartNeverRunsFrameAfterUnload() {
        val controller = LoadGateCore()
        val dir = Files.createTempDirectory("omilator-stop-race").toFile()
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath
        val engine = PlayerEngine(
            corePath = "/fake/core.dylib",
            romPath = romPath,
            audioOutput = NoopAudioOutput(),
            controllerFactory = { controller },
            dataDir = dir.absolutePath,
            initializeGamepad = false,
        )

        // start() on its own thread; it parks inside loadGame.
        val starter = thread(name = "engine-start") {
            kotlinx.coroutines.runBlocking { engine.start() }
        }
        assertTrue(controller.enteredLoad.await(5, java.util.concurrent.TimeUnit.SECONDS), "loadGame never started")

        // Release the load from a third thread AFTER stop() is in flight:
        // stop()'s teardown hop queues on the single core thread and can
        // only complete once start()'s block returns, so the interleaving
        // under test (teardown lands, then start's tail launches the frame
        // loop) is exercised for real.
        thread(name = "load-releaser") {
            Thread.sleep(50)
            controller.releaseLoad.countDown()
        }
        engine.stop()
        starter.join(10_000)
        assertTrue(!starter.isAlive, "start() must return after stop()")

        val ops = controller.ops.toList()
        assertTrue("loadGame:exit" in ops, "load must have completed (test sanity)")
        assertTrue("unloadGame" in ops, "teardown must have run (test sanity)")

        val unloadIndex = ops.indexOf("unloadGame")
        val framesAfterUnload = ops.subList(unloadIndex, ops.size).count { it == "runFrame" }
        assertTrue(
            framesAfterUnload == 0,
            "no runFrame may execute after unloadGame; ops=$ops",
        )
    }
}
