package com.omilator.ui.player

import com.omilator.core.audio.AudioOutput
import com.omilator.core.input.GamepadPoller
import com.omilator.core.libretro.api.CoreController
import com.omilator.core.libretro.api.AvInfo
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.Geometry
import com.omilator.core.libretro.api.InputDevice
import com.omilator.core.libretro.api.InputSource
import com.omilator.core.libretro.api.JoypadButton
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.api.VideoSink
import com.omilator.core.libretro.createCoreController
import com.omilator.data.settings.DesktopPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PlayerEngine(
    private val corePath: String,
    private val romPath: String,
    private val audioOutput: AudioOutput,
    /** Interval for the in-run SRAM checkpoint; injectable so tests don't
     *  wait wall-clock seconds. */
    private val sramFlushIntervalMillis: Long = DEFAULT_SRAM_FLUSH_MILLIS,
    /** Injectable controller + data root: the SRAM gate and the shutdown
     *  flush are engine-level behavior worth pinning with a fake core. */
    controllerFactory: () -> CoreController = { createCoreController(systemDirectory = DesktopPaths.dataDir) },
    private val dataDir: String = DesktopPaths.dataDir,
    /** Tests run without a display/GLFW: the poller's init() loads native
     *  GLFW, and destroy() is a no-op when init never ran. */
    private val initializeGamepad: Boolean = true,
) {
    companion object {
        internal const val DEFAULT_SRAM_FLUSH_MILLIS = 5_000L

        /** Below this, fps differences are float noise (59.940 vs 59.9401),
         *  not a mode change worth reconfiguring audio/pacing for. */
        private const val FPS_CHANGE_EPSILON = 0.01f
        private const val SAMPLE_RATE_EPSILON = 1.0
    }

    /**
     * Every libretro call runs on this one thread. The core (and the hidden
     * OpenGL context used for HW render) are thread-affine; letting load, run,
     * save and unload hop between Dispatchers.Default workers raced retro_run
     * against teardown and put the GL context on a thread that never owned it.
     */
    private val coreDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omilator-core").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + coreDispatcher)
    private val controller: CoreController = controllerFactory()
    private val converter = FrameConverter()
    private val inputState = InputStateHolder()
    private val gamepadPoller = GamepadPoller()
    private val latestFrame = AtomicReference<Framebuffer?>(null)

    /** Volatile: stop() reads this from the window thread while start()'s
     *  core-thread block assigns it at its tail — the exact interleaving
     *  the close-during-load race lives in. */
    @Volatile
    private var frameLoop: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Speed multiplier for fast-forward (Tab) / slow-motion (Shift+Tab). */
    private var speedMultiplier: Float = 1.0f

    /** Set once stop() begins; the shutdown hook and Compose disposal can
     *  both race to stop the same engine. */
    private val stopped = AtomicBoolean(false)

    // Process exit does not run Compose disposal. Every quit gesture that
    // bypasses the player screen (the window-close handler stops the engine
    // synchronously, but hard-exit paths remain) still trips shutdown
    // hooks, so this one bounds itself and flushes the battery save if the
    // engine was never stopped.
    private val shutdownHook = Thread {
        if (!stopped.get()) {
            runCatching { flushSramNow() }
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    suspend fun start() = withContext(coreDispatcher) {
        _state.update { it.copy(isLoading = true) }
        try {
            controller.loadCore(corePath)
            // Geometry/timing can change as early as load_game (cores signal
            // SET_SYSTEM_AV_INFO during init); the listener must be in
            // before content load or the first report is lost.
            controller.setSystemAvInfoListener(::onSystemAvInfo)
            val avInfo = controller.loadGame(romPath)
            controller.attach(
                video = VideoSink { fb -> latestFrame.set(fb) },
                audio = AudioSinkAdapter(audioOutput, { volume }, { suppressAudio }),
                input = InputSourceAdapter(inputState),
            )
            audioOutput.configure(avInfo.timing.sampleRate, channels = 2)
            if (initializeGamepad) gamepadPoller.init()
            loadPersistedOptions()  // Apply saved core options for this ROM
            restoreSram()
            frameIntervalNanosFor(avInfo.timing.fps).let { targetFrameIntervalNanos = it }
            currentFps = avInfo.timing.fps
            currentSampleRate = avInfo.timing.sampleRate
            _state.update {
                it.copy(isLoading = false, geometry = avInfo.geometry, fps = avInfo.timing.fps, sramNotice = sramNotice)
            }
            frameLoop = scope.launch { runLoop(avInfo.timing.fps) }
        } catch (t: Throwable) {
            // A half-started engine (core loaded, then loadGame/audio/options
            // failed) must tear itself down — stop() is not guaranteed to
            // run after a startup error.
            teardownNative()
            _state.update { it.copy(isLoading = false, error = "${t::class.simpleName}: ${t.message}") }
        }
    }

    /** SET_SYSTEM_AV_INFO: the core changed display mode mid-run. Publish
     *  the new geometry so the aspect-fit rectangle follows it instead of
     *  staying at the load-time ratio — and follow the timing half too:
     *  pacing and the audio config used to be computed once at start and
     *  never updated, so an fps/sample-rate switch desynced A/V until the
     *  player was reopened. */
    private fun onSystemAvInfo(info: AvInfo) {
        _state.update { it.copy(geometry = info.geometry, fps = info.timing.fps) }
        if (kotlin.math.abs(info.timing.fps - currentFps) > FPS_CHANGE_EPSILON) {
            currentFps = info.timing.fps
            targetFrameIntervalNanos = frameIntervalNanosFor(info.timing.fps)
        }
        if (kotlin.math.abs(info.timing.sampleRate - currentSampleRate) > SAMPLE_RATE_EPSILON) {
            currentSampleRate = info.timing.sampleRate
            // Fires on the core thread (inside runFrame/loadGame) — the
            // same thread start() configured the output on, so configure
            // never races write().
            runCatching { audioOutput.configure(info.timing.sampleRate, channels = 2) }
        }
    }

    /** Run-loop pacing source, in nanoseconds: read fresh every iteration
     *  so a mid-run timing change takes effect without restarting the
     *  loop. Visible to tests (desktopTest is the same module). */
    @Volatile
    internal var targetFrameIntervalNanos = 0L
        private set

    @Volatile
    private var currentFps = 0f

    @Volatile
    private var currentSampleRate = 0.0

    private fun frameIntervalNanosFor(fps: Float): Long =
        (1_000_000_000.0 / fps.coerceAtLeast(0.1f)).toLong()

    /** True once the core's SRAM block actually represents this game's
     *  battery save (a restored one, or a fresh one when no save file
     *  exists yet). Teardown may only flush SRAM to disk while this holds:
     *  a failed startup (loadGame threw) or a restore that never took
     *  effect leaves fresh core RAM in the slot, and flushing that would
     *  write zeros over the player's only durable battery save. */
    private var sramAuthoritative = false

    /** Non-fatal battery-save condition surfaced in [PlayerState]; null
     *  when the restore was clean. */
    private var sramNotice: String? = null

    /** Battery saves live in the core's SRAM block; without this round-trip
     *  every exit threw away everything since the last flush. */
    private fun restoreSram() {
        val f = sramFile()
        if (!f.exists()) {
            // No battery save yet: the fresh core RAM is the authoritative
            // copy and new progress must be flushable.
            sramAuthoritative = true
            return
        }
        val decision = runCatching {
            evaluateSramRestore(
                saved = f.readBytes(),
                readSaveRam = controller::readSaveRam,
                writeSaveRam = controller::writeSaveRam,
            )
        }.getOrNull()
        when {
            decision == null ->
                // The save file itself could not be read; treating fresh RAM
                // as authoritative risks destroying whatever is on disk.
                sramNotice = "Battery save could not be read; progress will not be saved this session."
            decision.migrate -> {
                // Size mismatch: preserve the old bytes under a backup name
                // (never overwrite an existing backup) and let the core's
                // fresh block start a new save.
                if (backUpSaveFile(f)) {
                    sramAuthoritative = true
                    sramNotice = decision.notice
                } else {
                    sramNotice = "Battery save could not be backed up; progress will not be saved this session."
                }
            }
            else -> {
                sramAuthoritative = decision.authoritative
                sramNotice = decision.notice
            }
        }
    }

    /** Moves [f] aside as `<name>.srm.bak` (numbered when one exists) so
     *  both the old bytes and any future save survive. */
    private fun backUpSaveFile(f: java.io.File): Boolean = runCatching {
        var target = java.io.File(f.parentFile, "${f.name}.bak")
        var n = 2
        while (target.exists()) {
            target = java.io.File(f.parentFile, "${f.name}.bak$n")
            n++
        }
        f.renameTo(target)
    }.getOrDefault(false)

    private fun flushSram() {
        if (!sramAuthoritative) return
        runCatching {
            val data = controller.readSaveRam()
            if (data.isEmpty()) return
            // Atomic replace: a crash mid-write over the live file would
            // corrupt the only durable battery save.
            val dst = sramFile().toPath()
            val tmp = dst.resolveSibling(".arcade-tmp-sram")
            java.nio.file.Files.write(tmp, data)
            try {
                java.nio.file.Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** One bounded flush hop to the core thread — used by the shutdown hook,
     *  where a wedged core thread must not hang process exit. Safe (no-op
     *  failure) after the engine is stopped. */
    fun flushSramNow(timeoutMillis: Long = 2_000): Boolean = runCatching {
        runBlocking {
            withTimeoutOrNull(timeoutMillis) {
                withContext(coreDispatcher) { flushSram() }
            }
        } != null
    }.getOrDefault(false)

    /** Stable per-ROM identity: the basename alone collided across games in
     *  different directories or systems, and hashed into SRAM and option
     *  file names keeps both independent. */
    private fun romIdHash(): String {
        val canonical = java.io.File(romPath).canonicalPath
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sanitizedRomBase(): String =
        java.io.File(romPath).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sramFile(): java.io.File {
        val dir = java.io.File(dataDir, "saves").apply { mkdirs() }
        return java.io.File(dir, "${sanitizedRomBase()}-${romIdHash()}.srm")
    }

    /** Manual save states follow the same path policy as SRAM/options
     *  (DesktopPaths, not a rebuilt macOS path) and the same per-ROM
     *  identity, so identically named ROMs never share state slots. */
    fun stateFile(slot: Int): java.io.File {
        val dir = java.io.File(dataDir, "states").apply { mkdirs() }
        return java.io.File(dir, "${sanitizedRomBase()}-${romIdHash()}.slot$slot.state")
    }

    private fun loadPersistedOptions() {
        val file = optionsFile()
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) controller.setOptionValue(parts[0], parts[1])
            }
        } catch (_: Throwable) {}
    }

    private fun persistOptions() {
        try {
            val selections = controller.getOptionSelections()
            if (selections.isEmpty()) return
            val text = selections.entries.joinToString("\n") { "${it.key}=${it.value}" }
            optionsFile().writeText(text)
        } catch (_: Throwable) {}
    }

    private fun optionsFile(): java.io.File {
        val dir = java.io.File(dataDir, "options").apply { mkdirs() }
        // Same identity scheme as SRAM: two identically named ROMs used to
        // share one option file. The content is key=value lines, so the
        // extension says .properties, not .json.
        return java.io.File(dir, "${sanitizedRomBase()}-${romIdHash()}.properties")
    }

    /**
     * Joins the frame loop before touching the core: cancelling without
     * joining let the unload below race an in-flight retro_run, and cancelling
     * the scope first could kill the unload coroutine before it ever ran.
     * Teardown then happens on the core thread itself.
     *
     * Idempotent: the window-close handler, Compose disposal and the JVM
     * shutdown hook can all race to stop the same engine, and a second full
     * teardown would join a dead dispatcher.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // Cancel the scope BEFORE the teardown hop and BEFORE reading
        // frameLoop. The old order (snapshot loop → teardown → cancel)
        // missed the close-during-load window: start() assigns frameLoop
        // at the tail of its own core-thread block, so a stop() that
        // arrives mid-loadGame snapshots null, tears the core down — and
        // the just-launched loop task, not yet cancelled at its liveness
        // check, could execute one retro_run against the unloaded core.
        // Cancelling first means any loop task start() launches from now
        // on is born cancelled and never runs; the volatile re-read below
        // joins a task that was already in flight.
        scope.cancel()
        runBlocking {
            frameLoop?.cancelAndJoin()
            withContext(coreDispatcher) {
                teardownNative()
            }
        }
        coreDispatcher.close()
        // The hook's job is done; leaving it registered would keep a stopped
        // engine reachable until exit. During shutdown this throws, which is
        // fine (best effort).
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    /** Idempotent core/audio/input teardown; shared by stop() and the
     *  failure path of start() so a half-started engine cleans up after
     *  itself. Runs on the core thread. */
    @Volatile
    private var tornDown = false

    private fun teardownNative() {
        if (tornDown) return
        tornDown = true
        runCatching { controller.setSystemAvInfoListener(null) }
        runCatching { controller.detach() }
        runCatching { flushSram() }
        runCatching { controller.unloadGame() }
        runCatching { controller.unloadCore() }
        runCatching { gamepadPoller.destroy() }
        runCatching { audioOutput.release() }
    }

    fun pressButton(button: Int) {
        inputState.press(button)
    }

    fun releaseButton(button: Int) {
        inputState.release(button)
    }

    fun saveState(path: String): Boolean = runBlocking {
        withContext(coreDispatcher) { controller.saveState(path) }
    }.also { ok ->
        // Also save a thumbnail of the current frame
        if (ok) {
            val frame = latestFrame.get()
            if (frame != null) {
                try {
                    val img = converter.convert(frame)
                    val thumbPath = path.replace(".state", ".png")
                    javax.imageio.ImageIO.write(img, "PNG", java.io.File(thumbPath))
                } catch (_: Throwable) {}
            }
        }
    }

    fun loadState(path: String): Boolean = runBlocking {
        withContext(coreDispatcher) { controller.loadState(path) }
    }

    fun renderFrameIfAvailable(): BufferedImage? {
        val fb = latestFrame.getAndSet(null) ?: return null
        return converter.convert(fb)
    }

    private suspend fun runLoop(targetFps: Float) {
        var nextDeadline = System.nanoTime()
        // Crash protection for the battery save: teardown is the only
        // flush point, so a crash (or any quit path that skips it) lost
        // every save since launch. A cheap periodic checkpoint bounds the
        // loss window while the gate is authoritative.
        val flushEveryFrames = ((targetFps * sramFlushIntervalMillis) / 1000f).toInt().coerceAtLeast(1)
        var framesSinceFlush = 0
        // The loop observes ITS OWN cancellation: testing the parent scope
        // meant stop()'s cancelAndJoin could wait forever on a loop running
        // behind schedule that never reached a suspension point. The
        // stopped flag backstops the (now first-class) scope cancellation
        // against a resume already past the liveness check.
        while (currentCoroutineContext().isActive && !stopped.get()) {
            currentCoroutineContext().ensureActive()
            // Poll gamepad before each frame. Gamepad writes go to the
            // gamepad-owned mask only — the poller reports every button
            // (false for neutral/absent pads), and routing that through
            // press/release erased keyboard-held buttons before the core
            // could read them.
            gamepadPoller.poll(
                setButton = inputState::setGamepadButton,
                setAnalog = inputState::setAnalog,
            )

            controller.runFrame()
            tickRewind()
            if (++framesSinceFlush >= flushEveryFrames) {
                framesSinceFlush = 0
                flushSram()
            }

            // Run-ahead: speculative extra frame for reduced input latency.
            // Display shows 1 frame ahead; state rolls back to real frame.
            // Audio produced by the speculative frame is dropped — it belongs
            // to a frame the rollback throws away.
            if (runAhead > 0) {
                try {
                    val savedState = controller.saveStateToMemory()
                    if (savedState.isEmpty()) {
                        // No serialization support: continuing would leave the
                        // core on speculative state, one frame ahead.
                        runAhead = 0
                    } else {
                        suppressAudio = true
                        try {
                            controller.runFrame()
                        } finally {
                            suppressAudio = false
                            if (!controller.loadStateFromMemory(savedState)) {
                                // Rollback failed: the core stays on the
                                // speculative frame, so run-ahead is off from
                                // here on rather than silently drifting.
                                runAhead = 0
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Adjust deadline by speed multiplier (fast forward / slow motion).
            // The interval is re-read each iteration: a mid-run
            // SET_SYSTEM_AV_INFO timing change updates it live.
            val interval = (targetFrameIntervalNanos / speedMultiplier).toLong()
            nextDeadline += interval
            val now = System.nanoTime()
            val wait = nextDeadline - now
            if (wait > 0) delay(wait / 1_000_000)
            else nextDeadline = now
        }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        speedMultiplier = multiplier.coerceIn(0.1f, 10f)
    }

    fun getSpeedMultiplier(): Float = speedMultiplier

    // ---- Rewind system ----
    private val rewindBuffer = java.util.ArrayDeque<ByteArray>()
    private var rewindFrameCounter = 0

    /** Serialized states of large cores run to multiple MB, so the buffer
     *  is bounded by bytes as well as count — a snapshot cap alone allowed
     *  hundreds of MB to pile up. */
    private val maxRewindBytes = 64L * 1024 * 1024
    private var rewindBytes = 0L

    private fun addRewindState(state: ByteArray) {
        // Cores without serialization return an empty state; buffering it
        // would make rewind replay zero-byte states forever.
        if (state.isEmpty() || state.size > maxRewindBytes) return

        rewindBuffer.addLast(state)
        rewindBytes += state.size
        while (rewindBuffer.size > 300 || rewindBytes > maxRewindBytes) {
            rewindBytes -= rewindBuffer.removeFirst().size
        }
    }

    fun tickRewind() {
        rewindFrameCounter++
        if (rewindFrameCounter >= 10) {
            rewindFrameCounter = 0
            try {
                addRewindState(controller.saveStateToMemory())
            } catch (_: Throwable) {}
        }
    }

    fun rewindStep(): Boolean = runBlocking {
        withContext(coreDispatcher) {
            val state = rewindBuffer.pollLast() ?: return@withContext false
            rewindBytes -= state.size
            controller.loadStateFromMemory(state)
        }
    }

    // ---- Cheats ----
    fun applyCheat(code: String) = runBlocking {
        withContext(coreDispatcher) {
            controller.cheatReset()
            controller.cheatSet(0, true, code.trim())
        }
        Unit
    }

    // ---- Core options ----
    fun getCoreOptions() = controller.getCoreOptions()
    fun setOptionValue(key: String, value: String) = runBlocking {
        withContext(coreDispatcher) {
            controller.setOptionValue(key, value)
        }
        persistOptions()
        Unit
    }

    // ---- Run-ahead (latency reduction) ----
    private var runAhead = 0
    fun toggleRunAhead() { runAhead = if (runAhead > 0) 0 else 1 }
    fun isRunAhead(): Boolean = runAhead > 0

    /** True while the speculative run-ahead frame runs; its audio is dropped. */
    @Volatile
    private var suppressAudio = false

    // ---- Audio volume ----
    @Volatile
    private var volume: Float = 1.0f
    fun setVolume(v: Float) { volume = v.coerceIn(0f, 2f) }
    fun getVolume(): Float = volume
}

data class PlayerState(
    val isLoading: Boolean = true,
    val geometry: Geometry? = null,
    val fps: Float = 60f,
    val error: String? = null,
    /** Non-fatal battery-save condition (e.g. a size-mismatch migration);
     *  surfaced as a banner instead of a console-only print. */
    val sramNotice: String? = null,
)

private class InputSourceAdapter(private val holder: InputStateHolder) : InputSource {
    override fun poll(port: Int, device: InputDevice, index: Int, id: Int): Int {
        if (port != 0) return 0
        return when (device) {
            InputDevice.JOYPAD -> holder.get(id)
            InputDevice.ANALOG -> when (index) {
                // Libretro encodes stick in `index` (0=left, 1=right) and
                // axis in `id` (0=X, 1=Y); the poller stores
                // [leftX, leftY, rightX, rightY].
                0, 1 -> when (id) {
                    0, 1 -> holder.analog(index * 2 + id)
                    else -> 0
                }
                else -> 0
            }
            else -> 0
        }
    }
}

private class AudioSinkAdapter(
    private val output: AudioOutput,
    private val volume: () -> Float,
    private val suppress: () -> Boolean,
) : com.omilator.core.libretro.api.AudioSink {
    override fun onSamples(samples: ShortArray) {
        if (suppress()) return
        val vol = volume()
        val scaled = if (vol == 1.0f) samples else ShortArray(samples.size) { i ->
            (samples[i] * vol)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        output.write(scaled)
    }
}

