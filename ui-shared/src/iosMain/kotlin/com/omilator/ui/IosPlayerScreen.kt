@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omilator.core.audio.IosAudioOutput
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.createCoreController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.atomic
import kotlin.math.abs
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlin.math.hypot

@Composable
fun IosPlayerScreen(romPath: String, corePath: String, onExit: () -> Unit = {}) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var argbPixels by remember { mutableStateOf<IntArray?>(null) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }

    // rememberCoroutineScope auto-cancels when the composable leaves the
    // composition — without this the runFrame loop would leak across game
    // switches (previous code used remember { CoroutineScope(...) } which
    // never cancelled, leaving the core + audio engine running forever).
    val scope = rememberCoroutineScope()
    // A real app-private directory: the env handler refuses system/save
    // queries when the configured directory is empty, and BIOS-dependent
    // cores need a usable path.
    val libretroDir = remember {
        (platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory,
            platform.Foundation.NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: "")
            .let { "$it/../libretro" }
            .also { platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null) }
    }
    val controller = remember { createCoreController(libretroDir) }
    val audioOutput = remember { IosAudioOutput() }
    val buttonStates = remember { mutableStateMapOf<Int, Boolean>() }

    // Atomic input bits: the core thread must not read the Compose
    // SnapshotStateMap while pointer handlers mutate it.
    val inputBits = remember { List(16) { atomic(0) } }
    val press: (Int, Boolean) -> Unit = { id, pressed ->
        if (id in inputBits.indices) inputBits[id].value = if (pressed) 1 else 0
        if (pressed) buttonStates[id] = true else buttonStates.remove(id)
    }

    remember {
        scope.launch(Dispatchers.Default) {
            try {
                logI("Player", "loading core: $corePath")
                controller.loadCore(corePath)
                val avInfo = controller.loadGame(romPath)
                logI("Player", "loaded: ${avInfo.geometry.baseWidth}x${avInfo.geometry.baseHeight} @ ${avInfo.timing.fps}fps, audio ${avInfo.timing.sampleRate}Hz")
                frameW = avInfo.geometry.baseWidth.toInt()
                frameH = avInfo.geometry.baseHeight.toInt()
                audioOutput.configure(avInfo.timing.sampleRate, channels = 2)
                val latestFrame = arrayOfNulls<Framebuffer>(1)
                controller.attach(
                    video = { fb -> latestFrame[0] = fb },
                    audio = { samples -> audioOutput.write(samples) },
                    input = { _, _, _, id -> if (id in inputBits.indices) inputBits[id].value else 0 },
                )
                isLoading = false
                // Nanosecond deadlines: truncating to whole milliseconds
                // overclocked 59.94/60 Hz cores by ~2.5%.
                val intervalNanos = (1_000_000_000.0 / avInfo.timing.fps).toLong()
                var deadline = TimeSource.Monotonic.markNow()
                while (currentCoroutineContext().isActive) {
                    controller.runFrame()
                    latestFrame[0]?.let { fb ->
                        (fb.data as? ByteArray)?.let { bytes ->
                            convertToArgb(bytes, fb.width.toInt(), fb.height.toInt(), fb.pitch.toInt(), fb.format)?.let { converted ->
                                argbPixels = converted
                                frameW = fb.width.toInt()
                                frameH = fb.height.toInt()
                            }
                        }
                    }
                    deadline += intervalNanos.nanoseconds
                    val remaining = deadline - TimeSource.Monotonic.markNow()
                    if (remaining.isPositive()) delay(remaining.inWholeMilliseconds)
                    else deadline = TimeSource.Monotonic.markNow()
                }
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
                isLoading = false
            } finally {
                // NonCancellable: leaving the screen cancels the loop, and
                // the core must still be detached and unloaded.
                withContext(NonCancellable + Dispatchers.Default) {
                    runCatching { controller.detach() }
                    runCatching { controller.unloadGame() }
                    runCatching { controller.unloadCore() }
                    runCatching { audioOutput.release() }
                }
            }
        }
        true
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
                    // === SCREEN AREA ===
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
                                        image = createImageBitmap(pixels, frameW, frameH),
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(frameW, frameH),
                                        dstOffset = IntOffset(dx, dy),
                                        dstSize = IntSize(drawW, drawH),
                                    )
                                }
                            }
                        }
                    }

                    // === CONTROLLER AREA ===
                    ControllerBar(press)
                }

                // Exit button floating on top-left
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
private fun ControllerBar(press: (Int, Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF1A1A24))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // === D-PAD (single touch surface, computes direction from offset) ===
        DpadSection(press)

        // === START / SELECT ===
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton("SELECT", Color(0xFF48484A), press, 2)
            SmallButton("START", Color(0xFF48484A), press, 3)
        }

        // === A / B BUTTONS ===
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionButton("A", Color(0xFF5856D6), press, 8)
            ActionButton("B", Color(0xFFFF2D55), press, 0)
        }
    }
}

// === D-PAD ===
//
// One 132x132dp touch surface. Touch offset relative to center determines
// direction (UP/DOWN/LEFT/RIGHT). Sliding the thumb rolls the active
// direction without lifting. A small deadzone around the center rejects
// jitter. Visuals are a non-interactive 3x3 grid that lights up the active
// quadrant by reading the same `activeDirection` state.

private const val DPAD_UP = 4
private const val DPAD_DOWN = 5
private const val DPAD_LEFT = 6
private const val DPAD_RIGHT = 7

@Composable
private fun DpadSection(press: (Int, Boolean) -> Unit) {
    var activeDirection by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = Modifier
            .size(132.dp)
            .pointerInput(Unit) {
                // Deadzone in px — pointer inside this radius from center
                // registers no direction. ~10dp keeps the touch target crisp
                // without making the center feel unresponsive.
                val deadzonePx = 10.dp.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    var current = directionFromOffset(down.position, cx, cy, deadzonePx)
                    applyDirection(current, null, press)
                    activeDirection = current

                    // Track this pointer until release. Exit-on-leave handled
                    // by checking bounds, so the button can't get stuck if
                    // the finger slides off the D-pad entirely.
                    var pointerInside = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: continue
                        change.consume()

                        if (!change.pressed) {
                            applyDirection(null, current, press)
                            activeDirection = null
                            break
                        }

                        val inside = isInside(change.position, size)
                        val newDir = if (inside) {
                            directionFromOffset(change.position, cx, cy, deadzonePx)
                        } else null
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
    // Pick dominant axis. Corners count as the axis they're closer to.
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

// === ACTION BUTTONS (A/B) ===

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
            .pressable(buttonId) { id, p -> press(id, p); pressed = p },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
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
            .pressable(buttonId) { id, p -> press(id, p); pressed = p },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (pressed) 0.9f else 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

// === TOUCH HANDLING ===
//
// Multi-touch fix: each button gets its own pointerInput. We track the
// specific pointer (by id) that did the initial down, and only release when
// *that* pointer lifts OR leaves the button bounds. Previous code used
// detectTapGestures.onPress + tryAwaitRelease which blocks until ALL
// pointers are up — broke multi-touch (hold A, tap B, A stayed "pressed").
//
// Exit-on-leave prevents the sticky-button bug where a finger slides off
// the button without lifting — without this, the button would stay pressed
// because pointerInput stops receiving events for a pointer that's left
// the hit-test bounds.

private fun Modifier.pressable(
    buttonId: Int,
    press: (Int, Boolean) -> Unit,
): Modifier = this.pointerInput(buttonId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        press(buttonId, true)

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            change.consume()

            if (!change.pressed || !isInside(change.position, size)) {
                press(buttonId, false)
                break
            }
        }
    }
}

private fun isInside(position: Offset, size: IntSize): Boolean {
    return position.x >= 0f && position.y >= 0f &&
        position.x <= size.width && position.y <= size.height
}

private fun applyDirection(
    newDir: Int?,
    oldDir: Int?,
    press: (Int, Boolean) -> Unit,
) {
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

private fun createImageBitmap(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val v = argb[i]
        bytes[i * 4] = (v and 0xFF).toByte()
        bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
    }
    return org.jetbrains.skia.Image.makeRaster(
        org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
        bytes,
        width * 4,
    ).toComposeImageBitmap()
}
