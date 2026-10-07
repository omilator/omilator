package com.omilator.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omilator.core.audio.AudioOutput
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.api.CoreController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.atomic
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlin.math.hypot

private const val DPAD_UP = 4
private const val DPAD_DOWN = 5
private const val DPAD_LEFT = 6
private const val DPAD_RIGHT = 7

/**
 * Battery-backed SRAM persistence: restore after loadGame, flush before
 * unloadGame. Implemented by the platform (Android: java.io under the
 * app's saves directory with a stable per-ROM identity).
 */
interface SramStore {
    fun read(): ByteArray?
    fun write(data: ByteArray)

    /** Move the current save aside as a backup (never overwriting an
     *  existing backup); called when the persisted save's size no longer
     *  matches the core's SRAM block, so the old bytes survive while a new
     *  save starts. Returns false when the platform cannot back up — the
     *  caller then keeps the flush gate closed rather than clobber the
     *  only durable copy. */
    fun backupExisting(): Boolean = false
}

/**
 * Joypad-only touch input source. The poll callback's parameters are
 * (port, device, index, id): answering by id alone reported the digital
 * B/Y touch buttons (ids 0/1) as analog stick positions whenever a core
 * polled RETRO_DEVICE_ANALOG. Only joypad queries on port 0 carry real
 * state; everything else is honestly neutral.
 */
internal class TouchInputSource(
    private val bits: List<kotlinx.atomicfu.AtomicInt>,
) : com.omilator.core.libretro.api.InputSource {
    override fun poll(port: Int, device: com.omilator.core.libretro.api.InputDevice, index: Int, id: Int): Int =
        if (port == 0 && device == com.omilator.core.libretro.api.InputDevice.JOYPAD && id in bits.indices) {
            bits[id].value
        } else 0
}

/**
 * Shared mobile player screen for iOS + Android. Renders the emulator
 * framebuffer to a Compose Canvas and overlays a handheld-style touch
 * controller (D-pad + A/B + Start/Select). Uses Compose Multiplatform
 * so the actual UI code is identical on both platforms.
 *
 * Platform-specific bits are passed in:
 *  - [coreController] — created via expect/actual `createCoreController`
 *    (dlopen+cinterop on iOS, JNI on Android).
 *  - [audioOutput] — created via expect/actual `createAudioOutputFactory`
 *    (AVAudioEngine on iOS, AudioTrack on Android).
 *  - [sramStore] — optional battery-save persistence; without it,
 *    RETRO_MEMORY_SAVE_RAM content is lost when the session ends.
 *
 * Multi-touch: per-pointer tracking via `awaitEachGesture`. The D-pad is
 * a single touch surface that computes direction from offset (10dp deadzone).
 * Action buttons are independent pointerInput zones — hold A + tap B works
 * correctly without one depending on the other's release.
 */
@Composable
fun MobilePlayerScreen(
    romPath: String,
    corePath: String,
    coreController: CoreController,
    audioOutput: AudioOutput,
    sramStore: SramStore? = null,
    onExit: () -> Unit = {},
) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var argbPixels by remember { mutableStateOf<IntArray?>(null) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var sramNotice by remember { mutableStateOf<String?>(null) }

    val buttonStates = remember { mutableStateMapOf<Int, Boolean>() }

    // Input as atomics: the core thread reads these from its input callback.
    // The old shape read the Compose SnapshotStateMap from that thread while
    // pointer handlers on the UI thread mutated it.
    val inputBits = remember { List(16) { atomic(0) } }
    val press: (Int, Boolean) -> Unit = { id, pressed ->
        if (id in inputBits.indices) inputBits[id].value = if (pressed) 1 else 0
        if (pressed) buttonStates[id] = true else buttonStates.remove(id)
    }

    // Keyed to the session inputs: a changed ROM/core in the same
    // composition cancels the old loop instead of leaking it.
    LaunchedEffect(romPath, corePath) {
        // True once the core's SRAM reflects this game's battery save (a
        // restored one, or a fresh one when no save exists yet). The
        // finally-block flush must be gated on it: after a failed loadGame
        // (or a restore that silently did not take) the SRAM block holds
        // fresh core RAM, and writing that would destroy the only durable
        // battery save.
        var sramAuthoritative = false
        withContext(Dispatchers.Default) {
            try {
                coreController.loadCore(corePath)
                // Mid-run SET_SYSTEM_AV_INFO: geometry is tracked per frame
                // below, but timing must be forwarded explicitly — the loop
                // paces and the audio output are configured once otherwise,
                // and an fps/sample-rate switch would desync them until the
                // screen is reopened. Fires on the core thread (inside
                // runFrame), the same thread that calls write(), so the
                // reconfigure never races it.
                //
                // Registered BEFORE loadGame (cores can report a new mode as
                // early as content load — desktop registers before loadGame
                // for the same reason), and guarded by the same epsilons
                // desktop's PlayerEngine uses: configure() rebuilds the
                // whole platform audio stack (AVAudioEngine / AudioTrack),
                // and float-noise repeats (59.940 vs 59.9401) or cores that
                // re-assert the command must not trigger that churn.
                val intervalNanos = atomic((1_000_000_000.0 / 60.0).toLong())
                var appliedFps = 0f
                var appliedSampleRate = 0.0
                fun applyTiming(info: com.omilator.core.libretro.api.AvInfo) {
                    if (AvTimingGuard.fpsChanged(appliedFps, info.timing.fps)) {
                        appliedFps = info.timing.fps
                        intervalNanos.value = (1_000_000_000.0 / info.timing.fps).toLong()
                    }
                    if (AvTimingGuard.sampleRateChanged(appliedSampleRate, info.timing.sampleRate)) {
                        appliedSampleRate = info.timing.sampleRate
                        runCatching { audioOutput.configure(info.timing.sampleRate, channels = 2) }
                    }
                }
                coreController.setSystemAvInfoListener(::applyTiming)
                val avInfo = coreController.loadGame(romPath)
                // loadGame's return value is the core's CURRENT av info —
                // apply it through the same guarded path (the first apply
                // always passes the epsilon, so load-time values stick; a
                // mid-load report that already applied them is absorbed).
                applyTiming(avInfo)
                val restore = restoreMobileSram(
                    sramStore = sramStore,
                    readSaveRam = coreController::readSaveRam,
                    writeSaveRam = coreController::writeSaveRam,
                )
                sramAuthoritative = restore.authoritative
                sramNotice = restore.notice
                frameW = avInfo.geometry.baseWidth.toInt()
                frameH = avInfo.geometry.baseHeight.toInt()
                val latestFrame = arrayOfNulls<Framebuffer>(1)
                coreController.attach(
                    video = { fb -> latestFrame[0] = fb },
                    audio = { samples -> audioOutput.write(samples) },
                    input = TouchInputSource(inputBits),
                )
                isLoading = false
                // Nanosecond deadlines: (1000/fps).toLong() truncated 60 Hz
                // to 16 ms and overdrives the core by ~2.5%. Re-read from
                // the atomic every iteration so a mid-run timing change
                // (see the av-info listener above) takes effect live.
                var deadline = TimeSource.Monotonic.markNow()
                while (currentCoroutineContext().isActive) {
                    coreController.runFrame()
                    latestFrame[0]?.let { fb ->
                        (fb.data as? ByteArray)?.let { bytes ->
                            convertToArgb(bytes, fb.width.toInt(), fb.height.toInt(), fb.pitch.toInt(), fb.format)?.let { converted ->
                                argbPixels = converted
                                frameW = fb.width.toInt()
                                frameH = fb.height.toInt()
                            }
                        }
                    }
                    deadline += intervalNanos.value.nanoseconds
                    val remaining = deadline - TimeSource.Monotonic.markNow()
                    if (remaining > Duration.ZERO) delay(remaining.inWholeMilliseconds)
                    else deadline = TimeSource.Monotonic.markNow()
                }
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
                isLoading = false
            } finally {
                // NonCancellable: leaving the screen cancels this coroutine,
                // and the core MUST still be unloaded rather than left
                // running with dangling callbacks.
                withContext(NonCancellable + Dispatchers.Default) {
                    runCatching { coreController.setSystemAvInfoListener(null) }
                    // SRAM flush precedes unload: retro_unload_game is where
                    // some cores hand the final battery block back.
                    runCatching {
                        sramStore?.let { store ->
                            if (sramAuthoritative) {
                                coreController.readSaveRam().takeIf { it.isNotEmpty() }?.let(store::write)
                            }
                        }
                    }
                    runCatching { coreController.detach() }
                    runCatching { coreController.unloadGame() }
                    runCatching { coreController.unloadCore() }
                    runCatching { audioOutput.release() }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D12))) {
        when {
            error != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, color = Color(0xFFFF6B6B))
                }
                ExitButton(onExit)
            }
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading...", color = Color.White)
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        argbPixels?.let { pixels ->
                            if (frameW > 0 && frameH > 0) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                ) {
                                    val scale = minOf(size.width / frameW, size.height / frameH)
                                    val drawW = (frameW * scale).toInt()
                                    val drawH = (frameH * scale).toInt()
                                    val dx = ((size.width - drawW) / 2f).toInt()
                                    val dy = ((size.height - drawH) / 2f).toInt()
                                    drawImage(
                                        image = createImageBitmapFromArgb(pixels, frameW, frameH),
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(frameW, frameH),
                                        dstOffset = IntOffset(dx, dy),
                                        dstSize = IntSize(drawW, drawH),
                                    )
                                }
                            }
                        }
                        // Battery-save condition (size-mismatch migration,
                        // unrestorable save): surfaced instead of a
                        // console-only print that saving is off this session.
                        sramNotice?.let { notice ->
                            Text(
                                notice,
                                color = Color(0xFFFFD166),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
                            )
                        }
                    }
                    ControllerBar(buttonStates, press)
                }
                ExitButton(onExit)
            }
        }
    }
}

@Composable
private fun ExitButton(onExit: () -> Unit) {
    IconButton(
        onClick = onExit,
        modifier = Modifier
            .padding(8.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Icon(Icons.Rounded.Close, contentDescription = "Exit", modifier = Modifier.size(20.dp))
    }
}

// === CONTROLLER ===

@Composable
private fun ControllerBar(buttonStates: MutableMap<Int, Boolean>, press: (Int, Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF1A1A24))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DpadSection(press)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton("SELECT", Color(0xFF48484A), press, 2)
            SmallButton("START", Color(0xFF48484A), press, 3)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionButton("A", Color(0xFF5856D6), press, 8)
            ActionButton("B", Color(0xFFFF2D55), press, 0)
        }
    }
}

@Composable
private fun DpadSection(press: (Int, Boolean) -> Unit) {
    var activeDirection by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = Modifier
            .size(132.dp)
            .pointerInput(Unit) {
                val deadzonePx = 10.dp.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var current = directionFromOffset(down.position, cx, cy, deadzonePx)
                    applyDirection(current, null, press)
                    activeDirection = current
                    var pointerInside = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        change.consume()
                        if (!change.pressed) {
                            applyDirection(null, current, press)
                            activeDirection = null
                            break
                        }
                        val inside = isInside(change.position, size)
                        val newDir = if (inside) directionFromOffset(change.position, cx, cy, deadzonePx) else null
                        if (inside != pointerInside || newDir != current) {
                            pointerInside = inside
                            if (newDir != current) {
                                applyDirection(newDir, current, press)
                                current = newDir
                                activeDirection = newDir
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DpadCell("\u25B2", DPAD_UP, activeDirection)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DpadCell("\u25C0", DPAD_LEFT, activeDirection)
                Box(modifier = Modifier.size(44.dp).background(Color(0xFF2A2A32)))
                DpadCell("\u25B6", DPAD_RIGHT, activeDirection)
            }
            DpadCell("\u25BC", DPAD_DOWN, activeDirection)
        }
    }
}

private fun directionFromOffset(position: Offset, cx: Float, cy: Float, deadzonePx: Float): Int? {
    val dx = position.x - cx
    val dy = position.y - cy
    if (hypot(dx, dy) < deadzonePx) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) DPAD_RIGHT else DPAD_LEFT
    } else {
        if (dy > 0) DPAD_DOWN else DPAD_UP
    }
}

@Composable
private fun DpadCell(arrow: String, dir: Int, activeDirection: Int?) {
    val isActive = activeDirection == dir
    val baseColor = if (isActive) Color(0xFF5A5A6A) else Color(0xFF3A3A44)
    val scale by animateFloatAsState(
        targetValue = if (isActive) 0.94f else 1f,
        animationSpec = tween(durationMillis = 60),
        label = "dpad-$dir",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = if (isActive) 1.dp else 4.dp,
                shape = RoundedCornerShape(6.dp),
            )
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(brightness = 1.2f),
                        baseColor,
                        baseColor.copy(brightness = 0.8f),
                    ),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(arrow, color = Color.White.copy(alpha = if (isActive) 1f else 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    press: (Int, Boolean) -> Unit,
    buttonId: Int,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 60),
        label = "action-$buttonId",
    )

    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = if (pressed) 2.dp else 8.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        color.copy(brightness = if (pressed) 1.0f else 1.3f),
                        color,
                        color.copy(brightness = if (pressed) 0.6f else 0.7f),
                    ),
                )
            )
            .pressable(buttonId) { p -> pressed = p; press(buttonId, p) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallButton(
    label: String,
    color: Color,
    press: (Int, Boolean) -> Unit,
    buttonId: Int,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 60),
        label = "small-$buttonId",
    )

    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = if (pressed) 1.dp else 3.dp,
                shape = RoundedCornerShape(14.dp),
            )
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        color.copy(brightness = if (pressed) 1.0f else 1.2f),
                        color,
                    ),
                )
            )
            .pressable(buttonId) { p -> pressed = p; press(buttonId, p) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (pressed) 0.9f else 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

// === TOUCH HANDLING ===

private fun Modifier.pressable(
    buttonId: Int,
    onChange: (Boolean) -> Unit,
): Modifier = this.pointerInput(buttonId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onChange(true)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            change.consume()
            if (!change.pressed || !isInside(change.position, size)) {
                onChange(false)
                break
            }
        }
    }
}

private fun isInside(position: Offset, size: IntSize): Boolean {
    return position.x >= 0f && position.y >= 0f &&
        position.x <= size.width && position.y <= size.height
}

private fun applyDirection(newDir: Int?, oldDir: Int?, press: (Int, Boolean) -> Unit) {
    if (oldDir != null && oldDir != newDir) press(oldDir, false)
    if (newDir != null && newDir != oldDir) press(newDir, true)
}

// === HELPERS ===

private fun Color.copy(brightness: Float): Color {
    return Color(
        red = (red * brightness).coerceIn(0f, 1f),
        green = (green * brightness).coerceIn(0f, 1f),
        blue = (blue * brightness).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun convertToArgb(bytes: ByteArray, width: Int, height: Int, pitch: Int, format: PixelFormat): IntArray? {
    if (width <= 0 || height <= 0) return null
    val argb = IntArray(width * height)
    val bpp = if (format == PixelFormat.XRGB8888) 4 else 2
    for (y in 0 until height) {
        val rowOffset = y * pitch
        for (x in 0 until width) {
            val src = rowOffset + x * bpp
            if (src + bpp > bytes.size) continue
            argb[y * width + x] = when (format) {
                PixelFormat.ORGB1555 -> {
                    val lo = bytes[src].toInt() and 0xFF
                    val hi = bytes[src + 1].toInt() and 0xFF
                    val packed = lo or (hi shl 8)
                    val r5 = (packed shr 10) and 0x1F
                    val g5 = (packed shr 5) and 0x1F
                    val b5 = packed and 0x1F
                    (0xFF shl 24) or ((r5 shl 3 or (r5 shr 2)) shl 16) or ((g5 shl 3 or (g5 shr 2)) shl 8) or (b5 shl 3 or (b5 shr 2))
                }
                PixelFormat.RGB565 -> {
                    val lo = bytes[src].toInt() and 0xFF
                    val hi = bytes[src + 1].toInt() and 0xFF
                    val packed = lo or (hi shl 8)
                    val r5 = (packed shr 11) and 0x1F
                    val g6 = (packed shr 5) and 0x3F
                    val val5 = packed and 0x1F
                    (0xFF shl 24) or ((r5 shl 3 or (r5 shr 2)) shl 16) or ((g6 shl 2 or (g6 shr 4)) shl 8) or (val5 shl 3 or (val5 shr 2))
                }
                PixelFormat.XRGB8888 -> {
                    val b = bytes[src].toInt() and 0xFF
                    val g = bytes[src + 1].toInt() and 0xFF
                    val r = bytes[src + 2].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
    return argb
}
