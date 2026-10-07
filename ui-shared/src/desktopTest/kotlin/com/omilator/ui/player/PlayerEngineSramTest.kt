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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level battery-save lifecycle, driven through a fake core: the
 * restore gate, the teardown flush, the periodic in-run checkpoint, and
 * the flush-without-stop path that backs the JVM shutdown hook.
 *
 * The window-close regression behind this suite: exitProcess(0) never runs
 * Compose disposal, so engine.stop() (the ONLY flush point) was skipped on
 * the most common quit gesture, losing every in-game save since launch.
 */
class PlayerEngineSramTest {

    /** Desktop writeSaveRam semantics: all-or-nothing on size match. */
    private class FakeCoreController : CoreController {
        @Volatile
        var sramBlock: ByteArray = ByteArray(0)
        var writeSaveRamCalls = 0
        var readSaveRamCalls = 0
        var failLoadGame = false
        var flushedToMemory = 0

        override val isLoaded = true
        override val memorySize: UInt = 0u

        override suspend fun loadCore(path: String) =
            SystemInfo("fake", "1.0", emptyList(), false, false)

        override suspend fun loadGame(romPath: String): AvInfo {
            if (failLoadGame) throw RuntimeException("retro_load_game failed")
            return AvInfo(
                geometry = Geometry(320u, 240u, 1024u, 768u, 4f / 3f),
                timing = Timing(fps = 60f, sampleRate = 48000.0),
            )
        }

        override fun runFrame() { flushedToMemory++ }
        override fun reset() {}
        override fun unloadGame() {}
        override fun unloadCore() {}

        private var systemAvInfoListener: ((AvInfo) -> Unit)? = null
        override fun setSystemAvInfoListener(listener: ((AvInfo) -> Unit)?) {
            systemAvInfoListener = listener
        }

        fun emitGeometry(g: Geometry) {
            systemAvInfoListener?.invoke(
                AvInfo(geometry = g, timing = Timing(fps = 60f, sampleRate = 48000.0)),
            )
        }

        fun emitAvInfo(g: Geometry, fps: Float, sampleRate: Double) {
            systemAvInfoListener?.invoke(
                AvInfo(geometry = g, timing = Timing(fps = fps, sampleRate = sampleRate)),
            )
        }

        override fun attach(video: VideoSink, audio: AudioSink, input: InputSource) {}
        override fun detach() {}
        override fun saveState(path: String) = false
        override fun loadState(path: String) = false
        override fun readMemory(region: UInt, offset: UInt, size: UInt) = ByteArray(size.toInt())
        override fun writeMemory(region: UInt, offset: UInt, data: ByteArray) {}
        override fun readSaveRam(): ByteArray {
            readSaveRamCalls++
            return sramBlock.copyOf()
        }

        override fun writeSaveRam(data: ByteArray) {
            writeSaveRamCalls++
            if (data.size == sramBlock.size) sramBlock = data.copyOf()
        }

        override fun cheatReset() {}
        override fun saveStateToMemory(): ByteArray = ByteArray(0)
        override fun loadStateFromMemory(bytes: ByteArray) = false
        override fun cheatSet(index: Int, enabled: Boolean, code: String) {}
        override fun getCoreOptions(): List<CoreOption> = emptyList()
        override fun setOptionValue(key: String, value: String) {}
        override fun getOptionSelections(): Map<String, String> = emptyMap()
    }

    private class FakeAudioOutput : AudioOutput {
        override var sampleRate: Double = 48000.0
        override var channels: Int = 2
        var releases = 0
        var configures = 0
        override fun configure(sampleRate: Double, channels: Int) {
            configures++
            this.sampleRate = sampleRate
            this.channels = channels
        }
        override fun write(samples: ShortArray): Int = samples.size
        override fun flush() {}
        override fun release() { releases++ }
    }

    private fun tempDir(): File = Files.createTempDirectory("omilator-sram-test").toFile()

    private fun newEngine(
        controller: FakeCoreController,
        dataDir: File,
        flushIntervalMillis: Long = PlayerEngine.DEFAULT_SRAM_FLUSH_MILLIS,
    ) = PlayerEngine(
        corePath = "/fake/core.dylib",
        romPath = File(dataDir, "game.gba").apply { writeText("rom") }.absolutePath,
        audioOutput = FakeAudioOutput(),
        sramFlushIntervalMillis = flushIntervalMillis,
        controllerFactory = { controller },
        dataDir = dataDir.absolutePath,
        initializeGamepad = false,
    )

    /** The engine's per-ROM SRAM identity: sanitized basename + first 8
     *  bytes of the canonical path's SHA-256. */
    private fun sramFileFor(dataDir: File): File {
        val romPath = File(dataDir, "game.gba")
        val id = java.security.MessageDigest.getInstance("SHA-256")
            .digest(romPath.canonicalPath.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(File(dataDir, "saves"), "game-$id.srm")
    }

    private fun startAndWait(engine: PlayerEngine, timeoutMs: Long = 10_000) {
        kotlinx.coroutines.runBlocking { engine.start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.state.value.isLoading) {
            check(System.currentTimeMillis() < deadline) { "engine never finished loading" }
            Thread.sleep(10)
        }
    }

    @Test
    fun stopFlushesMutatedSramToDisk() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val engine = newEngine(controller, dir)
        // No save file: fresh RAM is authoritative.
        controller.sramBlock = ByteArray(4096) { 0x00.toByte() }
        startAndWait(engine)

        // Player makes in-game progress...
        controller.sramBlock = ByteArray(4096) { 0x77.toByte() }
        engine.stop()

        val f = sramFileFor(dir)
        assertTrue(f.exists(), "stop() must flush battery RAM to disk")
        assertTrue(f.readBytes().contentEquals(ByteArray(4096) { 0x77.toByte() }))
    }

    @Test
    fun matchingRestoreFlushesOnlyTheCoreBlock() {
        val controller = FakeCoreController()
        val dir = tempDir()
        // Pre-existing save, same size as the core block.
        val saved = ByteArray(2048) { 0x33.toByte() }
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath
        val engine = PlayerEngine(
            corePath = "/fake/core.dylib",
            romPath = romPath,
            audioOutput = FakeAudioOutput(),
            controllerFactory = { controller },
            dataDir = dir.absolutePath,
            initializeGamepad = false,
        )
        controller.sramBlock = ByteArray(2048)
        // Place the save file under the identity the engine will use: run
        // one restore cycle first to learn the file name.
        startAndWait(engine)
        engine.stop()
        val f = sramFileFor(dir)
        assertTrue(f.exists())
        f.writeBytes(saved)

        // Second session: restore takes (sizes match), player progresses,
        // stop flushes the new content.
        controller.sramBlock = ByteArray(2048)
        val engine2 = PlayerEngine(
            corePath = "/fake/core.dylib",
            romPath = romPath,
            audioOutput = FakeAudioOutput(),
            controllerFactory = { controller },
            dataDir = dir.absolutePath,
            initializeGamepad = false,
        )
        startAndWait(engine2)
        assertNull(engine2.state.value.sramNotice, "clean restore must not warn")
        controller.sramBlock = ByteArray(2048) { 0x44.toByte() }
        engine2.stop()
        assertTrue(f.readBytes().contentEquals(ByteArray(2048) { 0x44.toByte() }), "flush must persist new RAM")
    }

    @Test
    fun sizeMismatchBacksUpOldSaveAndStartsANewOne() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath

        // Session 1 with an 8 KB block establishes the save file.
        controller.sramBlock = ByteArray(8192) { 0x11.toByte() }
        val engine1 = PlayerEngine(romPath = romPath, corePath = "/fake/core.dylib",
            audioOutput = FakeAudioOutput(), controllerFactory = { controller },
            dataDir = dir.absolutePath, initializeGamepad = false)
        startAndWait(engine1)
        controller.sramBlock = ByteArray(8192) { 0x12.toByte() }
        engine1.stop()
        val f = sramFileFor(dir)
        assertTrue(f.readBytes().contentEquals(ByteArray(8192) { 0x12.toByte() }))

        // Session 2 after a core update grew the SRAM block to 32 KB.
        controller.sramBlock = ByteArray(32768) { 0x99.toByte() }
        val engine2 = PlayerEngine(romPath = romPath, corePath = "/fake/core.dylib",
            audioOutput = FakeAudioOutput(), controllerFactory = { controller },
            dataDir = dir.absolutePath, initializeGamepad = false)
        startAndWait(engine2)
        controller.sramBlock = ByteArray(32768) { 0xAB.toByte() }
        engine2.stop()

        val backup = File(f.parentFile, "${f.name}.bak")
        assertTrue(backup.exists(), "old save must be preserved as a backup")
        assertTrue(backup.readBytes().contentEquals(ByteArray(8192) { 0x12.toByte() }), "backup must be byte-identical")
        assertTrue(f.readBytes().contentEquals(ByteArray(32768) { 0xAB.toByte() }), "new save must be flushable")
        assertNotNull(engine2.state.value.sramNotice, "mismatch must be surfaced, not console-only")
    }

    @Test
    fun periodicCheckpointFlushesWithoutStop() {
        val controller = FakeCoreController()
        val dir = tempDir()
        // 100 ms interval at 60 fps → a checkpoint every ~6 frames.
        val engine = newEngine(controller, dir, flushIntervalMillis = 100)
        controller.sramBlock = ByteArray(1024) { 0x00.toByte() }
        startAndWait(engine)

        controller.sramBlock = ByteArray(1024) { 0x5A.toByte() }
        val f = sramFileFor(dir)
        val deadline = System.currentTimeMillis() + 5_000
        while (!(f.exists() && f.readBytes().contentEquals(ByteArray(1024) { 0x5A.toByte() }))) {
            check(System.currentTimeMillis() < deadline) { "periodic SRAM checkpoint never flushed" }
            Thread.sleep(20)
        }
        engine.stop()
    }

    @Test
    fun flushWithoutStopCoversTheShutdownHookPath() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val engine = newEngine(controller, dir)
        controller.sramBlock = ByteArray(512) { 0x66.toByte() }
        startAndWait(engine)

        // The shutdown hook's body: flush even though stop() was never
        // called (the process-exit scenario).
        assertTrue(engine.flushSramNow())
        val f = sramFileFor(dir)
        assertTrue(f.exists(), "flushSramNow must persist the battery save")
        assertTrue(f.readBytes().contentEquals(ByteArray(512) { 0x66.toByte() }))
        engine.stop()
    }

    @Test
    fun stopIsIdempotentAndDoesNotRefetchSram() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val engine = newEngine(controller, dir)
        controller.sramBlock = ByteArray(256)
        startAndWait(engine)
        engine.stop()
        val readsAfterFirstStop = controller.readSaveRamCalls
        engine.stop() // close-handler + dispose + hook race
        assertEquals(readsAfterFirstStop, controller.readSaveRamCalls, "second stop must be a no-op")
    }

    @Test
    fun failedLoadGameNeverFlushesFreshRamOverTheSave() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath
        val engine1 = PlayerEngine(romPath = romPath, corePath = "/fake/core.dylib",
            audioOutput = FakeAudioOutput(), controllerFactory = { controller },
            dataDir = dir.absolutePath, initializeGamepad = false)
        controller.sramBlock = ByteArray(4096) { 0x0F.toByte() }
        startAndWait(engine1)
        engine1.stop()
        val f = sramFileFor(dir)
        assertTrue(f.exists())

        // Next launch fails inside loadGame: teardown must not write the
        // core's fresh zero RAM over the durable save.
        controller.sramBlock = ByteArray(4096)
        controller.failLoadGame = true
        val engine2 = PlayerEngine(romPath = romPath, corePath = "/fake/core.dylib",
            audioOutput = FakeAudioOutput(), controllerFactory = { controller },
            dataDir = dir.absolutePath, initializeGamepad = false)
        // start() swallows the failure into state.error; teardown ran there.
        startAndWait(engine2)
        assertNotNull(engine2.state.value.error)
        assertTrue(f.readBytes().contentEquals(ByteArray(4096) { 0x0F.toByte() }), "failed startup must not clobber the save")
    }

    @Test
    fun geometryListenerPublishesMidRunGeometryChanges() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val engine = newEngine(controller, dir)
        controller.sramBlock = ByteArray(128)
        startAndWait(engine)
        assertEquals(4f / 3f, engine.state.value.geometry?.aspectRatio)

        // Core switches display mode (SET_SYSTEM_AV_INFO equivalent).
        controller.emitGeometry(Geometry(640u, 480u, 1024u, 768u, 16f / 9f))
        assertEquals(16f / 9f, engine.state.value.geometry?.aspectRatio, "geometry change must reach player state")
        engine.stop()
    }

    @Test
    fun systemAvInfoTimingRepacesLoopAndReconfiguresAudio() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val audio = FakeAudioOutput()
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath
        val engine = PlayerEngine(
            corePath = "/fake/core.dylib",
            romPath = romPath,
            audioOutput = audio,
            controllerFactory = { controller },
            dataDir = dir.absolutePath,
            initializeGamepad = false,
        )
        controller.sramBlock = ByteArray(128)
        startAndWait(engine)
        assertEquals(60f, engine.state.value.fps)
        assertEquals(48000.0, audio.sampleRate)
        assertEquals(1_000_000_000L / 60L, engine.targetFrameIntervalNanos, "load-time pacing must be 60 Hz")

        // PAL-style switch: 50 fps + 44.1 kHz via SET_SYSTEM_AV_INFO.
        controller.emitAvInfo(Geometry(320u, 240u, 1024u, 768u, 4f / 3f), fps = 50f, sampleRate = 44100.0)
        assertEquals(50f, engine.state.value.fps, "fps change must reach player state")
        assertEquals(44100.0, audio.sampleRate, "audio pipeline must be reconfigured to the new rate")
        assertEquals(1_000_000_000L / 50L, engine.targetFrameIntervalNanos, "run-loop pacing must follow the new fps")
        engine.stop()
    }

    @Test
    fun floatNoiseTimingDoesNotChurnAudioConfig() {
        val controller = FakeCoreController()
        val dir = tempDir()
        val audio = FakeAudioOutput()
        val romPath = File(dir, "game.gba").apply { writeText("rom") }.absolutePath
        val engine = PlayerEngine(
            corePath = "/fake/core.dylib",
            romPath = romPath,
            audioOutput = audio,
            controllerFactory = { controller },
            dataDir = dir.absolutePath,
            initializeGamepad = false,
        )
        controller.sramBlock = ByteArray(128)
        startAndWait(engine)
        val configures = audio.configures

        // 59.995 fps / +0.5 Hz is float noise, not a mode change.
        controller.emitAvInfo(Geometry(320u, 240u, 1024u, 768u, 4f / 3f), fps = 59.995f, sampleRate = 48000.5)
        assertEquals(configures, audio.configures, "noise-level timing change must not reconfigure audio")
        engine.stop()
    }
}
