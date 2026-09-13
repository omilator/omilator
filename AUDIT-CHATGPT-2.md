# omilator - Audit Pass 2 (2026-09-12)

> STATUS: all 18 pass-2 findings verified against source and fixed (same day).
> Verified: forced re-compile of desktop, android (Kotlin + NDK native), iosArm64,
> iosSimulatorArm64. Buildbot artifact names verified against the live index.
> Notable corrections to the audit's own fixes: #6 accepts ONLY GL-core <=3.x
> (the audit's compat/GLES branches would hand cores contexts the readback code
> cannot use); #5 keeps max-size fallback dims for cores that never report.

## Verdict

**18 new findings: 1 Critical, 12 High, 5 Medium.** The Pass 1 findings that are actually fixed are not repeated below, and the documented deliberate limits are excluded (mobile SRAM remains intentionally unsupported, Android SAF playback still materializes to cache instead of streaming, and `IosPlayerScreen` remains intentionally duplicated).

Re-checked areas with no new defect in the specific fix itself: desktop `RetroEnv` now carries the required `0x10000` experimental bit on commands 42/47/50/51; `retro_hw_render_callback` offsets are now `context_reset@8`, frontend functions at `16/24`, `context_destroy@48`, with `bottom_left_origin` read at 34; `parseCoreOptions()` uses the 2080-byte `retro_core_option_definition` stride; iOS `fseeko`/`ftello` file sizing is 64-bit; iOS AV timing offsets `aspect@16`, `fps@24`, `sample_rate@32` are correct; the shared settings read-modify-write mutex is present; and the mobile players now use atomic button state plus `NonCancellable` teardown. fileciteturn2file1L4274-L4312 fileciteturn6file4L598-L610 fileciteturn8file0L176-L187 fileciteturn10file1L115-L134 fileciteturn4file3L597-L619 fileciteturn6file2L368-L424

## Findings

### 1. [CRITICAL] FFM environment callbacks dereference zero-length pointer segments

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`installEnvironmentCallback`, `onEnvironment`, `parseCoreOptions`, `parseInputDescriptors`, `handleGetVariable`)

**Problem:** `installEnvironmentCallback()` declares the `void *data` upcall parameter as bare `ValueLayout.ADDRESS`. Under JDK 21 FFM, pointer arguments delivered to an upcall through a bare address layout are zero-length `MemorySegment`s. Several handlers correctly call `reinterpret()` before dereferencing, but the new option/input parsers and `handleGetVariable()` call `data.get(...)` directly. A core invoking `SET_CORE_OPTIONS`, `SET_INPUT_DESCRIPTORS`, or `GET_VARIABLE` can therefore throw `IndexOutOfBoundsException` inside the upcall. JDK 21 explicitly warns that an exception escaping an upcall target terminates the JVM abruptly. fileciteturn5file0L16-L23 fileciteturn8file0L172-L245 fileciteturn8file0L266-L285 citeturn767091search0turn767091search1

**Fix:** Reinterpret `data` to a bounded view before every dereference, cap sentinel-terminated walks, and prevent exceptions from escaping the upcall.

```kotlin
private fun view(data: MemorySegment, bytes: Long): MemorySegment =
    data.reinterpret(bytes)

private fun handleGetVariable(data: MemorySegment): Boolean {
    if (data.address() == 0L) return false
    val variable = view(data, 16L)
    val keyPtr = variable.get(ValueLayout.ADDRESS, 0)
    if (keyPtr.address() == 0L) return false
    val key = keyPtr.reinterpret(256L).getUtf8String(0)
    val value = optionSelections[key] ?: return false
    variable.set(ValueLayout.ADDRESS, 8, arena.allocateUtf8String(value))
    return true
}

// Same rule in parseCoreOptions/parseInputDescriptors:
val record = data.reinterpret(offset + DEF_SIZE)
val keyPtr = record.get(ValueLayout.ADDRESS, offset)
```

Also make the upcall entry point non-throwing:

```kotlin
fun onEnvironment(cmd: Int, data: MemorySegment): Boolean =
    try { handleEnvironment(cmd, data) }
    catch (t: Throwable) {
        System.err.println("[Omilator] env $cmd failed: ${t.message}")
        false
    }
```

### 2. [HIGH] `PlayerEngine.stop()` can deadlock and always blocks the Compose UI thread

**File:** `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`stop`, `runLoop`); `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerScreen.desktop.kt` (`PlayerScreen`, `DisposableEffect.onDispose`)

**Problem:** `stop()` performs `runBlocking { loop?.cancelAndJoin() ... }`. The frame job is cancelled, but `runLoop()` tests `scope.isActive` (the parent scope), not the current child job. If emulation is running behind schedule, `wait <= 0` and the loop reaches no suspension point, so cancellation may never be observed and `cancelAndJoin()` can wait forever. Even when it terminates normally, `PlayerScreen` invokes `engine.stop()` synchronously from `onDispose`, so the UI thread is blocked for the entire join + SRAM + native unload + audio teardown. fileciteturn8file2L698-L720 fileciteturn8file2L755-L795 fileciteturn14file0L70-L77

**Fix:** Observe the frame job's own cancellation and make shutdown suspending/asynchronous from Compose.

```kotlin
private suspend fun runLoop(targetFps: Float) {
    val baseIntervalNanos = (1_000_000_000.0 / targetFps).toLong()
    var nextDeadline = System.nanoTime()
    while (currentCoroutineContext().isActive) {
        controller.runFrame()
        currentCoroutineContext().ensureActive()
        // ...
        if (wait > 0) delay(wait / 1_000_000)
    }
}

suspend fun stop() {
    frameLoop.also { frameLoop = null }?.cancelAndJoin()
    withContext(coreDispatcher) {
        runCatching { controller.detach() }
        runCatching { flushSram() }
        runCatching { controller.unloadGame() }
        runCatching { controller.unloadCore() }
        runCatching { gamepadPoller.destroy() }
        runCatching { audioOutput.release() }
    }
    scope.cancel()
    coreDispatcher.close()
}
```

Drive it from an owner that can launch teardown instead of blocking `onDispose`.

### 3. [HIGH] Core-option version negotiation routes compliant cores into an unimplemented fallback

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`onEnvironment`)

**Problem:** The corrected v1 `SET_CORE_OPTIONS` parser exists, but `GET_CORE_OPTIONS_VERSION` is only listed in `silentEnvCmds`; it has no handler, so the frontend returns `false`. A conforming core then assumes the legacy option interface and calls `SET_VARIABLES`. Omilator returns `true` for `SET_VARIABLES` but deliberately does not parse it. Result: the new 2080-byte parser is bypassed for exactly the cores that negotiate correctly, and later `GET_VARIABLE` cannot resolve those options. fileciteturn8file0L44-L64 fileciteturn8file0L85-L100

**Fix:** Advertise the option API actually implemented, and still parse the legacy fallback.

```kotlin
RetroEnv.GET_CORE_OPTIONS_VERSION -> {
    data.reinterpret(4L).set(ValueLayout.JAVA_INT, 0, 1)
    true
}
RetroEnv.SET_CORE_OPTIONS -> {
    parseCoreOptions(data)
    true
}
RetroEnv.SET_VARIABLES -> {
    parseLegacyVariables(data) // retro_variable[] terminated by {NULL,NULL}
    true
}
```

### 4. [HIGH] Desktop reports environment capabilities as successful without writing required outputs

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`onEnvironment`); `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`InputSourceAdapter.poll`)

**Problem:** `GET_LIBRETRO_PATH` and experimental `GET_AUDIO_VIDEO_ENABLE` return `true` without writing their required outputs (`const char **` and the audio/video enable bitmask respectively). Experimental `GET_INPUT_BITMASKS` also returns `true`, but `InputSourceAdapter` never implements the required `RETRO_DEVICE_ID_JOYPAD_MASK` (`id == 256`) query. A core that trusts these success returns can therefore read uninitialized output data or switch to bitmask input and receive zero input. fileciteturn8file0L17-L24 fileciteturn13file0L65-L72 citeturn206043search0turn871044search0

**Fix:** Fill outputs, or return `false` until the feature is really implemented.

```kotlin
RetroEnv.GET_LIBRETRO_PATH -> {
    data.reinterpret(8L).set(ValueLayout.ADDRESS, 0, corePathSeg)
    true
}
RetroEnv.GET_AUDIO_VIDEO_ENABLE -> {
    data.reinterpret(4L).set(ValueLayout.JAVA_INT, 0, 0x1 or 0x2)
    true
}
RetroEnv.GET_INPUT_BITMASKS -> false // until id == 256 is implemented
```

If bitmask support is enabled, synthesize the 16-bit RetroPad mask in `InputSourceAdapter.poll()` when `id == 256`.

### 5. [HIGH] Desktop HW-render readback discards the core's per-frame dimensions and decodes 32-bit pixels using the software format

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`onVideo`, `callRunHwFrame`); `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/impl/FfmCoreController.kt` (`runFrame`, `dispatchVideo`)

**Problem:** During HW rendering, `onVideo()` discards every video callback, including the width/height supplied for the presented frame. `FfmCoreController.runFrame()` then reads back the entire `maxWidth x maxHeight` FBO every frame and forwards those maximum dimensions. The readback is BGRA/32-bit, but `dispatchVideo()` chooses the framebuffer format from the core's software `pixelFormat`, which can still be 0RGB1555 or RGB565. Variable-resolution HW cores can therefore produce oversized frames, stale regions, and 4-byte pixels decoded as 2-byte pixels. fileciteturn8file0L103-L133 fileciteturn5file2L180-L193 fileciteturn5file2L261-L282

**Fix:** Record the HW callback's frame dimensions/validity and send readback through a dedicated XRGB8888 path.

```kotlin
private var hwFrameW = 0
private var hwFrameH = 0
private var hwFrameValid = false

fun onVideo(data: MemorySegment, width: Int, height: Int, pitch: Long) {
    if (hwRender.isActive) {
        hwFrameValid = data.address() != 0L
        if (hwFrameValid) { hwFrameW = width; hwFrameH = height }
        return
    }
    // software path...
}

// after retro_run:
if (hwFrameValid) {
    val pixels = hwRender.readPixels(hwFrameW, hwFrameH) ?: return
    dispatchHardwareFrame(pixels, hwFrameW, hwFrameH, PixelFormat.XRGB8888)
}
```

### 6. [HIGH] HW-render bridge accepts GLES and arbitrary GL-core requests but always creates one OpenGL 3.2 core context

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/HwRenderBridge.kt` (`handleRequest`); `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/GlContext.kt` (`create`)

**Problem:** `handleRequest()` returns `true` for legacy OpenGL, OpenGL Core, GLES2, GLES3, and versioned GLES. `GlContext.create()` always requests a desktop OpenGL 3.2 core profile and ignores the requested major/minor version. A GLES core, legacy fixed-function GL core, or GL-core core requiring a later version is therefore told its requested context was provided when it was not. fileciteturn6file4L628-L646 fileciteturn7file0L123-L134

**Fix:** Only accept contexts the frontend can actually construct and honor requested versions.

```kotlin
when (ctxType) {
    HW_CONTEXT_OPENGL_CORE -> {
        if (!GlContext.supportsCoreVersion(major, minor)) return false
        gl = GlContext.createCore(major, minor)
    }
    HW_CONTEXT_OPENGL -> gl = GlContext.createCompatibility()
    else -> return false // GLES needs EGL/GLES, not desktop GL
}

```

### 7. [HIGH] iOS ignores `retro_system_info.need_fullpath` and forces every ROM through the 512 MiB memory path

**File:** `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`loadCore`, `loadGame`)

**Problem:** `loadCore()` reads only the three string pointers from `retro_system_info` and always returns `needFullpath=false`, `blockExtract=false`. `loadGame()` then always allocates a full native copy of the ROM and rejects anything above 512 MiB. Libretro cores with `need_fullpath=true` require a valid path and expect `data/size` to be null/zero; disc-image cores can also legitimately exceed 512 MiB. The current code therefore violates the core's declared content-loading contract and makes large full-path content unloadable. fileciteturn8file1L345-L362 fileciteturn8file1L381-L424

**Fix:** Retain the two boolean fields and choose path-only versus in-memory loading accordingly.

```kotlin
private var needFullPath = false
private var blockExtract = false

// retro_system_info: 3 pointers then bool,bool on arm64
val bytes = buf.reinterpret<ByteVar>()
needFullPath = bytes[24].toInt() != 0
blockExtract = bytes[25].toInt() != 0

if (needFullPath) {
    info.reinterpret<CPointerVar<ByteVar>>()[0] = pathPtr
    info.reinterpret<CPointerVar<ByteVar>>()[1] = null
    info.reinterpret<LongVar>()[2] = 0L
} else {
    // bounded in-memory content path
}
```

### 8. [MEDIUM] iOS AV-info buffer is not zeroed before a call that may leave fields unspecified

**File:** `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`readSystemAvInfo`)

**Problem:** `readSystemAvInfo()` allocates 48 uninitialized bytes and immediately passes them to `retro_get_system_av_info()`. The libretro contract allows a core to leave optional fields such as `geometry.aspect_ratio` unset. The subsequent `if (aspect > 0f)` does not make uninitialized native memory safe; a stray positive float can be accepted as a bogus aspect ratio. The offsets themselves are correct. fileciteturn10file1L115-L134 citeturn921111search0

**Fix:** Zero the struct before the core fills it.

```kotlin
private fun readSystemAvInfo(): AvInfo = memScoped {
    val buf = allocArray<ByteVar>(48)
    memset(buf, 0, 48u)
    requireSymbol("retro_get_system_av_info")
        .reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()
        .invoke(buf)
    // existing offsets are then safe
    ...
}
```

### 9. [HIGH] iOS media callback function-pointer signatures still do not match the libretro ABI

**File:** `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`videoCb`, `audioBatchCb`, `audioSampleCb`)

**Problem:** The 0-argument callback mismatch was fixed, but other callbacks remain ABI-incompatible. `retro_video_refresh_t` uses `unsigned, unsigned, size_t` while `videoCb` takes three Kotlin `Int`s; on arm64 `size_t pitch` is 64-bit. `retro_audio_sample_batch_t` takes and returns `size_t`, while `audioBatchCb` takes/returns `Int`. `retro_audio_sample_t` takes two `int16_t`, while `audioSampleCb` uses two `Int`s. Calling a native function pointer through a different signature is undefined at the ABI boundary and can truncate pitch/frame counts or corrupt calls. fileciteturn2file2L351-L383 citeturn921111search0turn921111search3

**Fix:** Give the top-level functions the C-width types and pass typed function references to `staticCFunction`.

```kotlin
private fun videoImpl(
    data: COpaquePointer?, width: UInt, height: UInt, pitch: ULong
) { /* ... */ }

private fun audioBatchImpl(
    data: CPointer<ShortVar>?, frames: ULong
): ULong { /* ... */ return frames }

private fun audioSampleImpl(left: Short, right: Short) { /* ... */ }

internal val videoCb = staticCFunction(::videoImpl)
internal val audioBatchCb = staticCFunction(::audioBatchImpl)
internal val audioSampleCb = staticCFunction(::audioSampleImpl)
```

Prefer the platform cinterop `size_t` mapping if exposed rather than spelling `ULong` manually.

### 10. [HIGH] Android and iOS raw environment-command handlers still use wrong IDs and false-success semantics

**File:** `core-libretro/src/androidMain/kotlin/com/omilator/core/libretro/impl/JniCoreController.kt` (`onEnvironment`); `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`envCb`, `handleEnv`)

**Problem:** Android returns `true` for raw commands `0,9,10,19,51`; iOS returns `true` for `0,9,19,31,51,69`. Command 0 is not a public environment command, and `GET_INPUT_BITMASKS` is `51 | 0x10000`, not raw 51. Commands 9/19/31 are output-pointer queries, but neither platform writes the required pointer before returning success. On iOS, 69 is the core-options update-display callback registration; returning `true` without retaining the callback tells the core a facility exists when it does not. The desktop table is fixed; these mobile hard-coded paths are not. fileciteturn5file3L336-L348 fileciteturn8file1L485-L494 fileciteturn2file2L329-L349 citeturn871044search0turn871044search4

**Fix:** Share named constants (including the experimental bit) and only return success after satisfying the command.

```kotlin
private const val EXPERIMENTAL = 0x10000
private const val GET_SYSTEM_DIRECTORY = 9
private const val SET_PIXEL_FORMAT = 10
private const val GET_LIBRETRO_PATH = 19
private const val GET_SAVE_DIRECTORY = 31
private const val GET_INPUT_BITMASKS = 51 or EXPERIMENTAL
private const val SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK = 69

return when (cmd) {
    SET_PIXEL_FORMAT -> readPixelFormat(data)
    GET_SYSTEM_DIRECTORY -> writeStringPointer(data, systemDir)
    GET_LIBRETRO_PATH -> writeStringPointer(data, loadedCorePath)
    GET_SAVE_DIRECTORY -> writeStringPointer(data, saveDir)
    GET_INPUT_BITMASKS -> false // until id=256 is implemented
    SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK -> false // until retained/invoked
    else -> false
}
```

### 11. [HIGH] Core downloaders now select platforms, but generate non-existent buildbot paths/artifact names

**File:** `data-library/src/desktopMain/kotlin/com/omilator/data/library/CoreDownloader.kt` (`platform`, `download`); `data-library/src/androidMain/kotlin/com/omilator/data/library/AndroidCoreDownloader.kt` (`download`); `data-library/src/iosMain/kotlin/com/omilator/data/library/IosCoreDownloader.kt` (`cores`, `download`)

**Problem:** Desktop platform selection is no longer macOS-only, but `download()` builds names like `mgba_libretro_dylib.zip`, `mgba_libretro_so.zip`, and `mgba_libretro_dll.zip`; the current buildbot publishes `mgba_libretro.dylib.zip`, `.so.zip`, and `.dll.zip`. Android hard-codes the old `/android/arm64-v8a/latest` layout and filenames such as `mgba_libretro.so.zip`; the current buildbot is under `/android/latest/<abi>/` and most artifacts are named `*_libretro_android.so.zip`. It also cannot download x86_64 cores for an x86_64 emulator/device. On current iOS arm64 builds, mGBA is an exception published as `mgba_libretro.dylib.zip`, while the source asks for `mgba_libretro_ios.dylib.zip`. fileciteturn8file4L983-L1019 fileciteturn13file3L397-L450 fileciteturn13file4L533-L565 fileciteturn13file4L592-L605 citeturn875291search1turn875291search2turn508975search0turn508975search2turn508975search6

**Fix:** Model the exact remote artifact stem separately from the installed name, and select Android ABI at runtime.

```kotlin
// desktop
val zipUrl = "$buildbotBase/${entry.name}.${platform.coreExt}.zip"

// Android
val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
    ?: error("Unsupported ABI")
val base = "https://buildbot.libretro.com/nightly/android/latest/$abi"
val remote = "${entry.urlName}_android.so.zip"

// iOS special case
CoreEntry("mgba", "GB / GBC / GBA", "mgba_libretro")
```

Use per-core `remoteArtifact` overrides for buildbot exceptions instead of inferring every name.

### 12. [HIGH] iOS picker can delete the user's selected item when source and destination are the same path

**File:** `ui-shared/src/iosMain/kotlin/com/omilator/ui/IosFilePicker.kt` (`PickerDelegate.documentPicker`)

**Problem:** The security-scoped fix copies every selection to `Documents/<lastPathComponent>`. Before copying, it unconditionally removes any existing destination. If the user selects a file or folder already located at that exact Documents path, `source == destination`: Omilator deletes the selected item, then attempts to copy the now-deleted source. Name collisions with a different existing imported item are also destructively overwritten before a successful replacement is guaranteed. fileciteturn13file1L132-L162

**Fix:** Detect same-path selections and copy collisions to a unique/temp destination before any replacement.

```kotlin
val src = url.path ?: return onPicked(null)
val dest = "$docs/${url.lastPathComponent}"

if (src == dest) {
    onPicked(src)
    return
}

val finalDest = uniqueImportPath(dest)
val copied = fm.copyItemAtPath(src, toPath = finalDest, error = null)
onPicked(if (copied) finalDest else null)
```

Never delete the existing destination until a complete replacement copy exists.

### 13. [MEDIUM] iOS audio can leak pending-buffer reservations and `stop()` does not generation-invalidate old callbacks

**File:** `core-audio/src/iosMain/kotlin/com/omilator/core/audio/IosAudioOutput.kt` (`write`, `stop`)

**Problem:** `write()` increments `pendingBuffers` before validating `frames`, creating `AVAudioPCMBuffer`, and obtaining `floatChannelData`. Any early return after the reservation permanently consumes one slot. Separately, `stop()` resets `pendingBuffers` outside `pendingLock` and does not increment `generation`; completion callbacks from the old node can therefore race a subsequent `configure()` and decrement the new session's counter. The `flush()` generation logic itself is corrected. fileciteturn13file2L284-L334 fileciteturn13file2L337-L370

**Fix:** Validate/allocate before reserving where possible, release reservations on every failure path, and invalidate the generation under the lock during stop/reconfigure.

```kotlin
private fun invalidatePending() = pendingLock.withLock {
    generation++
    pendingBuffers = 0
}

private fun stop() {
    invalidatePending()
    playerNode?.stop()
    engine?.stop()
    // deactivate session...
}

// In write(), if anything fails after pendingBuffers++:
pendingLock.withLock {
    if (myGeneration == generation)
        pendingBuffers = (pendingBuffers - 1).coerceAtLeast(0)
}
```

### 14. [MEDIUM] iOS ROM allocation leaks on `fopen`/short-read failures

**File:** `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`loadGame`, `freeGameInfo`)

**Problem:** `loadGame()` allocates `dataBuf` before `fopen()`, but does not assign it to `gameDataPtr` until after the file was completely read. If `fopen()` fails or `fread()` is short, an exception exits while `freeGameInfo()` has no reference to the large allocation, leaking up to the 512 MiB cap. fileciteturn8file1L364-L379 fileciteturn8file1L381-L424

**Fix:** Transfer ownership immediately and clean up all retained allocations on any failure.

```kotlin
val dataBuf = nativeHeap.allocArray<ByteVar>(romSize)
gameDataPtr = dataBuf
try {
    val fp = fopen(romPath, "rb") ?: error("Cannot open ROM: $romPath")
    try {
        val got = fread(dataBuf, 1u, romSize.toULong(), fp)
        check(got == romSize.toULong()) { "Short read on ROM: $romPath" }
    } finally {
        fclose(fp)
    }
    check(retroLoadGame(info))
} catch (t: Throwable) {
    freeGameInfo()
    throw t
}
```

### 15. [HIGH] Desktop SRAM filenames collide across games and writes are not crash-safe

**File:** `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`sramFile`, `flushSram`, `restoreSram`)

**Problem:** The new desktop SRAM persistence uses only `romPath.nameWithoutExtension` as the save filename. Two different games with the same basename in different directories—or different systems—share one `.srm`, so launching one can restore another game's battery RAM and later overwrite it. `flushSram()` also writes directly to the live `.srm`; a crash or disk error during the write can corrupt the only durable battery save. This is separate from the documented deliberate limit that SRAM is desktop-only. fileciteturn8file2L651-L670

**Fix:** Derive a stable per-game key from the canonical path (or library game ID) and atomically replace the SRAM file.

```kotlin
private fun sramFile(): File {
    val canonical = File(romPath).canonicalPath
    val id = sha256(canonical.encodeToByteArray()).take(16)
    val base = File(romPath).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(saveDir, "$base-$id.srm")
}

private fun flushSram() {
    val data = controller.readSaveRam()
    if (data.isEmpty()) return
    val dst = sramFile().toPath()
    val tmp = dst.resolveSibling(".${dst.fileName}.tmp")
    Files.write(tmp, data)
    Files.move(tmp, dst, REPLACE_EXISTING, ATOMIC_MOVE)
}
```

### 16. [MEDIUM] Desktop analog input stores four axes correctly but queries them with the wrong libretro index/id mapping

**File:** `core-input/src/desktopMain/kotlin/com/omilator/core/input/GamepadPoller.kt` (`poll`); `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt` (`InputSourceAdapter.poll`)

**Problem:** `GamepadPoller` stores `[leftX,leftY,rightX,rightY]` at indices 0..3. Libretro analog polling encodes the stick in `index` (`LEFT=0`, `RIGHT=1`) and the axis in `id` (`X=0`, `Y=1`). `InputSourceAdapter` ignores `id` and returns `holder.analog(index)`, so left-Y returns left-X, right-X/Y return left-Y, and analog-button queries can return a stick axis. fileciteturn12file0L65-L68 fileciteturn13file0L65-L72

**Fix:** Map `(index,id)` to the four stored axes.

```kotlin
InputDevice.ANALOG -> when (index) {
    0, 1 -> when (id) {
        0, 1 -> holder.analog(index * 2 + id)
        else -> 0
    }
    2 -> 0 // RETRO_DEVICE_INDEX_ANALOG_BUTTON: implement separately if desired
    else -> 0
}
```

### 17. [HIGH] Windows/Linux desktop still route through macOS-only standalone-emulator code

**File:** `app-desktop/src/desktopMain/kotlin/com/omilator/app/Main.kt` (`playRom`, first-run setup); `data-launcher/src/desktopMain/kotlin/com/omilator/data/launcher/EmulatorInstaller.kt` (`install`, `findAppBundle`); `data-launcher/src/desktopMain/kotlin/com/omilator/data/launcher/backends/MacosAppLauncher.kt` (`findApp`, `launchOpenA`, `launchBinary`)

**Problem:** `playRom()` applies the macOS-specific PSP/GameCube/Wii HW-render block on every desktop OS, with no OS check. The fallback standalone stack is itself entirely macOS-specific: it searches `.app` bundles in `/Applications`/`~/Applications`, downloads macOS release assets, and launches with `open -a`. On Windows/Linux, affected ROMs are therefore prevented from taking the libretro path and then routed into a launcher that cannot work on that platform. First-run setup likewise attempts to install macOS applications on non-macOS desktops. fileciteturn15file0L17-L66 fileciteturn15file1L134-L206 fileciteturn15file1L208-L245 fileciteturn15file2L322-L373

**Fix:** Gate the macOS workaround and macOS installer by platform, or split launcher implementations per desktop OS.

```kotlin
private val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)

private fun playRom(romPath: String, useLibretro: (String) -> Unit) {
    if (!isMac) {
        useLibretro(romPath)
        return
    }
    // existing macOS-only HW-render routing
}

// Also do not expose/run EmulatorInstaller on Windows/Linux until
// Windows/Linux installer + launcher implementations exist.
```

### 18. [MEDIUM] Atomic settings persistence exists but desktop/Android production wiring bypasses it

**File:** `app-desktop/src/desktopMain/kotlin/com/omilator/app/Main.kt` (`main`); `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt` (`onCreate`); `data-settings/src/desktopMain/kotlin/com/omilator/data/settings/DesktopSettingsPersistence.kt` (`write`, `settingsStore`); `data-settings/src/iosMain/kotlin/com/omilator/data/settings/IosSettingsPersistence.kt` (`write`)

**Problem:** `DesktopSettingsPersistence.write()` correctly implements temp-file + atomic move, but `Main.kt` never uses `DesktopSettingsPersistence`; it constructs `SettingsStore` with `File.writeText`, restoring the truncate-in-place failure mode. Android does the same. iOS does use its temp-file writer, but it ignores both the `fwrite()` byte count and `rename()` return code, so an I/O failure can still promote a short temp file or silently fail replacement. The shared `updateAppSettings` mutex is correct; the defect is the persistence wiring below it. fileciteturn14file2L453-L486 fileciteturn14file1L252-L268 fileciteturn14file3L541-L547 fileciteturn6file1L207-L233

**Fix:** Route production stores through atomic persistence on every platform and verify native I/O results.

```kotlin
// Desktop Main.kt
val persistence = remember { DesktopSettingsPersistence(configDir) }
val settingsStore = remember { persistence.settingsStore() }

// Android: equivalent temp + fsync + rename helper
writeText = { path, content -> atomicWriteText(File(path), content) }
```

For iOS:

```kotlin
val written = fwrite(buf, 1u, bytes.size.toULong(), fp)
check(written == bytes.size.toULong()) { "Short settings write" }
check(rename(tmpPath, path) == 0) { "Settings replace failed" }
```

[AUDIT-CHATGPT-2.md](sandbox:/mnt/data/AUDIT-CHATGPT-2.md)
