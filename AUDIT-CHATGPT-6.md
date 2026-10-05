# Omilator Audit — 2026-10-04

Repository audited: `omilator/omilator`  
Audited commit: `b156761f229141b9df22ae25efa8f6937bbf8118` (`main` as observed during the audit)

## Scope and confidence

This is a static code audit of the current repository, with extra attention to:

- libretro ABI correctness on Desktop / Android / iOS
- coroutine/threading and lifecycle handling
- file and settings persistence
- ROM scanning / system detection
- downloading and external-process launch paths
- CI and documentation consistency
- recent libteca server-library additions

The repository already contains several earlier audit passes and many of their findings have been fixed. This report tries not to restate closed findings unless the same area contains a new or still-active issue.

Severity:
- **Critical** — likely crash/data corruption/security boundary failure in ordinary use
- **High** — major functional failure, native memory corruption risk, or persistent data-loss risk
- **Medium** — incorrect behavior, portability/lifecycle defect, or important reliability issue
- **Low** — maintenance, performance, UX, or documentation weakness

---

# Executive summary

I found **14 actionable findings**:

- **2 High**
- **8 Medium**
- **4 Low**

The most important issue is in the Android JNI libretro environment bridge: commands such as `GET_SYSTEM_DIRECTORY` receive a `const char **`, but the JNI helper copies characters directly into that pointer slot instead of assigning a pointer to stable storage. That can overwrite adjacent native memory and hand the core an invalid pointer.

The second high-priority area is libretro full-path handling on Android/Desktop. Android always calls `retro_load_game()` with `data = NULL`, even for cores that declare `need_fullpath = false`, while the Kotlin controller reports `needFullpath = false` without actually reading it. Desktop likewise discards `need_fullpath` from `retro_get_system_info()`. This creates compatibility failures with cores that expect frontend-provided in-memory content or whose content access contract differs.

There are also several concrete correctness problems in scanning, download resumption, lifecycle ownership, and platform portability.

---

# Findings

## 1. HIGH — Android JNI writes strings into a `const char **` output slot instead of writing a pointer

### Affected files

- `core-libretro/src/androidMain/kotlin/com/omilator/core/libretro/impl/JniCoreController.kt`
- `app-android/src/androidMain/cpp/libretro_jni.cpp`

### Problem

The Android environment handler handles:

- `RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY`
- `RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY`
- `RETRO_ENVIRONMENT_GET_LIBRETRO_PATH`

by calling:

```kotlin
writeNativeString(dataPtr, systemDirectory)
```

The native implementation is:

```cpp
size_t len = strlen(chars) + 1;
memcpy(reinterpret_cast<void*>(ptr), chars, len);
```

But libretro passes these commands a pointer to a `const char *` output variable — effectively `const char **`.

So `dataPtr` points to an 8-byte pointer slot on arm64, not a writable character buffer.

For a string longer than pointer size, the `memcpy` overwrites memory beyond the output slot. Even a short string leaves the core interpreting the first bytes of text as a pointer address.

### Impact

Possible outcomes include:

- native memory corruption
- invalid pointer dereference inside a core
- crashes during `retro_init()` or `retro_load_game()`
- BIOS/system-directory lookups silently failing
- corrupted stack/heap state that appears unrelated to the environment callback

### Fix

Retain C strings in native-owned stable storage and write their addresses into the `const char **`.

A simple JNI-side approach is to retain `std::string`s in `CoreState`:

```cpp
struct CoreState {
    // ...
    std::string system_directory;
    std::string save_directory;
    std::string core_path;
};
```

Expose a helper that writes a pointer:

```cpp
JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_writeNativeCStringPointer(
    JNIEnv* env, jobject, jlong data_ptr, jstring valueJ) {

    if (data_ptr == 0 || valueJ == nullptr) return;

    const char* value = env->GetStringUTFChars(valueJ, nullptr);
    if (!value) return;

    // Better: select one of retained CoreState strings by enum/key instead of
    // replacing one shared buffer.
    g_state.temp_env_string = value;
    env->ReleaseStringUTFChars(valueJ, value);

    auto out = reinterpret_cast<const char**>(data_ptr);
    *out = g_state.temp_env_string.c_str();
}
```

However, a **single** temporary string is unsafe if a core retains more than one returned pointer. Prefer three retained strings initialized during `loadCoreNative` or explicit setter methods:

```cpp
g_state.system_directory = ...;
g_state.save_directory = ...;
g_state.core_path = ...;
```

Then:

```cpp
*reinterpret_cast<const char**>(data) = g_state.system_directory.c_str();
```

An even cleaner design is to move these environment commands fully into C++, avoiding a JNI round trip for pointer-valued outputs.

### Test

Add an Android native unit/instrumentation smoke core whose environment callback:

1. requests all three directory/path commands,
2. verifies the returned pointer is non-null,
3. verifies each string content,
4. requests the first value again after the others and verifies the first pointer still points to valid content.

---

## 2. HIGH — Android and Desktop do not correctly honor `retro_system_info.need_fullpath`

### Affected files

- `app-android/src/androidMain/cpp/libretro_jni.cpp`
- `core-libretro/src/androidMain/kotlin/com/omilator/core/libretro/impl/JniCoreController.kt`
- `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/impl/FfmCoreController.kt`
- `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt`

### Problem

Android builds this unconditionally:

```cpp
struct retro_game_info info{};
info.path = path;
info.data = nullptr;
info.size = 0;
```

and calls `retro_load_game(&info)`.

But libretro cores declare whether they require a full path through:

```c
retro_system_info.need_fullpath
```

For cores with `need_fullpath == false`, the frontend is expected to provide content in `data`/`size` when appropriate.

The Android controller also returns:

```kotlin
needFullpath = false
```

without reading the actual core flag.

Desktop similarly constructs its public `SystemInfo` with:

```kotlin
needFullpath = false,
blockExtract = false,
```

and `LibretroFfm.callSystemInfo()` returns only name/version/extensions, discarding the actual boolean fields.

### Impact

Core compatibility will vary unpredictably.

Likely symptoms:

- some cartridge cores fail to load content on Android
- cores that do not open files themselves receive only a path and no data
- archive/content behavior is incorrect because `block_extract` is also discarded
- callers receive false metadata about what a core supports

### Fix

Read the full `retro_system_info` on all platforms.

Android:

```cpp
JNIEXPORT jboolean JNICALL
...systemInfoNeedFullpathNative(...) {
    retro_system_info info{};
    g_state.retro_get_system_info(&info);
    return info.need_fullpath ? JNI_TRUE : JNI_FALSE;
}
```

Also expose valid extensions and `block_extract`.

Then load content according to the contract:

```cpp
retro_game_info info{};
info.path = path;

std::vector<uint8_t> content;

if (!system_info.need_fullpath) {
    content = read_file(path);
    info.data = content.data();
    info.size = content.size();
}

bool ok = g_state.retro_load_game(&info);
```

Keep the data alive for as long as the core may legally reference it (safest: until unload).

Desktop: expand the FFM system info return type:

```kotlin
data class FfmSystemInfo(
    val name: String,
    val version: String,
    val extensions: String,
    val needFullPath: Boolean,
    val blockExtract: Boolean,
)
```

Read the bool fields from the actual struct layout and populate `SystemInfo` honestly.

`callLoadGame()` should branch on the cached flag instead of always passing zero data.

### Test

Add two tiny test cores:

- one with `need_fullpath = true` that asserts `path != NULL` and `data == NULL`
- one with `need_fullpath = false` that asserts non-null `data` and correct `size`

Run against Desktop and Android.

---

## 3. MEDIUM — Android ROM core resolution disagrees with `GameSystem` detection and silently falls back to mGBA

### Affected file

`app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt`

### Problem

`coreNameForRom()` only handles a small subset:

```kotlin
"gba", "gb", "gbc", "sgb" -> "mgba_libretro"
"nes", "nez" -> "mesen_libretro"
"sfc", "smc" -> "snes9x_libretro"
"iso", "cso", "prx" -> "ppsspp_libretro"
"n64", "z64", "v64" -> "mupen64plus_next_libretro"
else -> "mgba_libretro"
```

But `GameSystem` recognizes many more extensions and has a `preferredCore` field.

This means a scanned ROM such as Genesis `.md`, DS `.nds`, Dreamcast `.gdi`, PS1 `.cue`, Saturn `.chd`, etc. can appear in the Android library and then launch using **mGBA**.

`.iso` is also globally ambiguous; `GameSystem.detectByExtension("iso")` currently prefers PlayStation, while Android hardcodes PPSSPP.

### Impact

Wrong core launches, confusing errors, and false reports of broken ROMs/cores.

### Fix

Centralize resolution.

For example:

```kotlin
fun coreNameForGame(game: Game): String =
    "${game.system.preferredCore}_libretro"
```

The UI callback currently sends only `path`. Change it to pass the `Game` object, or add a shared resolver that uses the same system detection strategy as the scanner.

Do **not** use an unrelated default core. For unknown/ambiguous content return an error:

```kotlin
return null
```

and show “No core mapping for this ROM”.

For ambiguous extensions such as `.iso`, prefer metadata/context over extension-only detection.

---

## 4. MEDIUM — `LibraryRepository` cache is overwritten once per directory during multi-directory scans

### Affected files

- `ui-shared/src/commonMain/kotlin/com/omilator/ui/library/LibraryViewModel.kt`
- `data-library/src/commonMain/kotlin/com/omilator/data/library/LibraryRepository.kt`

### Problem

`LibraryViewModel.rescan()` correctly accumulates:

```kotlin
for (dir in directories) {
    all += repository.rescan(dir)
}
```

but `LibraryRepository.rescan()` does:

```kotlin
cached = scanner.scan(directory)
return cached
```

After a scan of N directories, the UI receives all games via its own local `all`, but `repository.games()`, `gamesBySystem()`, and `search()` contain **only the final directory**.

### Impact

Any later feature using repository-level cached queries will see incomplete state. This is especially likely as library functionality expands.

### Fix

Either remove the internal cache entirely or let the repository own multi-directory scanning.

Recommended:

```kotlin
class LibraryRepository(
    private val scanner: LibraryScanner,
) {
    private var cached: List<Game> = emptyList()

    suspend fun rescan(directories: List<String>): List<Game> {
        cached = directories
            .flatMap { scanner.scan(it) }
            .distinctBy { it.id }
        return cached
    }
}
```

Then `LibraryViewModel` calls this once.

Also deduplicate by canonical/stable ID so nested configured directories cannot show the same game twice.

---

## 5. MEDIUM — Emptying the configured library directories leaves stale games visible

### Affected file

`ui-shared/src/commonMain/kotlin/com/omilator/ui/library/LibraryViewModel.kt`

### Problem

`rescan(directories)` immediately returns when the list is empty:

```kotlin
if (directories.isEmpty()) return
```

So removing the last configured directory updates `scannedDirectories`, but the previous `games` list remains intact.

### Impact

The UI displays games from directories the user explicitly removed until restart or another successful scan.

### Fix

Clear state:

```kotlin
fun rescan(directories: List<String>) {
    if (directories.isEmpty()) {
        _state.value = _state.value.copy(
            isLoading = false,
            games = emptyList(),
            error = null,
        )
        return
    }
    // ...
}
```

Also clear `selectedSystem` if it no longer exists.

---

## 6. MEDIUM — Libteca resume logic can leave stale trailing bytes when a server ignores Range

### Affected file

`data-library/src/desktopMain/kotlin/com/omilator/data/library/LibtecaLibrarySource.kt`

### Problem

When resuming an existing partial file:

```kotlin
if (conn.responseCode == 200 && have > 0) {
    have = 0
}
val out = java.io.RandomAccessFile(dst, "rw")
out.seek(have)
```

If the previous file is larger than the fresh 200-response payload, the code seeks to 0 but **never truncates the file**.

Example:

- old cache length: 100 MB
- server ignores Range and returns a new/current 80 MB object
- code overwrites bytes 0..80 MB
- stale bytes 80..100 MB remain

There is also no final size validation when `size > 0`.

### Impact

Corrupt cached ROMs that may still look “complete” to later code.

### Fix

When restarting:

```kotlin
val out = RandomAccessFile(dst, "rw")
if (conn.responseCode == 200 && have > 0) {
    have = 0
    out.setLength(0)
}
out.seek(have)
```

After transfer:

```kotlin
if (size > 0) {
    require(out.length() == size) {
        "download size mismatch: ${out.length()} != $size"
    }
}
```

Prefer writing to `<file>.part` and atomically rename only after validation.

Also validate `Content-Range` for 206 responses, including the returned start offset.

---

## 7. MEDIUM — `RandomAccessFile` can leak on exceptional Libteca downloads

### Affected file

`data-library/src/desktopMain/kotlin/com/omilator/data/library/LibtecaLibrarySource.kt`

### Problem

The file is opened:

```kotlin
val out = java.io.RandomAccessFile(dst, "rw")
```

and manually closed only after the read loop.

If `input.read`, `out.write`, or the progress callback throws, `out.close()` is skipped.

### Fix

Use `use`:

```kotlin
RandomAccessFile(dst, "rw").use { out ->
    // seek/truncate/read loop
}
```

Keep the connection `disconnect()` in the existing `finally`.

---

## 8. MEDIUM — Server library ViewModel mutates Compose-observed objects off the main thread and “re-emits” the same StateFlow value

### Affected file

`ui-shared/src/desktopMain/kotlin/com/omilator/ui/library/ServerLibrarySection.kt`

### Problem

`ServerLibraryViewModel` stores mutable fields directly on `ServerGame`:

```kotlin
game.downloadProgress = progress
game.localFile = file
```

from a `Dispatchers.IO` coroutine.

It then tries to trigger recomposition with:

```kotlin
_state.value = _state.value
```

A `MutableStateFlow` suppresses equal values. Assigning the same object/value is not a reliable emission mechanism.

If `ServerGame.downloadProgress` is plain mutable state rather than Compose snapshot state, progress UI can fail to refresh entirely. If it is Compose `mutableStateOf`, mutating it from IO threads is still a poor concurrency model.

The `downloading` mutable set is also read/written from callers/main and IO jobs without synchronization.

### Fix

Make server state immutable and update it atomically.

Example:

```kotlin
data class ServerGameUi(
    val game: ServerGame,
    val localFile: File? = null,
    val downloadProgress: Float? = null,
    val isDownloading: Boolean = false,
)
```

Then:

```kotlin
withContext(Dispatchers.Main) {
    _state.update { state ->
        state.copy(
            games = state.games.map {
                if (it.game.fileId == id)
                    it.copy(downloadProgress = progress)
                else it
            }
        )
    }
}
```

Or keep all ViewModel mutations on `Dispatchers.Main` and perform only network/file operations in `withContext(Dispatchers.IO)`.

Replace `mutableSetOf` with state-derived `isDownloading` or a mutex-protected set.

---

## 9. MEDIUM — Desktop paths are hardcoded to macOS `~/Library/Application Support` on every OS

### Affected files

- `data-settings/src/desktopMain/kotlin/com/omilator/data/settings/DesktopSettingsPersistence.kt`
- `ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt`
- likely desktop app setup code using the same convention

### Problem

The project claims Windows/Linux/macOS desktop support, but functions such as:

```kotlin
defaultConfigDir()
defaultSystemDir()
sramFile()
optionsFile()
```

construct:

```text
~/Library/Application Support/Omilator
```

on all operating systems.

### Impact

On Windows and Linux:

- config/save files go into a bizarre macOS-style folder
- Linux ignores XDG conventions
- Windows bypasses `%APPDATA%`/`%LOCALAPPDATA%`
- uninstall/backup behavior is surprising
- multiple code paths may eventually disagree on locations

### Fix

Create one shared desktop path provider:

```kotlin
object DesktopPaths {
    val appDataDir: Path
    val configDir: Path
    val saveDir: Path
    val cacheDir: Path
}
```

Suggested conventions:

- macOS: `~/Library/Application Support/Omilator`
- Windows: `%APPDATA%\Omilator` (or LOCALAPPDATA depending on desired roaming)
- Linux: `${XDG_CONFIG_HOME:-~/.config}/omilator` for config and `${XDG_DATA_HOME:-~/.local/share}/omilator` for saves/data

Pass directories into `PlayerEngine` instead of recomputing them internally.

---

## 10. MEDIUM — Desktop core-option file naming collides across ROMs and the file extension/content disagree

### Affected file

`ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt`

### Problem

SRAM correctly hashes the canonical ROM path, but options do not:

```kotlin
return File(dir, "${File(romPath).nameWithoutExtension}.json")
```

Two different ROMs with the same filename share one option file.

Additionally, the file is called `.json` but contains:

```text
key=value
key2=value2
```

not JSON.

Values containing newline or `=` are also not safely encoded.

### Impact

Per-game core options can bleed between different games and systems.

### Fix

Reuse the same stable ROM identity/hash as SRAM.

Either use actual JSON:

```kotlin
@Serializable
data class PersistedCoreOptions(val values: Map<String, String>)
```

or rename to `.properties` and use a proper escaping format.

Recommended filename:

```text
<sanitized-title>-<canonical-path-hash>.json
```

---

## 11. LOW — `PlayerEngine.start()` failure does not clean up a partially loaded core

### Affected file

`ui-shared/src/desktopMain/kotlin/com/omilator/ui/player/PlayerEngine.kt`

### Problem

`start()` catches all failures and only sets UI error:

```kotlin
} catch (t: Throwable) {
    _state.value = ...
}
```

If `loadCore()` succeeded but `loadGame()`, audio configuration, gamepad init, option restore, or SRAM restore throws, the core may remain initialized until `stop()` is later called.

Depending on UI behavior after startup error, `stop()` may not always run.

### Fix

Track startup stages and clean up in the catch:

```kotlin
catch (t: Throwable) {
    runCatching { controller.detach() }
    runCatching { controller.unloadGame() }
    runCatching { controller.unloadCore() }
    runCatching { gamepadPoller.destroy() }
    runCatching { audioOutput.release() }
    _state.value = ...
}
```

Or have `start()` call a shared idempotent teardown routine.

Also consider preventing `start()` from being invoked twice.

---

## 12. LOW — Emulator installer parses GitHub release JSON with unrelated regex lists

### Affected file

`data-launcher/src/desktopMain/kotlin/com/omilator/data/launcher/EmulatorInstaller.kt`

### Problem

The latest-release response is parsed with two independent regex scans:

```kotlin
val urls = assetRegex.findAll(body)...
val names = nameRegex.findAll(body)...
for (i in urls.indices) {
    val name = names.getOrElse(i) { "" }
```

`"name"` occurs in many objects in GitHub release JSON, not only asset objects. Therefore index `i` in the `names` list is not guaranteed to correspond to index `i` in `browser_download_url`.

### Impact

The installer can select the wrong asset or fail to find a valid one as GitHub response structure changes.

### Fix

Use `kotlinx.serialization`:

```kotlin
@Serializable
data class GithubRelease(val assets: List<GithubAsset> = emptyList())

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
```

Then:

```kotlin
release.assets.firstOrNull { spec.assetFilter(it.name) }?.browserDownloadUrl
```

Also set a `User-Agent` request header and handle GitHub API rate limits explicitly.

---

## 13. LOW — CI smoke test can report success for a process that was killed by `timeout`

### Affected file

`.github/workflows/build.yml`

### Problem

Linux runs:

```sh
xvfb-run ... timeout 15 java ... &
APP_PID=$!
sleep 10
if kill -0 $APP_PID; then
   ...
```

`APP_PID` is the wrapper process (`xvfb-run` / `timeout` chain), not guaranteed to be the JVM.

The smoke test verifies only that the wrapper is still alive at 10 seconds. It does not inspect application logs or prove the main window initialized successfully.

It also leaves a race between the 10-second check and the 15-second timeout.

### Fix

Prefer testing the packaged executable like the Windows job, or create a dedicated `--smoke-test` app mode that initializes core services/window and exits 0 after successful startup.

At minimum:

- capture stdout/stderr
- `wait` on the wrapper
- distinguish intentional timeout (124) from crash
- assert an explicit startup marker emitted by the application

---

## 14. LOW — README and workflow comments have drifted from the repository state

### Affected files

- `README.md`
- `.github/workflows/build.yml`
- `AGENTS.md`

### Problems

README says:

- Kotlin `2.0.21`
- Compose `1.7.0`

but the version catalog currently contains:

- Kotlin `2.1.20`
- Compose `1.9.0`

The CI workflow contains comments referring to `SESSION_NOTES`, while `AGENTS.md` explicitly says such files should not be created/committed, and none is present in the repository.

`AGENTS.md` also says the repo is mirrored at `github.com/im-tyler/omilator`, while the audited repository is `github.com/omilator/omilator`.

### Fix

Avoid duplicating version numbers in README where practical. If retained, update them whenever the catalog changes.

Replace the workflow comment with a stable public reference, for example:

```yaml
# macOS GLFW/Cocoa main-thread conflict is tracked in NEXT_STEPS.md.
```

Update `AGENTS.md` to the current canonical GitHub remote.

---

# Additional improvements

These are not necessarily bugs, but they would materially improve reliability and maintainability.

## A. Add a cross-platform libretro conformance test core

The project has already caught many ABI issues through audits. A tiny in-repo test core would prevent regressions far more cheaply.

The test core should exercise:

- callback installation before `retro_init`
- environment pointer outputs
- `need_fullpath = true`
- `need_fullpath = false`
- pixel formats
- audio sample + batch callbacks
- input joypad and analog
- SRAM memory region
- serialize/unserialize
- core options v1 + legacy variables
- intentionally unknown environment commands

Build it for JVM/desktop, Android, and iOS simulator where possible.

## B. Move platform path policy into dedicated modules

Paths are currently computed independently in settings/player/app code.

Create a small abstraction:

```kotlin
data class AppDirectories(
    val config: String,
    val data: String,
    val cache: String,
    val saves: String,
    val system: String,
    val cores: String,
)
```

Construct it once in each platform app and inject it.

## C. Replace extension-only content classification with a resolver

Many formats are inherently ambiguous:

- `.iso`
- `.bin`
- `.cue`
- `.chd`
- `.m3u`
- `.elf`
- `.app`

A robust resolver can use:

1. directory context
2. sidecar files
3. lightweight magic/header sniffing
4. user override
5. extension as fallback

Persist a selected system/core override for ambiguous games.

## D. Add static analysis and formatting to CI

Useful additions:

- `./gradlew check`
- Kotlin tests for every module
- ktlint or detekt
- Android lint
- C/C++ warnings-as-errors for JNI bridge where realistic
- sanitizer build for the small JNI test core / native bridge

The current workflow primarily compiles/package-links; it does not appear to run the full test suite.

## E. Validate downloads before replacing usable files

Apply a common pattern to:

- libretro core downloads
- server ROM downloads
- cover art
- emulator downloads

Pattern:

1. write `<target>.part`
2. enforce HTTP success
3. optionally validate expected size/hash/content type
4. `fsync` if durability matters
5. atomic rename

This prevents partial artifacts from being treated as installed/cache hits.

## F. Make teardown idempotent everywhere

Native emulator lifecycle code benefits from an explicit state machine:

```text
NEW -> CORE_LOADED -> GAME_LOADED -> RUNNING -> STOPPING -> CLOSED
```

Every teardown method should be safe to call multiple times. This simplifies Compose disposal, activity recreation, failed startup, and cancellation.

---

# Suggested implementation order

## Phase 1 — native correctness

1. Fix Android `const char **` environment writes.
2. Implement/read `need_fullpath` + `block_extract` on Android and Desktop.
3. Add the conformance test core for both content-loading modes.

## Phase 2 — user-visible correctness

4. Centralize Android core resolution and remove mGBA fallback.
5. Fix empty-library rescan behavior.
6. Fix repository multi-directory cache semantics.
7. Fix Libteca resume truncation, final-size validation, and file closing.

## Phase 3 — concurrency/lifecycle

8. Refactor `ServerLibraryViewModel` to immutable state updates.
9. Make PlayerEngine startup/teardown idempotent.
10. Centralize platform directories.

## Phase 4 — maintenance

11. Hash per-ROM option filenames + use real JSON.
12. Replace GitHub-release regex parsing.
13. Strengthen CI smoke tests and run tests/static analysis.
14. Refresh README / AGENTS / workflow comments.

---

# Patch sketches

## Android environment pointer fix

Preferred architecture: store output strings in native state and answer pointer-valued commands in native code.

```cpp
struct CoreState {
    // existing function pointers...
    std::string system_directory;
    std::string save_directory;
    std::string core_path;
};

static bool on_environment(unsigned cmd, void* data) {
    if (!data) return false;

    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *reinterpret_cast<const char**>(data) =
                g_state.system_directory.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *reinterpret_cast<const char**>(data) =
                g_state.save_directory.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
            *reinterpret_cast<const char**>(data) =
                g_state.core_path.c_str();
            return true;

        default:
            break;
    }

    // Delegate non-pointer commands to Kotlin if desired.
    // ...
}
```

Pass these strings into native state before `retro_init()`.

## Libteca safe resume

```kotlin
RandomAccessFile(dst, "rw").use { out ->
    if (conn.responseCode == 200 && have > 0) {
        have = 0
        out.setLength(0)
    }

    out.seek(have)

    conn.inputStream.use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            onProgress?.onProgress(have, total)
        }
    }

    if (size > 0) {
        check(out.length() == size) {
            "Downloaded ${out.length()} bytes; expected $size"
        }
    }
}
```

Better: download to a `.part` file and rename after validation.

## Library rescan ownership

```kotlin
class LibraryRepository(
    private val scanner: LibraryScanner,
) {
    private var cached: List<Game> = emptyList()

    suspend fun rescan(directories: List<String>): List<Game> {
        cached = directories
            .flatMap { scanner.scan(it) }
            .distinctBy(Game::id)
        return cached
    }

    fun games(): List<Game> = cached
}
```

```kotlin
fun rescan(directories: List<String>) {
    scope.launch {
        if (directories.isEmpty()) {
            _state.value = _state.value.copy(
                isLoading = false,
                games = emptyList(),
                selectedSystem = null,
                error = null,
            )
            return@launch
        }

        _state.value = _state.value.copy(isLoading = true, error = null)

        runCatching { repository.rescan(directories) }
            .onSuccess { games ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    games = games,
                )
            }
            .onFailure { t ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = t.message ?: "Scan failed",
                )
            }
    }
}
```

## Shared core resolution

```kotlin
fun coreNameFor(game: Game): String =
    "${game.system.preferredCore}_libretro"
```

Do not map an unknown extension to mGBA. Surface an explicit unsupported-content error.

---

# Verification checklist after fixes

Run at least:

```sh
./gradlew check
./gradlew :app-desktop:compileKotlinDesktop
./gradlew :app-android:assembleDebug
./gradlew :ui-shared:linkDebugFrameworkIosSimulatorArm64
```

Then manually verify:

- Android: launch a BIOS-using core and confirm system/save directory queries.
- Android: load one `need_fullpath=true` core and one `need_fullpath=false` core.
- Desktop Windows/Linux: verify config, saves, SRAM, and options land in platform-correct directories.
- Library: configure two ROM folders, remove one, then remove the last; UI and repository queries must match.
- Libteca: create a partial file larger than the server payload and force server response `200` instead of `206`; resulting ROM must be exactly expected size.
- Server library: progress updates should remain smooth under a large download without off-main mutation warnings.
- Two identically named ROMs in different folders must have independent SRAM and core-option files.

---

# Final assessment

The project has improved substantially over the previous audit passes, particularly around Desktop FFM and iOS native ABI handling. The remaining Android pointer-output issue is serious enough that it should be fixed before treating Android libretro execution as stable.

After the two High findings and the Libteca/cache/state issues are fixed, the next highest-value investment is not another large static audit: it is a tiny cross-platform libretro conformance core plus CI coverage. That would turn many of the ABI and lifecycle assumptions that have repeatedly required manual auditing into executable contracts.
