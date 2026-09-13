# omilator - Audit (2026-09-12)

> STATUS REGISTER (2026-09-12):
> Pass 1 (commit 722c714): #1 #2 #3 #4 #5 #7 #8 #19(desktop) #22 #23 #25 #26 #27 #28 #33 #35
> Pass 2 (this commit): #6 #9 #10 #11 #12 #13 #14(desktop) #15 #16 #17 #18 #19(iOS) #20 #21 #24 #29 #30 #31 #32 #34 #36 #37 #38
> ALL 38 findings implemented. Verified: compile on desktop, android (Kotlin + NDK native), iosArm64, iosSimulatorArm64.
> Known limits left deliberately: #14 SRAM is desktop-only (Android/iOS controllers keep the default no-op until
> their memory paths exist); #15 SAF ROM playback materializes the file into cacheDir (no streaming); #10 IosPlayerScreen
> was fixed in place rather than removed (the common-screen unification is a product decision).
> Verification notes that changed the audit's own fixes: #1's proposed fix WRITES bottom_left_origin at 34
> (core-owned input — the frontend must READ it); #2's constant table omits the 0x10000 EXPERIMENTAL bit;
> #32 uses TimeSource deadlines rather than plain ns delay.

## Summary

| # | Severity | File | Issue |
|---:|:---:|---|---|
| 1 | Critical | `core-libretro/.../jvm/gl/HwRenderBridge.kt` | `retro_hw_render_callback` is decoded/written using the wrong ABI offsets and wrong HW-context enum values. |
| 2 | Critical | `core-libretro/.../jvm/LibretroLayouts.kt` | Many `RETRO_ENVIRONMENT_*` command IDs are incorrect, causing callbacks to interpret/write incompatible native structures. |
| 3 | Critical | `core-libretro/.../jvm/LibretroFfm.kt` | `parseCoreOptions()` treats an inline values array as pointers and uses a 40-byte struct stride, causing out-of-bounds native reads. |
| 4 | Critical | `core-libretro/.../jvm/LibretroFfm.kt` | `parseInputDescriptors()` uses the wrong terminator and can walk past the descriptor array into arbitrary native memory. |
| 5 | Critical | `ui-shared/.../desktopMain/.../PlayerEngine.kt` | `stop()` cancels the cleanup scope immediately and can race core unload/dlclose against an in-flight `retro_run`. |
| 6 | Critical | `core-libretro/.../iosMain/.../NativeCoreController.kt` | iOS `retro_game_info` initialization/path copying writes into temporary `ByteArray`s instead of native memory and leaks all native allocations. |
| 7 | High | `ui-shared/.../desktopMain/.../PlayerEngine.kt` | OpenGL/libretro execution is not thread-confined; the coroutine can run the core on a thread different from the one that owns the GL context. |
| 8 | High | `ui-shared/.../desktopMain/.../PlayerEngine.kt` | Save/load/rewind/cheat/options calls can execute concurrently with `retro_run`. |
| 9 | High | `ui-shared/.../commonMain/.../MobilePlayerScreen.kt` | Android player reads/writes Compose snapshot state from a background thread and never unloads the core on disposal. |
| 10 | High | `ui-shared/.../iosMain/.../IosPlayerScreen.kt` | iOS player duplicates the background Compose-state race and also exits without unloading/detaching the core. |
| 11 | High | `core-libretro/.../androidMain/.../JniCoreController.kt` | Android returns hard-coded GBA AV timing for every core, producing wrong audio sample rates and initial geometry. |
| 12 | High | `core-libretro/.../iosMain/.../NativeCoreController.kt` | iOS returns hard-coded GBA AV timing and always loads the entire ROM twice into RAM. |
| 13 | High | `core-audio/.../iosMain/.../IosAudioOutput.kt` | `pendingBuffers` is raced between core/audio callback threads and `flush()` leaves the backpressure counter stale. |
| 14 | High | `core-libretro` controllers / `PlayerEngine.kt` | SRAM/battery memory is stubbed out and never flushed before game/core unload. |
| 15 | High | `app-android/.../MainActivity.kt` | SAF `content://` directory URIs are persisted but passed to a scanner that only understands `java.io.File` paths. |
| 16 | High | `ui-shared/.../iosMain/.../IosFilePicker.kt` | Security-scoped document URLs are reduced to raw paths without retaining access or copying them into the sandbox. |
| 17 | High | `ui-shared/.../LibraryViewModel.kt`, `SettingsScreen.kt` | Settings persistence reconstructs partial `AppSettings`, resetting unrelated fields; independent async saves can overwrite each other. |
| 18 | High | `core-input/build.gradle.kts`, `core-libretro/build.gradle.kts`, `CoreDownloader.kt` | The supposedly cross-platform desktop build hard-codes macOS ARM64 LWJGL natives and macOS `.dylib` downloads. |
| 19 | High | `core-libretro/.../NativeCoreController.kt`, `LibretroFfm.kt` | Media/input callbacks are installed after `retro_init`, so cores may execute initialization before required callbacks exist. |
| 20 | High | `core-libretro/.../iosMain/.../NativeCoreController.kt` | 0-argument C callbacks are implemented as functions taking a dummy `Int`, creating an ABI-signature mismatch. |
| 21 | Medium | `data-library/.../Game.kt` | Extension-only system detection silently picks the first of many colliding formats and contradicts its own ISO comment. |
| 22 | Medium | `ui-shared/.../PlayerScreen.desktop.kt` | `S` is both the X joypad button and the scale-mode shortcut; its KeyUp is consumed, leaving X permanently pressed. |
| 23 | Medium | `core-input/.../GamepadPoller.kt` | Disconnects leave stale buttons pressed, joystick `LAST` is skipped, analog has no deadzone, and PlayerEngine discards analog values. |
| 24 | Medium | `core-audio/.../iosMain/.../PlatformAudioOutputFactory.kt` | The iOS `actual` audio factory still returns a silent stub instead of `IosAudioOutput`. |
| 25 | Medium | `core-libretro/.../desktopMain/.../FfmCoreController.kt` | The shared FFM `Arena` is never closed, so loaded native libraries/upcall stubs remain live after `unloadCore()`. |
| 26 | Medium | `core-libretro/.../jvm/LibretroLayouts.kt` | `retro_game_geometry` has padding inserted before `aspect_ratio`, so aspect ratio is read from the wrong native offset. |
| 27 | Medium | `core-libretro/api/Types.kt` / platform controllers | The frontend has no 0RGB1555 pixel format and defaults unset cores to XRGB8888, misinterpreting 16-bit video frames. |
| 28 | Medium | `ui-shared/.../desktopMain/.../PlayerEngine.kt` | Run-ahead emits speculative audio and the volume setting is never applied to samples. |
| 29 | Medium | `data-settings` persistence | Settings writes truncate the live file in place; interrupted writes become corrupt JSON that silently resets to defaults. |
| 30 | Medium | `app-android/MainActivity.kt`, `app-desktop/Main.kt`, `RootViewController.kt` | `GlobalScope`/unowned `CoroutineScope`s outlive their Activity/window/controller and can mutate dead UI state. |
| 31 | Medium | `data-library/.../iosMain/.../IosLibraryScanner.kt` | iOS scanner ignores the requested directory and scans only the top level of Documents. |
| 32 | Medium | `MobilePlayerScreen.kt`, `IosPlayerScreen.kt` | Mobile frame pacing truncates fractional frame intervals to whole milliseconds, overclocking common 59.94/60 Hz cores. |
| 33 | Medium | `core-libretro/.../jvm/LibretroFfm.kt` | Core option changes are never reported through `GET_VARIABLE_UPDATE`, so many cores will not apply runtime changes. |
| 34 | Medium | `core-input/.../InputManager.kt` | `StateFlow` contains a mutable object mutated in place, suppressing emissions; unchecked setters can also throw on invalid IDs. |
| 35 | Medium | `core-libretro/.../jvm/gl/GlContext.kt` | OpenGL readback is bottom-up but is forwarded directly as top-down framebuffer data. |
| 36 | Low | `iosApp/iOSApp.swift` | Deep-link handling removes the leading `/` from absolute ROM paths. |
| 37 | Low | `data-saves/.../SaveState.kt` | Save-state listing derives slots from arbitrary listing order and fabricates every creation time as “now”. |
| 38 | Low | `ui-shared/.../desktopMain/.../CoverArt.desktop.kt` | The configured TheGamesDB API key is never supplied to `CoverArtService`, so the setting has no effect. |

## Findings

### 1. [CRITICAL] Desktop HW-render callback uses the wrong native ABI layout

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/HwRenderBridge.kt` (`handleRequest`)
- Problem: The desktop implementation reads version fields at offsets 4/8, reads `context_reset` at 16, and writes frontend callbacks at 32/40. The iOS implementation in the same source tree documents the actual 64-bit layout with `context_reset` at 8, frontend callbacks at 16/24, flags at 32+, and `context_destroy` at 48. Desktop also defines Vulkan as `7` while the iOS binding uses Vulkan `6`. The current writes overwrite flags/version data and part of the core's `context_destroy` pointer, which can directly crash native code. fileciteturn2file0L5-L42 fileciteturn7file4L1043-L1056
- Fix:

```diff
- val sized = data.reinterpret(56L)
+ val sized = data.reinterpret(64L)

  val ctxType = sized.get(ValueLayout.JAVA_INT, 0)
- val major = sized.get(ValueLayout.JAVA_INT, 4)
- val minor = sized.get(ValueLayout.JAVA_INT, 8)
+ val resetSeg = sized.get(ValueLayout.ADDRESS, 8)
+ val major = sized.get(ValueLayout.JAVA_INT, 36)
+ val minor = sized.get(ValueLayout.JAVA_INT, 40)
+ val destroySeg = sized.get(ValueLayout.ADDRESS, 48)

- val resetSeg = sized.get(ValueLayout.ADDRESS, 16)
- val destroySeg = sized.get(ValueLayout.ADDRESS, 24)

- sized.set(ValueLayout.ADDRESS, 32, getFbStub)
- sized.set(ValueLayout.ADDRESS, 40, getProcStub)
- sized.set(ValueLayout.JAVA_BYTE, 48, 1)
+ sized.set(ValueLayout.ADDRESS, 16, getFbStub)
+ sized.set(ValueLayout.ADDRESS, 24, getProcStub)
+ sized.set(ValueLayout.JAVA_BYTE, 34, 1) // bottom_left_origin

  companion object {
      private const val HW_CONTEXT_NONE = 0
      private const val HW_CONTEXT_OPENGL = 1
-     private const val HW_CONTEXT_OPENGLES = 2
-     private const val HW_CONTEXT_OPENGL_CORE = 3
-     private const val HW_CONTEXT_OPENGLES2 = 4
-     private const val HW_CONTEXT_OPENGLES3 = 5
-     private const val HW_CONTEXT_OPENGLES_VERSION = 6
-     private const val HW_CONTEXT_VULKAN = 7
+     private const val HW_CONTEXT_OPENGLES2 = 2
+     private const val HW_CONTEXT_OPENGL_CORE = 3
+     private const val HW_CONTEXT_OPENGLES3 = 4
+     private const val HW_CONTEXT_OPENGLES_VERSION = 5
+     private const val HW_CONTEXT_VULKAN = 6
  }
```

### 2. [CRITICAL] Libretro environment command numbers are extensively misdefined

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroLayouts.kt` (`RetroEnv`)
- Problem: Examples include `GET_LOG_INTERFACE = 6` instead of 27, `GET_RUMBLE_INTERFACE = 27` instead of 23, `SET_VARIABLES = 12` instead of 16, `SET_FRAME_TIME_CALLBACK = 16` instead of 21, `SET_AUDIO_CALLBACK = 13` instead of 22, and `GET_AUDIO_VIDEO_ENABLE = 69` instead of 47. Consequently, a core sending one valid environment command can have its data interpreted as an unrelated structure; the worst case is installing a function pointer with the wrong ABI signature and later calling it. fileciteturn2file1L55-L75
- Fix:

```kotlin
internal object RetroEnv {
    const val GET_SYSTEM_DIRECTORY = 9
    const val SET_PIXEL_FORMAT = 10
    const val SET_INPUT_DESCRIPTORS = 11
    const val SET_DISK_CONTROL_INTERFACE = 13
    const val SET_HW_RENDER = 14
    const val GET_VARIABLE = 15
    const val SET_VARIABLES = 16
    const val GET_VARIABLE_UPDATE = 17
    const val GET_LIBRETRO_PATH = 19
    const val SET_FRAME_TIME_CALLBACK = 21
    const val SET_AUDIO_CALLBACK = 22
    const val GET_RUMBLE_INTERFACE = 23
    const val GET_LOG_INTERFACE = 27
    const val GET_SAVE_DIRECTORY = 31
    const val SET_CONTROLLER_INFO = 35
    const val GET_AUDIO_VIDEO_ENABLE = 47
    const val GET_TARGET_REFRESH_RATE = 50
    const val GET_INPUT_BITMASKS = 51
    const val GET_CORE_OPTIONS_VERSION = 52
    const val SET_CORE_OPTIONS = 53
    const val GET_PREFERRED_HW_RENDER = 56
    const val SET_SUPPORT_ACHIEVEMENTS = 42
}
```

The constants should preferably be generated/imported from the vendored `libretro.h` rather than duplicated manually.

### 3. [CRITICAL] Core-option parser walks the wrong native structure

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`parseCoreOptions`)
- Problem: `retro_core_option_definition` contains an inline fixed-size `values[]` array, but the code assumes the whole definition is 40 bytes and treats offset 32 as a pointer to that array. It then advances to the next definition after 40 bytes. A core that calls `SET_CORE_OPTIONS` can therefore make the frontend dereference strings and adjacent memory as arbitrary pointers. fileciteturn2file2L85-L104
- Fix:

```kotlin
private const val OPTION_VALUE_COUNT = 128
private const val VALUE_SIZE = 16L
private const val VALUES_OFFSET = 24L
private const val DEFAULT_OFFSET = VALUES_OFFSET + OPTION_VALUE_COUNT * VALUE_SIZE // 2072
private const val DEF_SIZE = DEFAULT_OFFSET + 8L // 2080 on 64-bit

private fun parseCoreOptions(data: MemorySegment) {
    coreOptions.clear()
    if (data.address() == 0L) return

    var defOffset = 0L
    while (true) {
        val keyPtr = data.get(ValueLayout.ADDRESS, defOffset)
        if (keyPtr.address() == 0L) break

        val key = cString(keyPtr, 256)
        val desc = cStringOr(data.get(ValueLayout.ADDRESS, defOffset + 8), 256, key)
        val info = cStringOrNull(data.get(ValueLayout.ADDRESS, defOffset + 16), 1024)

        val values = buildList {
            repeat(OPTION_VALUE_COUNT) { i ->
                val off = defOffset + VALUES_OFFSET + i * VALUE_SIZE
                val valuePtr = data.get(ValueLayout.ADDRESS, off)
                if (valuePtr.address() == 0L) return@buildList
                val value = cString(valuePtr, 256)
                val label = cStringOr(
                    data.get(ValueLayout.ADDRESS, off + 8),
                    256,
                    value,
                )
                add(CoreOptionValue(value, label))
            }
        }

        val defaultPtr = data.get(ValueLayout.ADDRESS, defOffset + DEFAULT_OFFSET)
        val defaultValue = cStringOr(defaultPtr, 256, values.firstOrNull()?.value ?: "")

        coreOptions += CoreOption(key, desc, info, defaultValue, values)
        optionSelections.putIfAbsent(key, defaultValue)
        defOffset += DEF_SIZE
    }
}
```

### 4. [CRITICAL] Input-descriptor parser has the wrong array terminator

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`parseInputDescriptors`)
- Problem: The code expects `port == 0xFFFFFFFF` to terminate the array. Libretro descriptor arrays terminate with a null description pointer. The current loop can therefore pass the terminator and continue reading successive 24-byte blocks from unrelated native memory until it happens to fault. fileciteturn2file3L112-L131
- Fix:

```diff
  while (true) {
      val port = data.get(ValueLayout.JAVA_INT, offset)
-     if (port == 0xFFFFFFFF.toInt()) break
      val device = data.get(ValueLayout.JAVA_INT, offset + 4)
      val id = data.get(ValueLayout.JAVA_INT, offset + 12)
      val descSeg = data.get(ValueLayout.ADDRESS, offset + 16)
+     if (descSeg.address() == 0L) break

-     val desc = if (descSeg.address() != 0L)
-         descSeg.reinterpret(128L).getUtf8String(0) else ""
+     val desc = descSeg.reinterpret(128L).getUtf8String(0)

      if (port == 0 && device == 1 && desc.isNotBlank()) {
          inputDescriptors["btn_$id"] = desc
      }
      offset += 24L
  }
```

### 5. [CRITICAL] Core shutdown can race an active `retro_run` or skip unload completely

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`stop`)
- Problem: `stop()` calls `frameLoop.cancel()` without joining it, schedules unload asynchronously on `scope`, and immediately calls `scope.cancel()`. The unload coroutine can therefore be cancelled before it runs; alternatively it can execute while native `retro_run` is still inside the core. On desktop that can progress to native library/resource teardown while executing code from that core. fileciteturn7file2L454-L468
- Fix:

```kotlin
suspend fun stop() {
    val loop = frameLoop
    frameLoop = null

    loop?.cancelAndJoin() // no native call may still be active

    withContext(coreDispatcher) {
        runCatching { controller.detach() }
        runCatching { flushSram() }
        runCatching { controller.unloadGame() }
        runCatching { controller.unloadCore() }
        runCatching { gamepadPoller.destroy() }
        runCatching { audioOutput.release() }
    }

    scope.cancel()
}
```

All libretro calls should additionally be confined to `coreDispatcher` as described below.

### 6. [CRITICAL] iOS builds `retro_game_info` from uninitialized native memory

- File: `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`loadGame`)
- Problem: `zeroBytes.copyInto(info.readBytes(32))` zeroes a temporary Kotlin copy, not `info`. Likewise, `pathBytes.copyInto(pathBuf.readBytes(...))` writes into another temporary copy, leaving `pathBuf` uninitialized. The native struct's `meta` field therefore remains garbage and `path` points at garbage bytes. None of `info`, `dataBuf`, or `pathBuf` is retained/freed correctly either. fileciteturn7file4L943-L983
- Fix:

```kotlin
private var gameInfoPtr: CPointer<ByteVar>? = null
private var gameDataPtr: CPointer<ByteVar>? = null
private var gamePathPtr: CPointer<ByteVar>? = null

private fun freeGameInfo() {
    gamePathPtr?.let(nativeHeap::free)
    gameDataPtr?.let(nativeHeap::free)
    gameInfoPtr?.let(nativeHeap::free)
    gamePathPtr = null
    gameDataPtr = null
    gameInfoPtr = null
}

override suspend fun loadGame(romPath: String): AvInfo {
    freeGameInfo()

    val info = nativeHeap.allocArray<ByteVar>(32)
    memset(info, 0, 32u)
    gameInfoPtr = info

    val path = romPath.cstr.getPointer(nativeHeap)
    gamePathPtr = path.reinterpret()

    info.reinterpret<CPointerVar<ByteVar>>()[0] = path.reinterpret()
    info.reinterpret<CPointerVar<ByteVar>>()[1] = null
    info.reinterpret<ULongVar>()[2] = 0uL
    info.reinterpret<CPointerVar<ByteVar>>()[3] = null

    val ok = requireSymbol("retro_load_game")
        .reinterpret<CFunction<(CPointer<ByteVar>?) -> Boolean>>()
        .invoke(info)

    check(ok) { "retro_load_game failed" }
    return readSystemAvInfo()
}

override fun unloadGame() {
    requireSymbol("retro_unload_game").reinterpret<CFunction<() -> Unit>>().invoke()
    freeGameInfo()
}
```

### 7. [HIGH] Desktop core and OpenGL context are allowed to hop threads

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`start`, `runLoop`)
- Problem: Core loading occurs in the caller's coroutine, while the frame loop is launched on `Dispatchers.Default`. That dispatcher may also resume the loop on different worker threads after delays. The hidden GLFW/OpenGL context is thread-affine, while `GlContext.makeCurrent()` merely binds an FBO and never calls `glfwMakeContextCurrent`. fileciteturn7file2L395-L430 fileciteturn3file1L43-L60
- Fix:

```kotlin
private val coreDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "omilator-core") }
        .asCoroutineDispatcher()

private val scope = CoroutineScope(SupervisorJob() + coreDispatcher)

suspend fun start() = withContext(coreDispatcher) {
    controller.loadCore(corePath)
    val avInfo = controller.loadGame(romPath)
    controller.attach(
        VideoSink { latestFrame.set(it) },
        AudioSinkAdapter(audioOutput),
        InputSourceAdapter(inputState),
    )
    audioOutput.configure(avInfo.timing.sampleRate, 2)
    gamepadPoller.init()

    frameLoop = scope.launch {
        runLoop(avInfo.timing.fps)
    }
}
```

And make the GL binding truthful:

```kotlin
fun makeCurrent() {
    GLFW.glfwMakeContextCurrent(window)
    if (GL.getCapabilities() == null) {
        GL.createCapabilities()
    }
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
}
```

### 8. [HIGH] Save-state, rewind, cheat, and option calls race `retro_run`

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt`
- Problem: `saveState()`, `loadState()`, `rewindStep()`, cheats, and option changes are callable from Compose/UI code while `runLoop()` invokes `controller.runFrame()` on the engine scope. Libretro cores generally maintain mutable global state around all of these operations, so concurrent serialize/unserialize/run calls can corrupt core state or native memory. fileciteturn7file2L478-L560
- Fix:

```kotlin
suspend fun saveState(path: String): Boolean = withContext(coreDispatcher) {
    controller.saveState(path)
}

suspend fun loadState(path: String): Boolean = withContext(coreDispatcher) {
    controller.loadState(path)
}

suspend fun rewindStep(): Boolean = withContext(coreDispatcher) {
    val state = rewindBuffer.pollLast() ?: return@withContext false
    controller.loadStateFromMemory(state)
}

suspend fun applyCheat(code: String) = withContext(coreDispatcher) {
    controller.cheatReset()
    controller.cheatSet(0, true, code.trim())
}

suspend fun setOptionValue(key: String, value: String) = withContext(coreDispatcher) {
    controller.setOptionValue(key, value)
    persistOptions()
}
```

All core operations should execute on the same single core thread.

### 9. [HIGH] Shared mobile player crosses Compose state between threads and never unloads the core

- File: `ui-shared/src/commonMain/kotlin/com/omilator/ui/player/MobilePlayerScreen.kt`
- Problem: A `Dispatchers.Default` coroutine directly writes `isLoading`, `error`, `argbPixels`, `frameW`, and `frameH`, while it reads `buttonStates`, a Compose `mutableStateMapOf`, concurrently with pointer handlers on the UI thread. Cancellation only releases audio; it does not detach, unload the game, or unload the core. fileciteturn7file3L697-L740
- Fix:

```kotlin
val inputBits = remember { AtomicIntArray(16) }

LaunchedEffect(romPath, corePath) {
    try {
        val av = withContext(Dispatchers.Default) {
            coreController.loadCore(corePath)
            val info = coreController.loadGame(romPath)
            coreController.attach(
                video = { frameChannel.trySend(it) },
                audio = { audioOutput.write(it) },
                input = { _, _, _, id ->
                    if (id in 0 until 16) inputBits[id] else 0
                },
            )
            info
        }

        frameW = av.geometry.baseWidth.toInt()
        frameH = av.geometry.baseHeight.toInt()
        isLoading = false

        withContext(Dispatchers.Default) {
            runCoreLoop(coreController, av.timing.fps, frameChannel)
        }
    } catch (t: Throwable) {
        error = t.message
        isLoading = false
    } finally {
        withContext(NonCancellable + Dispatchers.Default) {
            coreController.detach()
            runCatching { coreController.unloadGame() }
            runCatching { coreController.unloadCore() }
            audioOutput.release()
        }
    }
}
```

Touch handlers should write `inputBits`, not a Compose map consumed by the core thread.

### 10. [HIGH] iOS player repeats the same lifecycle/threading defect

- File: `ui-shared/src/iosMain/kotlin/com/omilator/ui/IosPlayerScreen.kt`
- Problem: The iOS-specific player runs the core on `Dispatchers.Default`, reads a Compose state map from native input callbacks, updates Compose state from the background coroutine, and only releases audio in `finally`. `NativeCoreController.unloadGame()`, `unloadCore()`, and `detach()` are never called when leaving the screen. fileciteturn7file0L79-L132
- Fix:

```kotlin
LaunchedEffect(romPath, corePath) {
    try {
        val av = withContext(coreDispatcher) {
            controller.loadCore(corePath)
            val info = controller.loadGame(romPath)
            controller.attach(videoSink, audioSink, atomicInputSource)
            audioOutput.configure(info.timing.sampleRate, 2)
            info
        }

        isLoading = false
        withContext(coreDispatcher) {
            runCoreLoop(controller, av.timing.fps)
        }
    } finally {
        withContext(NonCancellable + coreDispatcher) {
            controller.detach()
            runCatching { controller.unloadGame() }
            runCatching { controller.unloadCore() }
            audioOutput.release()
        }
    }
}
```

The duplicate `IosPlayerScreen` should ideally be removed and iOS should use the corrected common `MobilePlayerScreen`.

### 11. [HIGH] Android configures every core with GBA timing

- File: `core-libretro/src/androidMain/kotlin/com/omilator/core/libretro/impl/JniCoreController.kt` (`loadGame`)
- Problem: `loadGame()` always returns `240x160`, 60 FPS, and `65536 Hz`. The returned sample rate is immediately used to configure `AudioTrack`, so NES/SNES/PSP/N64/etc. can play at the wrong pitch/rate even though video callbacks later carry real dimensions. fileciteturn3file4L236-L253
- Fix:

```kotlin
override suspend fun loadGame(romPath: String): AvInfo {
    check(loaded) { throw CoreNotLoadedException("Core not loaded") }
    check(loadGameNative(romPath)) { "retro_load_game failed for $romPath" }

    val av = systemAvInfoNative()
    return AvInfo(
        geometry = Geometry(
            av[0].toUInt(),
            av[1].toUInt(),
            av[2].toUInt(),
            av[3].toUInt(),
            Float.fromBits(av[4]),
        ),
        timing = Timing(
            fps = Double.fromBits(av[5].toLong()).toFloat(),
            sampleRate = Double.fromBits(av[6].toLong()),
        ),
    )
}

private external fun systemAvInfoNative(): IntArray
```

The JNI bridge should call `retro_get_system_av_info` and return the actual values.

### 12. [HIGH] iOS hard-codes AV timing and double-buffers arbitrarily large ROMs

- File: `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`loadGame`, `readRomToBytes`)
- Problem: Every ROM is first loaded into a Kotlin `ByteArray`, then copied again to `nativeHeap`; a PSP/PS2-size image can therefore consume roughly twice its file size before core allocations. `ftell(...).toInt()` also truncates files larger than `Int.MAX_VALUE`. After all that, the controller still returns GBA AV values. fileciteturn7file4L955-L988 fileciteturn7file4L1095-L1110
- Fix:

```kotlin
// Read and retain need_fullpath from retro_get_system_info.
private var needFullPath = false

override suspend fun loadGame(romPath: String): AvInfo {
    val info = allocRetainedGameInfo()

    if (needFullPath) {
        info.path = retainCString(romPath)
        info.data = null
        info.size = 0u
    } else {
        val fileSize = getFileSize64(romPath)
        require(fileSize <= MAX_IN_MEMORY_ROM) {
            "Core requires in-memory content; ROM is too large: $fileSize"
        }
        val nativeData = readFileDirectlyToNativeHeap(romPath, fileSize)
        info.data = nativeData
        info.size = fileSize.toULong()
    }

    check(retroLoadGame(info.ptr))
    return readSystemAvInfo()
}
```

For large images, use full-path cores or memory mapping instead of making two complete copies.

### 13. [HIGH] iOS audio backpressure counter is a data race

- File: `core-audio/src/iosMain/kotlin/com/omilator/core/audio/IosAudioOutput.kt` (`write`, `flush`)
- Problem: `pendingBuffers++` happens on the core thread while the schedule completion handler decrements it on an audio callback thread. The comment explicitly accepts the torn access. `flush()` then calls `reset()` without resetting or generation-invalidating queued callbacks, so the counter can remain at the cap and cause permanent audio drops. fileciteturn6file2L93-L110
- Fix:

```kotlin
private val pendingLock = NSLock()
private var pendingBuffers = 0
private var generation = 0L

override fun write(samples: ShortArray): Int {
    val myGeneration = pendingLock.withLock {
        if (pendingBuffers >= MAX_PENDING) return samples.size
        pendingBuffers++
        generation
    }

    node.scheduleBuffer(buffer) {
        pendingLock.withLock {
            if (myGeneration == generation) {
                pendingBuffers = (pendingBuffers - 1).coerceAtLeast(0)
            }
        }
    }
    return samples.size
}

override fun flush() {
    pendingLock.withLock {
        generation++
        pendingBuffers = 0
    }
    playerNode?.stop()
    playerNode?.reset()
    playerNode?.play()
}
```

### 14. [HIGH] Battery-backed SRAM is never persisted

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/impl/FfmCoreController.kt` (`memorySize`, `readMemory`, `writeMemory`)
- Problem: Desktop reports `memorySize = 0`; Android/iOS similarly return empty/zero memory implementations. `PlayerEngine.stop()` never writes SRAM before unloading. Cores that rely on `retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)` therefore lose battery saves at exit. fileciteturn6file4L155-L174 fileciteturn7file4L1014-L1023
- Fix:

```kotlin
// LibretroFfm
private var getMemoryDataHandle: MethodHandle? = null
private var getMemorySizeHandle: MethodHandle? = null

fun readSaveRam(): ByteArray {
    val size = getMemorySizeHandle!!.invoke(RETRO_MEMORY_SAVE_RAM) as Long
    if (size <= 0) return ByteArray(0)

    val ptr = getMemoryDataHandle!!.invoke(RETRO_MEMORY_SAVE_RAM) as MemorySegment
    if (ptr.address() == 0L) return ByteArray(0)

    return ptr.reinterpret(size).toArray(ValueLayout.JAVA_BYTE)
}

fun writeSaveRam(bytes: ByteArray) {
    val size = getMemorySizeHandle!!.invoke(RETRO_MEMORY_SAVE_RAM) as Long
    val ptr = getMemoryDataHandle!!.invoke(RETRO_MEMORY_SAVE_RAM) as MemorySegment
    require(bytes.size.toLong() == size)
    MemorySegment.copy(bytes, 0, ptr.reinterpret(size), ValueLayout.JAVA_BYTE, 0, bytes.size)
}
```

Then flush before unload:

```kotlin
private suspend fun flushSram() {
    val data = controller.readMemory(RETRO_MEMORY_SAVE_RAM, 0u, controller.memorySize)
    if (data.isNotEmpty()) atomicWrite(sramFile(), data)
}
```

### 15. [HIGH] Android directory picker stores URIs that its scanner cannot read

- File: `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt` (`dirPickerLauncher`)
- Problem: The picker stores strings such as `content://...` in settings, but `AndroidLibraryScanner` constructs `File(directory)` and immediately rejects anything that is not a filesystem directory. Consequently, the primary Android “Add directory” flow successfully persists permission and then scans zero ROMs. fileciteturn1file7L696-L713 fileciteturn5file3L117-L136
- Fix:

```kotlin
class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {
    override suspend fun scan(directory: String): List<Game> =
        withContext(Dispatchers.IO) {
            val uri = directory.toUri()
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val root = DocumentFile.fromTreeUri(context, uri)
                    ?: return@withContext emptyList()
                scanDocumentTree(root)
            } else {
                scanFileTree(File(directory))
            }
        }

    private fun scanDocumentTree(root: DocumentFile): List<Game> =
        root.listFiles().flatMap { doc ->
            when {
                doc.isDirectory -> scanDocumentTree(doc)
                doc.isFile -> gameFromDocument(doc)?.let(::listOf) ?: emptyList()
                else -> emptyList()
            }
        }
}
```

Construct it with application context:

```kotlin
LibraryRepository(AndroidLibraryScanner(applicationContext))
```

### 16. [HIGH] iOS document picker loses access to external documents

- File: `ui-shared/src/iosMain/kotlin/com/omilator/ui/IosFilePicker.kt` (`documentPicker`)
- Problem: The picker returns only `NSURL.path`. There is no `startAccessingSecurityScopedResource()`, bookmark retention, or copy into the app sandbox. Directory selection is also merely printed by `RootViewController`; it is not added to either library/settings state. Files outside the sandbox can therefore become unreadable immediately after the callback. fileciteturn5file4L173-L195 fileciteturn7file1L298-L305
- Fix:

```kotlin
override fun documentPicker(
    controller: UIDocumentPickerViewController,
    didPickDocumentsAtURLs: List<*>,
) {
    val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        ?: return onPicked(null)

    val accessing = url.startAccessingSecurityScopedResource()
    try {
        val importedPath = copyIntoDocuments(url)
        onPicked(importedPath)
    } finally {
        if (accessing) url.stopAccessingSecurityScopedResource()
    }
}
```

For directories, persist a security-scoped bookmark or copy/import the selected ROMs, then actually add the resolved location:

```kotlin
pickDirectory(vc) { path ->
    if (path != null) {
        libraryViewModel.addDirectory(path)
        settingsViewModel.addDirectory(path)
    }
}
```

### 17. [HIGH] Settings updates destructively reset unrelated fields

- File: `ui-shared/src/commonMain/kotlin/com/omilator/ui/library/LibraryViewModel.kt` (`persistSettings`)
- Problem: Adding/removing a library directory saves a brand-new `AppSettings(libraryDirectories = directories)`, resetting theme, API key, audio latency, save/core directories, vsync, and future fields. `SettingsViewModel.persist()` performs another partial reconstruction. Both run asynchronously, so concurrent updates are also last-writer-wins. fileciteturn4file0L8-L25 fileciteturn4file1L44-L60
- Fix:

```kotlin
class SettingsStore(...) {
    private val mutex = Mutex()

    suspend fun updateAppSettings(
        path: String,
        transform: (AppSettings) -> AppSettings,
    ): AppSettings = mutex.withLock {
        val current = loadAppSettings(path)
        val updated = transform(current)
        saveAppSettings(updated, path)
        updated
    }
}
```

Library updates then preserve everything else:

```kotlin
scope.launch {
    store.updateAppSettings(settingsPath) {
        it.copy(libraryDirectories = directories)
    }
}
```

Theme/API updates should use the same method:

```kotlin
store.updateAppSettings(settingsPath) {
    it.copy(
        theme = state.theme,
        theGamesDbApiKey = state.theGamesDbApiKey,
    )
}
```

### 18. [HIGH] Windows/Linux desktop distributions contain macOS-only native dependencies and cores

- File: `core-libretro/build.gradle.kts`, `core-input/build.gradle.kts`, `data-library/src/desktopMain/.../CoreDownloader.kt`
- Problem: Both desktop modules include only `natives-macos-arm64`; `CoreDownloader` always downloads Apple ARM64 `.dylib` files. Windows/Linux builds can compile but fail once GLFW/OpenGL/gamepad code loads, and first-run setup downloads cores they cannot execute. fileciteturn5file0L9-L15 fileciteturn5file0L31-L42 fileciteturn5file1L55-L79
- Fix:

```kotlin
val lwjglNatives = when {
    OperatingSystem.current().isWindows -> "natives-windows"
    OperatingSystem.current().isLinux -> "natives-linux"
    System.getProperty("os.arch") == "aarch64" -> "natives-macos-arm64"
    else -> "natives-macos"
}

runtimeOnly("org.lwjgl:lwjgl:3.3.4:$lwjglNatives")
runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.4:$lwjglNatives")
runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.4:$lwjglNatives")
```

And choose downloader platform dynamically:

```kotlin
private val platform = when {
    os.isWindows -> Platform("windows/x86_64", "dll")
    os.isLinux -> Platform("linux/x86_64", "so")
    os.isMac && arch == "aarch64" -> Platform("apple/osx/arm64", "dylib")
    else -> Platform("apple/osx/x86_64", "dylib")
}

private val buildbotBase =
    "https://buildbot.libretro.com/nightly/${platform.buildbotPath}/latest"
```

### 19. [HIGH] Core callbacks are installed after `retro_init`

- File: `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`loadCore`)
- Problem: Only the environment callback is installed before `retro_init`; video/audio/input callbacks are installed afterward. Desktop follows the same sequence. A core is therefore initialized while several frontend callbacks are unset, which is outside the safe canonical initialization sequence and can break cores that consult them during startup. fileciteturn7file4L902-L922
- Fix:

```diff
  retro_set_environment(envPtr)

- retro_init()
-
- retro_set_video_refresh(videoPtr)
- retro_set_audio_sample_batch(audioBatchPtr)
- retro_set_audio_sample(audioSamplePtr)
- retro_set_input_poll(inputPollPtr)
- retro_set_input_state(inputStatePtr)
+ retro_set_video_refresh(videoPtr)
+ retro_set_audio_sample_batch(audioBatchPtr)
+ retro_set_audio_sample(audioSamplePtr)
+ retro_set_input_poll(inputPollPtr)
+ retro_set_input_state(inputStatePtr)
+
+ retro_init()
```

Apply the same order in `FfmCoreController.loadCore()`.

### 20. [HIGH] iOS native callback signatures intentionally mismatch the C ABI

- File: `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt`
- Problem: `hwGetFramebufferCb` and `inputPollCb` represent C callbacks with zero arguments but are compiled as callbacks taking a dummy `Int`. The comment relies on ARM64 “ignoring” the extra parameter. Calling through a function pointer of a different type is undefined at the ABI boundary and makes the bridge architecture/compiler dependent. fileciteturn6file3L118-L135
- Fix:

```kotlin
private fun hwGetFramebuffer(): ULong =
    nativeHwRender?.currentFramebuffer() ?: 0uL

private fun inputPoll() {
    // no-op
}

internal val hwGetFramebufferCb =
    staticCFunction(::hwGetFramebuffer)

internal val inputPollCb =
    staticCFunction(::inputPoll)
```

If overload resolution is the issue, use explicitly typed top-level functions rather than changing the native signature.

### 21. [MEDIUM] ROM extension detection silently misclassifies ambiguous formats

- File: `data-library/src/commonMain/kotlin/com/omilator/data/library/Game.kt` (`detectByExtension`)
- Problem: `.bin`, `.elf`, `.app`, `.chd`, `.m3u`, `.cue`, `.iso`, and `.ciso` occur in multiple systems, but `entries.firstOrNull` silently selects the first enum entry. The comment says ISO resolves to PS1, while the actual enum puts `.iso` under PSP and Saturn, so it resolves PSP. Mobile launchers then compound this by defaulting many unknown extensions to mGBA. fileciteturn6file0L7-L30 fileciteturn7file1L306-L328
- Fix:

```kotlin
fun candidatesByExtension(extension: String): List<GameSystem> {
    val ext = extension.lowercase()
    return entries.filter { system ->
        system.extensions.any { it.equals(ext, ignoreCase = true) }
    }
}

fun detectByExtension(extension: String): GameSystem? {
    val candidates = candidatesByExtension(extension)
    return candidates.singleOrNull()
}
```

For ambiguous files, resolve using cue contents, magic/header detection, directory metadata, or a persisted per-game override instead of guessing.

### 22. [MEDIUM] Scale shortcut leaves the emulated X button stuck

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerScreen.desktop.kt` (`onKeyEvent`)
- Problem: `KeyboardMapping` maps `S` to `JoypadButton.X`. KeyDown reaches the mapping and presses X, but KeyUp is intercepted first by the “cycle scaling” shortcut and returns before releasing X. The core sees X held forever after a single S press. fileciteturn4file3L102-L118 fileciteturn4file4L136-L160
- Fix:

```diff
- if (event.type == KeyEventType.KeyUp &&
-     keyCode == KeyEvent.VK_S) {
+ if (event.type == KeyEventType.KeyUp &&
+     keyCode == KeyEvent.VK_V) {
      scaleMode = (scaleMode + 1) % 3
      return@onKeyEvent true
  }
```

Also define reserved UI shortcuts separately and assert they do not overlap emulated mappings.

### 23. [MEDIUM] Gamepad disconnects cause stuck input and analog input is effectively disabled

- File: `core-input/src/desktopMain/kotlin/com/omilator/core/input/GamepadPoller.kt` (`poll`)
- Problem: Polling returns without clearing prior state when a controller disappears, so any button held at disconnect remains pressed in `InputStateHolder`. `0 until GLFW_JOYSTICK_LAST` also omits the final joystick ID. Analog axes have no deadzone, and `PlayerEngine` discards every analog callback with `setAnalog = { _, _ -> }`. fileciteturn4file2L75-L94
- Fix:

```kotlin
fun poll(
    setButton: (Int, Boolean) -> Unit,
    setAnalog: (Int, Int) -> Unit,
) {
    var found = false

    for (jid in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
        if (!GLFW.glfwJoystickIsGamepad(jid)) continue
        if (!GLFW.glfwGetGamepadState(jid, state)) continue

        found = true
        // map buttons...

        fun deadzone(v: Float): Float =
            if (abs(v) < 0.15f) 0f else v

        setAnalog(0, (deadzone(axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X)) * 32767).toInt())
        // ...
        break
    }

    if (!found) {
        JoypadButtonIds.forEach { setButton(it, false) }
        repeat(4) { setAnalog(it, 0) }
    }
}
```

`PlayerEngine` must retain and expose analog state instead of discarding it.

### 24. [MEDIUM] iOS `AudioOutputFactory` produces silence

- File: `core-audio/src/iosMain/kotlin/com/omilator/core/audio/PlatformAudioOutputFactory.kt`
- Problem: The iOS `actual` factory returns `StubAudioOutput`, whose writes are discarded. Any shared code using the advertised expect/actual factory on iOS will silently have no audio even though a real `IosAudioOutput` exists. fileciteturn6file1L45-L64
- Fix:

```kotlin
private class IosFactory : AudioOutputFactory {
    override fun create(): AudioOutput = IosAudioOutput()
}

actual fun createAudioOutputFactory(): AudioOutputFactory = IosFactory()
```

Delete `StubAudioOutput` from `iosMain`.

### 25. [MEDIUM] Desktop FFM arena keeps cores loaded after unload

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/impl/FfmCoreController.kt`
- Problem: A single `Arena.ofShared()` is created for the controller and never closed in `unloadCore()`. `SymbolLookup.libraryLookup`, upcall stubs, strings, serialized buffers, and the native library therefore remain alive for the controller's lifetime even after its `native` reference is cleared. fileciteturn6file4L155-L174
- Fix:

```kotlin
private var arena: Arena? = null
private var native: LibretroFfm? = null

override suspend fun loadCore(path: String): SystemInfo {
    check(native == null)
    val newArena = Arena.ofShared()
    try {
        val n = LibretroFfm(newArena, systemDirectory)
        n.loadCore(path)
        // ...
        arena = newArena
        native = n
        return buildSystemInfo(n)
    } catch (t: Throwable) {
        newArena.close()
        throw t
    }
}

override fun unloadCore() {
    val n = native
    native = null

    runCatching { n?.hwRender?.destroy() }
    runCatching { n?.callDeinit() }

    arena?.close()
    arena = null
}
```

### 26. [MEDIUM] FFM AV-info layout reads aspect ratio at the wrong offset

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroLayouts.kt` (`gameGeometry`, `systemAvInfo`)
- Problem: Four `uint32` fields occupy 16 bytes and `aspect_ratio` follows immediately, but the layout inserts four bytes of padding before the float. The required alignment padding belongs between the 20-byte geometry struct and the following double-based timing struct. The current code can therefore return a garbage aspect ratio while FPS/sample rate happen to align. fileciteturn0file0L3225-L3243
- Fix:

```kotlin
val gameGeometry: MemoryLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("base_width"),
    ValueLayout.JAVA_INT.withName("base_height"),
    ValueLayout.JAVA_INT.withName("max_width"),
    ValueLayout.JAVA_INT.withName("max_height"),
    ValueLayout.JAVA_FLOAT.withName("aspect_ratio"),
)

val systemAvInfo: MemoryLayout = MemoryLayout.structLayout(
    gameGeometry.withName("geometry"),
    MemoryLayout.paddingLayout(4),
    systemTiming.withName("timing"),
)
```

### 27. [MEDIUM] Default libretro 0RGB1555 frames are treated as XRGB8888

- File: `core-libretro/src/commonMain/kotlin/com/omilator/core/libretro/api/Types.kt`
- Problem: The public `PixelFormat` enum exposes only XRGB8888/RGB565 and gives them IDs 0/1, while the desktop native constants correctly show `ORGB1555=0`, `XRGB8888=1`, `RGB565=2`. Platform controllers initialize `pixelFormat = 1` and map everything except 2 to XRGB8888. A core that retains libretro's default 0RGB1555 format is therefore interpreted with the wrong bytes-per-pixel. fileciteturn0file0L2249-L2257 fileciteturn0file0L3275-L3279
- Fix:

```kotlin
enum class PixelFormat(val retroId: UInt, val bytesPerPixel: Int) {
    ORGB1555(0u, 2),
    XRGB8888(1u, 4),
    RGB565(2u, 2),
}

private fun pixelFormatFromRetro(value: Int): PixelFormat = when (value) {
    0 -> PixelFormat.ORGB1555
    1 -> PixelFormat.XRGB8888
    2 -> PixelFormat.RGB565
    else -> error("Unsupported libretro pixel format $value")
}
```

Add 0RGB1555 conversion to `FrameConverter` and mobile conversion code.

### 28. [MEDIUM] Run-ahead duplicates audio and volume controls do nothing

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`runLoop`, `setVolume`)
- Problem: Run-ahead saves state, calls `runFrame()` again, then restores state, but the speculative call still passes audio into `AudioOutput`. The user consequently hears audio from a frame that was rolled back. Separately, `volume` is stored but `AudioSinkAdapter` writes unmodified samples, so +/- controls have no audible effect. fileciteturn7file2L516-L584
- Fix:

```kotlin
private var suppressAudio = false
private var volume = 1.0f

private val audioSink = AudioSink { samples ->
    if (!suppressAudio) {
        val scaled = ShortArray(samples.size) { i ->
            (samples[i] * volume)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        audioOutput.write(scaled)
    }
}

if (runAhead > 0) {
    val saved = controller.saveStateToMemory()
    suppressAudio = true
    try {
        controller.runFrame()
    } finally {
        suppressAudio = false
        controller.loadStateFromMemory(saved)
    }
}
```

### 29. [MEDIUM] Settings files are replaced non-atomically and corruption silently resets everything

- File: `data-settings/src/commonMain/kotlin/com/omilator/data/settings/SettingsStore.kt`
- Problem: Desktop uses `File.writeText` and iOS opens the real settings file with `"wb"`, truncating it before the new JSON is complete. A crash/kill/disk error can leave partial JSON; the next `loadAppSettings()` catches decoding failure and silently returns `AppSettings.DEFAULT`, making corruption look like a legitimate reset. fileciteturn1file4L419-L426 fileciteturn1file4L455-L462
- Fix:

```kotlin
suspend fun saveAppSettings(settings: AppSettings, path: String) {
    val encoded = json.encodeToString(settings)
    writeTextAtomically(path, encoded)
}
```

Desktop implementation:

```kotlin
val target = Paths.get(path)
val temp = target.resolveSibling("${target.fileName}.tmp")
Files.writeString(temp, content)
Files.move(
    temp,
    target,
    StandardCopyOption.REPLACE_EXISTING,
    StandardCopyOption.ATOMIC_MOVE,
)
```

Also preserve/report corrupt files instead of silently replacing them:

```kotlin
return try {
    json.decodeFromString(text)
} catch (e: SerializationException) {
    throw SettingsCorruptException(path, e)
}
```

### 30. [MEDIUM] Long-lived global coroutines outlive UI owners

- File: `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt`
- Problem: Android and desktop use `GlobalScope`; iOS creates raw `CoroutineScope(Dispatchers.Default)` instances with no retained job. Downloads/scans can continue after an Activity/window/controller is destroyed and still update view-model/UI state. fileciteturn1file7L741-L744 fileciteturn7file1L229-L249
- Fix:

```kotlin
// Android
lifecycleScope.launch(Dispatchers.IO) {
    libraryViewModel.rescan(...)
}

// Desktop composable
val uiScope = rememberCoroutineScope()
onDownloadCores = {
    uiScope.launch(Dispatchers.IO) { ... }
}
```

For iOS root ownership:

```kotlin
val rootJob = SupervisorJob()
val rootScope = CoroutineScope(rootJob + Dispatchers.Default)

// retain rootScope for the controller lifetime
// rootJob.cancel() when the UIViewController is disposed/deinitialized
```

### 31. [MEDIUM] iOS scanner ignores its input directory and is non-recursive

- File: `data-library/src/iosMain/kotlin/com/omilator/data/library/IosLibraryScanner.kt` (`scan`)
- Problem: `scan(directory)` completely ignores `directory`, calls `documentsDirectory()`, and then only enumerates immediate children. Persisted directories or selected subdirectories therefore have no effect, and ROMs nested inside folders are not discovered. fileciteturn0file0L6049-L6091
- Fix:

```kotlin
override suspend fun scan(directory: String): List<Game> =
    withContext(Dispatchers.Default) {
        scanDirectory(directory)
    }

private fun scanDirectory(root: String): List<Game> {
    val fm = NSFileManager.defaultManager
    val enumerator = fm.enumeratorAtPath(root) ?: return emptyList()

    return buildList {
        while (true) {
            val relative = enumerator.nextObject() as? String ?: break
            val fullPath = "$root/$relative"

            val ext = relative.substringAfterLast('.', "")
            val system = GameSystem.detectByExtension(ext) ?: continue

            add(
                Game(
                    id = fullPath,
                    title = cleanRomTitle(relative.substringAfterLast('/').substringBeforeLast('.')),
                    system = system,
                    filePath = fullPath,
                    fileSizeBytes = fileSize(fullPath),
                )
            )
        }
    }
}
```

### 32. [MEDIUM] Mobile frame scheduler truncates 60 Hz timing to 16 ms

- File: `ui-shared/src/commonMain/kotlin/com/omilator/ui/player/MobilePlayerScreen.kt`
- Problem: `(1000.0 / fps).toLong()` turns 16.666 ms into 16 ms. With a cheap `retro_run`, a 60 Hz core is scheduled near 62.5 Hz; 59.94 Hz is similarly overdriven. Audio is generated according to emulated frames, so this also increases audio production/latency pressure. The separate iOS player has the same code. fileciteturn7file3L720-L740 fileciteturn7file0L112-L131
- Fix:

```kotlin
private suspend fun runCoreLoop(controller: CoreController, fps: Float) {
    val intervalNs = (1_000_000_000.0 / fps).toLong()
    var deadline = monotonicTimeNanos()

    while (currentCoroutineContext().isActive) {
        controller.runFrame()
        deadline += intervalNs

        val remaining = deadline - monotonicTimeNanos()
        if (remaining > 0) {
            delay(remaining / 1_000_000)
        } else {
            deadline = monotonicTimeNanos()
        }
    }
}
```

### 33. [MEDIUM] Runtime core-option changes are never announced

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`GET_VARIABLE_UPDATE`, `setOptionValue`)
- Problem: The environment callback always returns false for `GET_VARIABLE_UPDATE`, while `setOptionValue()` simply modifies the map. Cores that only re-read variables when the update flag becomes true will never observe UI option changes. fileciteturn0file0L2970-L2975 fileciteturn0file0L3149-L3150
- Fix:

```kotlin
@Volatile
private var variablesDirty = false

fun setOptionValue(key: String, value: String) {
    if (optionSelections[key] != value) {
        optionSelections[key] = value
        variablesDirty = true
    }
}

private fun handleVariableUpdate(data: MemorySegment): Boolean {
    if (data.address() == 0L) return false
    data.reinterpret(1).set(ValueLayout.JAVA_BOOLEAN, 0, variablesDirty)
    variablesDirty = false
    return true
}
```

Then:

```kotlin
RetroEnv.GET_VARIABLE_UPDATE -> handleVariableUpdate(data)
```

### 34. [MEDIUM] `InputManager` mutates a `StateFlow` value in place

- File: `core-input/src/commonMain/kotlin/com/omilator/core/input/InputManager.kt` (`update`, `InputState`)
- Problem: `_state.value.apply(transform)` modifies the same `InputState` instance and assigns the same object back. `StateFlow` may therefore suppress emissions because the value compares equal to itself. `setButton()` and `setAnalog()` also index raw arrays without validating indices, unlike the getter methods. fileciteturn0file0L1564-L1602
- Fix:

```kotlin
data class InputState(
    val buttons: IntArray = IntArray(16),
    val analogs: IntArray = IntArray(4),
) {
    fun copyMutable(): InputState =
        InputState(buttons.copyOf(), analogs.copyOf())
}

fun update(transform: InputState.() -> Unit) {
    val next = _state.value.copyMutable()
    next.transform()
    _state.value = next
}

fun InputState.setButton(button: Int, pressed: Boolean) {
    if (button in buttons.indices) {
        buttons[button] = if (pressed) 1 else 0
    }
}

fun InputState.setAnalog(index: Int, value: Int) {
    if (index in analogs.indices) {
        analogs[index] = value.coerceIn(-32768, 32767)
    }
}
```

### 35. [MEDIUM] Hardware-render readback is vertically inverted

- File: `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/GlContext.kt` (`readPixelsRGBA`)
- Problem: The source comment correctly notes that OpenGL returns rows bottom-first and that the caller must flip them. `LibretroFfm.callRunHwFrame()` forwards the returned byte array directly to the regular top-down framebuffer conversion path without performing that flip. fileciteturn0file0L3693-L3710 fileciteturn0file0L3032-L3043
- Fix:

```kotlin
fun readPixelsRGBA(): ByteArray {
    val raw = readRawPixels()
    val stride = width * 4
    val flipped = ByteArray(raw.size)

    for (y in 0 until height) {
        val src = y * stride
        val dst = (height - 1 - y) * stride
        raw.copyInto(flipped, dst, src, src + stride)
    }
    return flipped
}
```

### 36. [LOW] iOS deep-link handler destroys absolute paths

- File: `iosApp/iOSApp.swift` (`handle(url:)`)
- Problem: After extracting `url.path`, the code removes its leading `/`. The documented payload is an absolute ROM path, so `/Users/...` or `/private/...` becomes `Users/...` / `private/...`, causing subsequent `fopen` to use a relative path. fileciteturn0file0L6492-L6499
- Fix:

```diff
- // Strip leading slash if present (path-style)
- if romPath.hasPrefix("/") { romPath.removeFirst() }
-
  romPath = romPath.removingPercentEncoding ?? romPath
```

For the absolute-string fallback, explicitly restore the slash if needed:

```swift
if !romPath.hasPrefix("/") {
    romPath = "/" + romPath
}
```

### 37. [LOW] Save-state metadata is recreated incorrectly on every listing

- File: `data-saves/src/commonMain/kotlin/com/omilator/data/saves/SaveState.kt` (`SaveStateRepository.list`)
- Problem: Slots are assigned from `mapIndexed`, so changing directory enumeration order changes slot numbers. `createdAt` is always `Clock.System.now()`, meaning every old state appears newly created whenever the list is refreshed. Matching with `startsWith(gameId)` is also fragile when `gameId` is an absolute ROM path but state files are basename-based. fileciteturn0file0L6161-L6175
- Fix:

```kotlin
private val stateName =
    Regex("""^(.+)\.slot(\d+)\.state$""")

suspend fun list(gameId: String, directory: String): List<SaveState> {
    val gameKey = stableGameKey(gameId)

    return listDir(directory).mapNotNull { path ->
        val name = path.substringAfterLast('/')
        val match = stateName.matchEntire(name) ?: return@mapNotNull null
        if (match.groupValues[1] != gameKey) return@mapNotNull null

        SaveState(
            id = path,
            gameId = gameId,
            slot = match.groupValues[2].toInt(),
            createdAt = fileModifiedAt(path),
            sizeBytes = fileSize(path) ?: 0L,
        )
    }.sortedBy { it.slot }
}
```

### 38. [LOW] Desktop TheGamesDB setting is not connected to cover-art loading

- File: `ui-shared/src/desktopMain/kotlin/com/omilator/ui/library/CoverArt.desktop.kt` (`rememberCoverArt`)
- Problem: The UI persists a TheGamesDB API key, but `rememberCoverArt()` always constructs `CoverArtService(cacheDir)` without a key. Therefore `TheGamesDbService.isActive` remains false and the configured key never affects desktop cover resolution. fileciteturn0file0L8880-L8892
- Fix:

```kotlin
@Composable
fun rememberCoverArt(
    game: Game,
    theGamesDbApiKey: String,
): ImageBitmap? {
    val coverState = produceState<ImageBitmap?>(
        initialValue = null,
        game.id,
        theGamesDbApiKey,
    ) {
        value = withContext(Dispatchers.IO) {
            val cacheDir = File(
                System.getProperty("user.home"),
                "Library/Application Support/Omilator/covers",
            )
            CoverArtService(
                cacheDir = cacheDir,
                theGamesDbKey = theGamesDbApiKey,
            ).resolveCover(game)?.let {
                ImageIO.read(it)?.toComposeImageBitmap()
            }
        }
    }
    return coverState.value
}
```
