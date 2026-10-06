package com.omilator.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.omilator.core.audio.createAudioOutputFactory
import java.io.File

@Composable
fun PlayerScreen(
    gameId: String,
    onClose: () -> Unit,
    /** Explicit system for this ROM when the filename alone cannot say —
     *  downloaded server ROMs are extensionless `.rom` cache files, so the
     *  platform must come from server metadata carried by the caller. */
    systemOverride: com.omilator.data.library.GameSystem? = null,
) {
    val corePath = remember(gameId, systemOverride) {
        resolveCorePath(gameId, systemOverride)
    }
    if (corePath == null) {
        NoCoreMappingScreen(romPath = gameId, onClose = onClose)
        return
    }
    val audioOutput = remember { createAudioOutputFactory().create() }
    val engine = remember(gameId) {
        PlayerEngine(
            corePath = corePath,
            romPath = gameId,
            audioOutput = audioOutput,
        )
    }
    val state by engine.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(gameId) { engine.start() }

    DisposableEffect(gameId) {
        onDispose {
            engine.stop()
            audioOutput.release()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    var latestBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var fastForward by remember { mutableStateOf(false) }
    var isRewinding by remember { mutableStateOf(false) }
    var showCheatDialog by remember { mutableStateOf(false) }
    var cheatCode by remember { mutableStateOf("") }
    var showOptionsDialog by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(0) } // 0=aspect, 1=stretch, 2=integer
    var debugFrameCount by remember { mutableStateOf(0) }
    var lastEmittedCount by remember { mutableStateOf(0) }

    // Frame pump: pull latest framebuffer from the engine on every UI frame.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { _ ->
                if (isRewinding) {
                    engine.rewindStep()
                }
                val image = engine.renderFrameIfAvailable()
                if (image != null) {
                    latestBitmap = image.toComposeImageBitmap()
                }
                val emittedNow = 0
                if (emittedNow != lastEmittedCount) {
                    lastEmittedCount = emittedNow
                }
                debugFrameCount = emittedNow
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp && event.type != KeyEventType.KeyDown) {
                    return@onKeyEvent false
                }
                val keyCode = event.key.nativeKeyCode
                if (event.type == KeyEventType.KeyUp && keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                    onClose()
                    return@onKeyEvent true
                }
                // Rewind: hold Backspace to step backwards
                if (keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    isRewinding = event.type == KeyEventType.KeyDown
                    return@onKeyEvent true
                }
                // Cheats: press C to open dialog
                if (event.type == KeyEventType.KeyUp && keyCode == java.awt.event.KeyEvent.VK_C) {
                    showCheatDialog = true
                    return@onKeyEvent true
                }
                // Core options: press O to open dialog
                if (event.type == KeyEventType.KeyUp && keyCode == java.awt.event.KeyEvent.VK_O) {
                    showOptionsDialog = true
                    return@onKeyEvent true
                }
                // Run-ahead toggle: press R
                if (event.type == KeyEventType.KeyUp && keyCode == java.awt.event.KeyEvent.VK_R) {
                    engine.toggleRunAhead()
                    return@onKeyEvent true
                }
                // Video scaling: press S to cycle modes
                // Cycle scaling: press V.
                // (Was S, which is the emulated X button — its KeyUp was
                // consumed here and X stayed pressed forever.)
                if (event.type == KeyEventType.KeyUp && keyCode == java.awt.event.KeyEvent.VK_V) {
                    scaleMode = (scaleMode + 1) % 3
                    return@onKeyEvent true
                }
                // Volume: + / - keys
                if (event.type == KeyEventType.KeyUp) {
                    when (keyCode) {
                        java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.VK_PLUS -> {
                            engine.setVolume(engine.getVolume() + 0.1f)
                            return@onKeyEvent true
                        }
                        java.awt.event.KeyEvent.VK_MINUS -> {
                            engine.setVolume(engine.getVolume() - 0.1f)
                            return@onKeyEvent true
                        }
                    }
                }
                val button = KeyboardMapping.buttonFor(keyCode)
                if (button != null) {
                    when (event.type) {
                        KeyEventType.KeyDown -> engine.pressButton(button)
                        KeyEventType.KeyUp -> engine.releaseButton(button)
                    }
                    return@onKeyEvent true
                }
                if (event.type == KeyEventType.KeyUp) {
                    when (keyCode) {
                        // Fast forward toggle (Tab)
                        java.awt.event.KeyEvent.VK_TAB -> {
                            fastForward = !fastForward
                            engine.setSpeedMultiplier(if (fastForward) 3.0f else 1.0f)
                            true
                        }
                        // Save state slots: F1-F5
                        java.awt.event.KeyEvent.VK_F1 -> { engine.saveState(engine.stateFile(1).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F2 -> { engine.saveState(engine.stateFile(2).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F3 -> { engine.saveState(engine.stateFile(3).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F4 -> { engine.saveState(engine.stateFile(4).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F5 -> { engine.saveState(engine.stateFile(5).absolutePath); true }
                        // Load state slots: Shift+F1-F5
                        java.awt.event.KeyEvent.VK_F6 -> { engine.loadState(engine.stateFile(1).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F7 -> { engine.loadState(engine.stateFile(2).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F8 -> { engine.loadState(engine.stateFile(3).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F9 -> { engine.loadState(engine.stateFile(4).absolutePath); true }
                        java.awt.event.KeyEvent.VK_F10 -> { engine.loadState(engine.stateFile(5).absolutePath); true }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.error != null -> ErrorOverlay(state.error!!, corePath, gameId)
            state.isLoading -> LoadingOverlay(corePath, gameId)
            latestBitmap == null -> WaitingForFramesOverlay(corePath, gameId, debugFrameCount)
            else -> EmulatedSurface(latestBitmap!!, state.geometry?.aspectRatio ?: 1.5f, scaleMode)
        }

        DebugOverlay(
            corePath = corePath,
            romPath = gameId,
            geometry = state.geometry,
            framesEmitted = 0,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Cheat dialog
        if (showCheatDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCheatDialog = false },
                title = { Text("Enter Cheat Code") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = cheatCode,
                        onValueChange = { cheatCode = it },
                        placeholder = { Text("e.g. 010138CD or GAME GENIE code") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        if (cheatCode.isNotBlank()) {
                            engine.applyCheat(cheatCode)
                        }
                        showCheatDialog = false
                    }) { Text("Apply") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showCheatDialog = false }) { Text("Cancel") }
                },
            )
        }

        // Fast forward indicator
        if (fastForward) {
            Text(
                ">> ${engine.getSpeedMultiplier()}x",
                color = Color(0x80FFFFFF),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }

        // Core options dialog
        if (showOptionsDialog) {
            val options = remember { engine.getCoreOptions() }
            val selections = remember { mutableStateMapOf<String, String>() }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showOptionsDialog = false },
                title = { Text("Emulator Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (options.isEmpty()) {
                            Text("This core has no configurable options.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            options.forEach { option ->
                                Column {
                                    Text(option.description, style = MaterialTheme.typography.labelLarge)
                                    option.values.forEach { v ->
                                        val current = selections[option.key] ?: option.default
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                selections[option.key] = v.value
                                                engine.setOptionValue(option.key, v.value)
                                            },
                                        ) {
                                            Text(
                                                v.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (v.value == current) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showOptionsDialog = false }) { Text("Done") }
                },
            )
        }
    }
}

/** Unsupported extension: no silent mGBA fallback — scanner and launcher
 *  resolve through the same GameSystem table, so what the library refuses
 *  to scan must not launch here either. */
@Composable
private fun NoCoreMappingScreen(romPath: String, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    event.key.nativeKeyCode == java.awt.event.KeyEvent.VK_ESCAPE
                ) {
                    onClose()
                    true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("No core mapping for ROM", color = MaterialTheme.colorScheme.error)
            Text(romPath, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
            Text("Esc to go back", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun EmulatedSurface(bitmap: ImageBitmap, aspectRatio: Float, scaleMode: Int = 0) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Destination comes from the core's display aspect ratio (see
        // computeViewport): scaling by bitmap pixels alone ignored it, and
        // the old "stretch" mode used maxOf(scaleX, scaleY), which crops.
        val dst = computeViewport(
            canvasWidth = size.width,
            canvasHeight = size.height,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            displayAspectRatio = aspectRatio,
            scaleMode = scaleMode,
        )
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(dst.offset.x.toInt(), dst.offset.y.toInt()),
            dstSize = IntSize(dst.size.width.toInt(), dst.size.height.toInt()),
        )
    }
}

@Composable
private fun LoadingOverlay(corePath: String, romPath: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Loading...", color = Color.White)
        Text(romPath, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
        Text(corePath, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WaitingForFramesOverlay(corePath: String, romPath: String, framesSoFar: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Waiting for first frame...", color = Color.White)
        Text("Frames emitted by core so far: $framesSoFar", color = Color.LightGray)
        Text(romPath, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorOverlay(message: String, corePath: String, romPath: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text("Error loading game", color = MaterialTheme.colorScheme.error)
        Text(message, color = Color.White, modifier = Modifier.padding(top = 8.dp))
        Text("Core: $corePath", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Text("ROM:  $romPath", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Text("Esc to go back", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun DebugOverlay(
    corePath: String,
    romPath: String,
    geometry: com.omilator.core.libretro.api.Geometry?,
    framesEmitted: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(8.dp),
    ) {
        Text(
            buildString {
                appendLine("core: ${File(corePath).name}")
                append("emitted: $framesEmitted")
                geometry?.let {
                    appendLine()
                    append("geom: ${it.baseWidth}x${it.baseHeight}")
                }
            },
            color = Color(0x80FFFFFF),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
