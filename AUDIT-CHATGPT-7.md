# Omilator Audit — post-`94e77bf` follow-up

Audited current `main` after the 2026-10-04 14-finding fix batch (`94e77bf`) and the follow-up bookkeeping commit (`e35c5fb`).

Explicitly excluded from re-reporting:
- all 14 findings fixed in `94e77bf`
- Android core-download coverage for `beetle_psx_hw`, `play`, `azahar`, `dolphin`
- Linux/Windows XDG / `%APPDATA%` path migration

The findings below are additional issues observed in current `main`.

---

## 1. High — Android JNI core state is process-global, so two controller instances can corrupt each other

### Where
- `core-libretro/src/androidMain/.../JniCoreController.kt`
- native `libretro_jni.cpp`

### Problem
The Kotlin API constructs a `JniCoreController` per player session, but the JNI implementation stores the active core and callback target in global state (`g_state`, `g_controller_ref`).

That means the correctness of the controller depends on there never being two live instances at once. A second `loadCore()` overwrites global native state belonging to the first instance. The first controller may then run/unload the second controller's core, receive callbacks intended for the second controller, or clear shared pointers while the other session is still alive.

This is not only theoretical: Compose recreation, overlapping teardown/startup, tests, or future background/preload work can create overlapping instances.

### Fix
Move native state behind an opaque per-controller handle.

Kotlin:

```kotlin
private var nativeHandle: Long = 0

override suspend fun loadCore(path: String): SystemInfo {
    check(nativeHandle == 0L)
    nativeHandle = createNativeState(this)
    setEnvPathsNative(nativeHandle, systemDirectory, systemDirectory, path)
    if (!loadCoreNative(nativeHandle, path)) {
        destroyNativeState(nativeHandle)
        nativeHandle = 0
        error("Failed to load core: $path")
    }
    ...
}

override fun runFrame() = runFrameNative(nativeHandle)

override fun unloadCore() {
    val h = nativeHandle
    if (h == 0L) return
    deinitNative(h)
    destroyNativeState(h)
    nativeHandle = 0
    loaded = false
}
```

C++:

```cpp
struct CoreState {
    jobject controller_ref = nullptr;
    void* handle = nullptr;
    ...
};

static CoreState* state(jlong h) {
    return reinterpret_cast<CoreState*>(h);
}
```

Pass the handle to every JNI call and store the owning controller's global ref inside that state. Avoid any mutable process-global core state except the `JavaVM*`.

Add a test that creates two controllers with a fake core, loads both, and verifies callbacks/run/unload remain isolated.

---

## 2. High — Android libretro environment support is too incomplete for many real cores

### Where
- `core-libretro/src/androidMain/.../JniCoreController.kt`

### Problem
Android currently handles only:
- `SET_PIXEL_FORMAT`
- `GET_INPUT_BITMASKS` (declined)
- the three pointer-valued directory/path queries in native C++

It declines core-options negotiation, variable access, input descriptors, logging, rumble, audio/video enable, target refresh, and other common environment commands that the desktop/iOS implementations already support.

As a result, cores that work on desktop/iOS can initialize with missing defaults/features or fail outright on Android. This is separate from the recorded "missing Android core downloads" follow-up: even already-downloadable cores can be affected.

### Fix
Bring Android's environment implementation to parity with the supported non-HW subset of desktop.

At minimum implement:
- `GET_CORE_OPTIONS_VERSION`
- `SET_CORE_OPTIONS`
- `SET_CORE_OPTIONS_INTL`
- `SET_VARIABLES`
- `GET_VARIABLE`
- `GET_VARIABLE_UPDATE`
- `SET_INPUT_DESCRIPTORS`
- `GET_AUDIO_VIDEO_ENABLE`
- `GET_LOG_INTERFACE` if a safe JNI/native logger bridge is available
- `GET_RUMBLE_INTERFACE` if exposed by UI/platform, otherwise decline honestly

Do not duplicate parsing logic if avoidable. Extract the semantic option state into common Kotlin types and keep only pointer decoding/encoding platform-specific.

Add an Android native smoke core that probes each supported command and asserts the expected writes/return values.

---

## 3. High — Android save RAM is still effectively unimplemented

### Where
- `core-libretro/src/androidMain/.../JniCoreController.kt`
- `MobilePlayerScreen.kt`

### Problem
`JniCoreController` reports:

```kotlin
override val memorySize: UInt = 0u
override fun readMemory(...) = ByteArray(...)
override fun writeMemory(...) {}
```

and does not override `readSaveRam()` / `writeSaveRam()` with real libretro memory access.

`MobilePlayerScreen` also never restores or flushes battery-backed RAM.

Therefore games using `RETRO_MEMORY_SAVE_RAM` lose in-game saves when the mobile session ends, even though desktop has a working SRAM round-trip.

### Fix
Add JNI wrappers for:

```c
retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)
retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)
```

Then implement:

```kotlin
override fun readSaveRam(): ByteArray = readSaveRamNative()
override fun writeSaveRam(data: ByteArray) = writeSaveRamNative(data)
```

Persist SRAM on mobile with the same stable per-ROM identity policy used on desktop. Prefer a shared helper for:
- sanitized basename
- canonical/stable ROM identity
- atomic replacement

For SAF ROMs, hash the original URI identity rather than only the temporary materialized cache filename.

Restore SRAM after `loadGame()` and flush before `unloadGame()`.

---

## 4. High — server page configuration is frozen at first composition

### Where
- `app-desktop/src/desktopMain/.../Main.kt`

### Problem
`serverPage` is created with:

```kotlin
val serverPage = remember<(@Composable () -> Unit)?> {
    if (settingsViewModel.state.value.libtecaServerUrl.isNotBlank()) { ... } else null
}
```

Because the `remember` has no keys and reads `.state.value` outside a collected Compose state, it never updates when the user adds, changes, or removes the server URL/token.

Effects:
- configuring a server after startup does not add the Server tab
- removing the server does not remove the tab
- changing credentials keeps the old page/view-model lifecycle behavior

### Fix
Collect settings state in Compose and derive the server page from the current values.

Example:

```kotlin
val settingsState by settingsViewModel.state.collectAsState()
val serverConfigured =
    settingsState.libtecaServerUrl.isNotBlank() &&
    settingsState.libtecaServerToken.isNotBlank()

val serverViewModel = remember(
    settingsState.libtecaServerUrl,
    settingsState.libtecaServerToken,
) {
    ServerLibraryViewModel {
        if (!serverConfigured) null
        else LibtecaServerConnection(
            settingsState.libtecaServerUrl.trim(),
            settingsState.libtecaServerToken.trim(),
            File(configDir, "rom-cache"),
        )
    }
}

val serverPage: (@Composable () -> Unit)? =
    if (serverConfigured) {
        { ServerLibrarySection(serverViewModel, onPlayGame = ...) }
    } else null
```

Also clear server-library state when configuration changes so games/download state from the old server are not shown under the new credentials.

---

## 5. High — server playtime reporting always sends zero seconds

### Where
- `app-desktop/src/desktopMain/.../Main.kt`

### Problem
On server-game launch:

```kotlin
playRom(file.absolutePath) { romPath -> playing = romPath }
serverViewModel.reportPlaytime(game, 0)
```

The progress endpoint is called immediately with `0`, and no later session-duration report is tied to player exit.

So the shipped "playtime reporting" integration does not report actual playtime.

### Fix
Represent the playing session as structured state, not just a ROM path:

```kotlin
data class PlayingSession(
    val romPath: String,
    val serverGame: ServerGame? = null,
    val startedAtNanos: Long = System.nanoTime(),
)
```

On exit:

```kotlin
val session = playing ?: return
val seconds = ((System.nanoTime() - session.startedAtNanos) / 1_000_000_000L)
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()

session.serverGame?.let { serverViewModel.reportPlaytime(it, seconds) }
playing = null
```

If the server's contract expects cumulative position instead of session duration, first fetch/retain existing progress and add the elapsed session duration accordingly.

---

## 6. Medium/High — `LibtecaServerConnection.listGames()` makes one work-detail request per edition

### Where
- `app-desktop/src/desktopMain/.../LibtecaServerConnection.kt`

### Problem
Inside each work:

```kotlin
for (ed in work.editions) {
    val detail = source.work(work.id)
    ...
}
```

If a work has multiple game editions, the same detail endpoint is fetched repeatedly. On a large library this creates avoidable N×E HTTP traffic and makes refresh much slower.

### Fix
Fetch detail once per work:

```kotlin
for (work in works) {
    val detail = runCatching { source.work(work.id) }.getOrNull() ?: continue
    val detailByEdition = detail.editions.associateBy { it.id }

    for (ed in work.editions) {
        if (!ed.format.startsWith("game-")) continue
        val file = detailByEdition[ed.id]?.files?.firstOrNull() ?: continue
        ...
    }
}
```

If the server list endpoint can expose file IDs/sizes directly, prefer extending/using that contract and eliminate detail fan-out entirely.

---

## 7. Medium/High — server refreshes can race and stale results can overwrite newer configuration/results

### Where
- `ServerLibraryViewModel` in `ServerLibrarySection.kt`

### Problem
Every `refresh()` launches a new IO coroutine. There is no cancellation or generation check.

If refresh A is slow and refresh B starts later, B can finish first and publish fresh games, then A can finish and overwrite the state with stale results or stale credentials.

This becomes especially visible once server settings are made reactive.

### Fix
Track and cancel the active refresh job, or use a monotonically increasing generation.

```kotlin
private var refreshJob: Job? = null

fun refresh() {
    refreshJob?.cancel()
    val generation = ++refreshGeneration
    _state.update { it.copy(isLoading = true, error = null) }

    refreshJob = scope.launch(Dispatchers.IO) {
        val result = runCatching {
            connect()?.listGames() ?: error("Not configured")
        }

        withContext(Dispatchers.Main) {
            if (generation != refreshGeneration) return@withContext

            result.fold(
                onSuccess = { games ->
                    _state.update { s ->
                        s.copy(
                            isLoading = false,
                            games = games,
                            selectedSystem =
                                s.selectedSystem?.takeIf { sys ->
                                    games.any { it.system == sys }
                                },
                        )
                    }
                },
                onFailure = { ... },
            )
        }
    }
}
```

Also expose `close()`/`dispose()` to cancel the private scope when the owning screen/view-model is discarded.

---

## 8. Medium/High — local library scans can race and stale scans can re-add removed directories

### Where
- `ui-shared/src/commonMain/.../LibraryViewModel.kt`

### Problem
`addDirectory`, `removeDirectory`, and manual `rescan()` each launch independent scans.

Scenario:
1. scan of directories `[A, B]` starts
2. user removes `B`; scan of `[A]` starts
3. `[A]` finishes first and publishes correct state
4. old `[A, B]` scan finishes later and republishes games from removed `B`

The directory list in UI remains `[A]`, but the games list is stale.

### Fix
Cancel the previous scan job or tag requests with a generation.

```kotlin
private var scanJob: Job? = null
private var scanGeneration = 0L

fun rescan(directories: List<String>) {
    val generation = ++scanGeneration
    scanJob?.cancel()

    if (directories.isEmpty()) {
        ...
        return
    }

    scanJob = scope.launch {
        _state.update { it.copy(isLoading = true, error = null) }

        val result = runCatching {
            repository.rescan(directories)
        }

        if (generation != scanGeneration) return@launch

        ...
    }
}
```

Since `LibraryRepository.cached` is mutable shared state, either serialize scans in the view-model or make repository scanning return a value without mutating shared cache until the winning generation commits.

---

## 9. Medium/High — first-run desktop setup performs blocking network/process work on the UI dispatcher

### Where
- `app-desktop/src/desktopMain/.../Main.kt`

### Problem
`LaunchedEffect(Unit)` runs on the Compose/main dispatcher, but calls synchronous:
- `coreDownloader.download(...)`
- `emulatorInstaller.install(...)`

Those operations perform network I/O, ZIP extraction, and process work. The first-run setup dialog can freeze and stop repainting while setup runs.

### Fix
Move blocking operations to `Dispatchers.IO`, and marshal only state updates back through Compose state.

For example:

```kotlin
LaunchedEffect(Unit) {
    if (!setupNeeded) return@LaunchedEffect

    withContext(Dispatchers.IO) {
        for (entry in coreDownloader.cores) {
            ...
            val ok = coreDownloader.download(entry) { msg ->
                ...
            }
            ...
        }
    }
}
```

Cleaner: make download/install APIs `suspend` and perform their blocking sections internally with `withContext(Dispatchers.IO)`.

---

## 10. Medium — desktop and Android core downloads install directly to the live core path

### Where
- `data-library/src/desktopMain/.../CoreDownloader.kt`
- `data-library/src/androidMain/.../AndroidCoreDownloader.kt`

### Problem
Both downloaders stream ZIP contents directly into the final `.dylib`/`.so`/`.dll` file.

If the process is interrupted, storage fills, the network read fails, or extraction throws, a truncated core remains at the final filename.

On the next run:

```kotlin
if (existing.exists()) return true
```

treats that corrupt/truncated core as successfully installed forever.

### Fix
Extract to a temporary sibling, validate non-empty output, then atomically rename.

```kotlin
val final = File(targetDir, libName)
val tmp = File(targetDir, ".$libName.part")
tmp.delete()

tmp.outputStream().use { output ->
    zis.copyTo(output)
}

require(tmp.length() > 0) {
    "empty core archive member"
}

try {
    Files.move(
        tmp.toPath(),
        final.toPath(),
        REPLACE_EXISTING,
        ATOMIC_MOVE,
    )
} catch (_: AtomicMoveNotSupportedException) {
    Files.move(
        tmp.toPath(),
        final.toPath(),
        REPLACE_EXISTING,
    )
}
```

In the catch/finally path, delete the temp file.

`isInstalled()` should ideally do more than `exists()`: at least require non-zero length, or persist a small successful-install manifest/hash.

---

## 11. Medium — desktop core ZIP extraction can install the wrong shared library

### Where
- `data-library/src/desktopMain/.../CoreDownloader.kt`

### Problem
The downloader accepts the first archive member ending in the platform extension:

```kotlin
if (entry2.name.endsWith(".${platform.coreExt}")) {
    val outFile = File(targetDir, File(entry2.name).name)
    ...
    return true
}
```

It does not require the member to match the requested core name.

If an archive ever contains helper libraries or multiple native libraries, it can report success while never creating the exact path checked by `isInstalled(entry)`.

### Fix
Require an exact basename:

```kotlin
val expected = "${entry.name}.${platform.coreExt}"

...

if (File(entry2.name).name == expected) {
    ...
}
```

After extraction, assert `final.name == expected` and `final.isFile`.

Mirror the same principle on Android: require the exact expected `_android.so` archive member rather than the first `.so`.

---

## 12. Medium — desktop run-ahead publishes the speculative frame after rolling core state back

### Where
- `ui-shared/src/desktopMain/.../PlayerEngine.kt`

### Problem
The frame sequence is:

1. run real frame → video callback stores real frame in `latestFrame`
2. serialize real state
3. run speculative frame → video callback overwrites `latestFrame` with speculative frame
4. load serialized real state

The displayed frame is therefore one frame ahead while the core state has been rolled back.

That can be intentional for run-ahead, but only if the implementation deliberately keeps input/audio/video semantics coherent. Here there is no separate speculative video channel and no guard for cores that mutate external state during `retro_run`; failures are silently swallowed.

The more concrete bug is that if `loadStateFromMemory(savedState)` returns `false`, the code ignores the return value and continues from speculative state while assuming rollback succeeded.

### Fix
At minimum check rollback success and disable run-ahead on failure:

```kotlin
val savedState = controller.saveStateToMemory()
if (savedState.isEmpty()) {
    runAhead = 0
    return
}

suppressAudio = true
try {
    controller.runFrame()
} finally {
    suppressAudio = false
}

if (!controller.loadStateFromMemory(savedState)) {
    runAhead = 0
    // optionally surface a one-time warning
}
```

Prefer an explicit `speculativeFrame` slot so normal and run-ahead frame ownership is obvious. Only publish the speculative frame after a successful rollback.

---

## 13. Medium — mobile player coroutine is launched from `remember { ... }` instead of a lifecycle effect

### Where
- `ui-shared/src/commonMain/.../MobilePlayerScreen.kt`

### Problem
The player starts side effects inside:

```kotlin
remember {
    scope.launch(Dispatchers.Default) {
        ...
    }
    true
}
```

This works accidentally because the remembered calculation runs once for that composition, but it is not tied declaratively to `romPath`, `corePath`, `coreController`, or `audioOutput`.

If