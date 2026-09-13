# omilator - Audit Pass 3 (2026-09-12)

> STATUS: 6 of 7 fixed; #4 REFUTATED pending runtime test.
> #4 (bottom_left_origin direction): libretro.h states false = "standard libretro
> top-left origin semantics"; GL readback is bottom-row-first, so frames from a
> top-left-semantics core arrive reversed and MUST flip - which is what the code
> does (flip unless bottom_left_origin). Matches RetroArch's readback behavior.
> A hardware-rendering core on-device is the only arbiter that can settle it;
> a blind revert would invert the common case. All fixes verified by forced
> recompile of desktop, android (Kotlin + NDK), iosArm64, iosSimulatorArm64.

## Verdict

**7 new findings: 5 High, 2 Medium. No Critical findings.** This pass intentionally excludes all Pass 1 / Pass 2 findings and the documented deliberate limits. The findings below are distinct failure modes in or exposed by the pass-2-era fixes. The remaining focused areas — including the non-throwing environment wrapper, iOS full-path content branch and C-width callbacks, PlayerEngine child-job cancellation/SRAM hashing, and the corrected desktop analog index/id mapping — produced no additional distinct finding that met the stated bar after prior-list overlap was removed. fileciteturn7file0L6-L17

## Findings

### 1. [HIGH] `SET_CORE_OPTIONS_INTL` is now reachable through v1 negotiation but is acknowledged and discarded

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`handleEnvironment`, new `parseCoreOptionsIntl`)

**Problem:** The pass-2 fix now answers `GET_CORE_OPTIONS_VERSION` with version 1, but `SET_CORE_OPTIONS_INTL` still returns `true` without parsing its `retro_core_options_intl` payload. The standard libretro v1 helper path can therefore select `SET_CORE_OPTIONS_INTL` after successful version negotiation, while Omilator silently stores no definitions. In the current source, ordinary `SET_CORE_OPTIONS` is parsed, the intl command is a bare success, and legacy `SET_VARIABLES` is parsed separately. fileciteturn6file1L55-L68 fileciteturn7file2L239-L268 Standard generated core-option helpers use the intl command when v1 is available and an internationalized definition structure is built. citeturn140227view0

A concrete failure is a core using the generated v1 helper with US definitions plus optional localized definitions. Omilator advertises v1, the core calls `SET_CORE_OPTIONS_INTL`, Omilator returns success, but `coreOptions`/`optionSelections` remain empty. The settings UI then reports no options, and later `GET_VARIABLE` cannot supply the core's declared defaults.

**Fix:** Until `GET_LANGUAGE` and localized-definition merging are implemented, parse the mandatory US definition pointer rather than claiming success and dropping the payload.

```kotlin
private fun parseCoreOptionsIntl(data: MemorySegment) {
    if (data.address() == 0L) return

    // retro_core_options_intl {
    //     const retro_core_option_definition *us;
    //     const retro_core_option_definition *local;
    // }
    val intl = data.reinterpret(16L)
    val us = intl.get(ValueLayout.ADDRESS, 0)

    if (us.address() != 0L) {
        parseCoreOptions(us)
    }
}

private fun handleEnvironment(cmd: Int, data: MemorySegment): Boolean {
    return when (cmd) {
        // ...
        RetroEnv.SET_CORE_OPTIONS_INTL -> {
            parseCoreOptionsIntl(data)
            true
        }
        // ...
        else -> false
    }
}
```

### 2. [HIGH] `parseLegacyVariables()` treats the entire pipe-delimited choice list as one default value

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`parseLegacyVariables`)

**Problem:** The new legacy fallback parser splits only at `"; "`. For a valid legacy value such as `"Speed hack; false|true"`, it records the default as the literal string `"false|true"` and exposes exactly one `CoreOptionValue` with that same text. fileciteturn7file3L490-L525 The libretro legacy-variable contract instead defines the suffix as a `|`-delimited value list whose first entry is the default; the canonical example is `false|true`. citeturn148673view2turn431364view0

A concrete failure is a legacy core that declares `"frameskip; disabled|enabled"`, then compares `GET_VARIABLE` against `"disabled"` or `"enabled"`. Omilator returns `"disabled|enabled"`, which is not a legal individual choice, so the core can fall through to the wrong configuration branch. The UI likewise shows one bogus combined choice instead of two values.

**Fix:** Parse the description separately, split the value payload on `|`, and select the first actual value as the default.

```kotlin
private fun parseLegacyVariables(data: MemorySegment) {
    if (data.address() == 0L) return

    coreOptions.clear()
    var offset = 0L

    while (true) {
        val record = data.reinterpret(offset + 16L)
        val keySeg = record.get(ValueLayout.ADDRESS, offset)
        if (keySeg.address() == 0L) break

        val valueSeg = record.get(ValueLayout.ADDRESS, offset + 8)
        val key = keySeg.reinterpret(256L).getUtf8String(0)
        val raw = if (valueSeg.address() != 0L) {
            valueSeg.reinterpret(1024L).getUtf8String(0)
        } else {
            ""
        }

        val description = raw.substringBefore(";").trim().ifBlank { key }
        val choices = raw.substringAfter(";", "")
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val defaultValue = choices.firstOrNull().orEmpty()

        coreOptions += CoreOption(
            key = key,
            description = description,
            info = null,
            default = defaultValue,
            values = choices.map { CoreOptionValue(it, it) },
        )

        if (key !in optionSelections && defaultValue.isNotEmpty()) {
            optionSelections[key] = defaultValue
        }

        offset += 16L
    }
}
```

### 3. [MEDIUM] `GET_VARIABLE` reports the interface as unsupported for a valid capability probe or unknown key

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`handleGetVariable`)

**Problem:** `handleGetVariable()` returns `false` when `data == NULL`, when the key pointer is null, or when the requested key is absent from `optionSelections`. fileciteturn4file3L158-L173 Libretro permits `RETRO_ENVIRONMENT_GET_VARIABLE` with `data == NULL` as a support probe and requires the frontend to return success when the facility exists; for an unknown variable, the output value should be null rather than changing the command's support result. citeturn148673view2

A concrete failure is a core that first executes `environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, NULL)` before enabling its option code. Omilator returns `false`, so the core concludes that variables are unavailable even though Omilator implements them. A core probing an optional key can reach the same false-negative.

**Fix:** Separate “the command is supported” from “a value exists,” and write a null output pointer for missing keys.

```kotlin
private val optionValuePointers =
    mutableMapOf<Pair<String, String>, MemorySegment>()

private fun stableOptionValuePointer(key: String, value: String): MemorySegment =
    optionValuePointers.getOrPut(key to value) {
        arena.allocateUtf8String(value)
    }

private fun handleGetVariable(data: MemorySegment): Boolean {
    // NULL is a valid capability probe.
    if (data.address() == 0L) return true

    val view = data.reinterpret(16L)
    val keySeg = view.get(ValueLayout.ADDRESS, 0)

    if (keySeg.address() == 0L) {
        view.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL)
        return true
    }

    val key = keySeg.reinterpret(256L).getUtf8String(0)
    val value = optionSelections[key]

    view.set(
        ValueLayout.ADDRESS,
        8,
        if (value == null) MemorySegment.NULL
        else stableOptionValuePointer(key, value),
    )
    return true
}
```

### 4. [HIGH] HW readback applies `bottom_left_origin` in the wrong direction, vertically inverting valid frames

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/HwRenderBridge.kt` (`handleRequest`, `readPixels`); `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/gl/GlContext.kt` (`readPixelsRGBA`)

**Problem:** The bridge correctly reads the core's `bottom_left_origin` flag, but `readPixels()` returns raw OpenGL row order when that flag is `true` and flips rows only when it is `false`. fileciteturn8file1L294-L304 fileciteturn8file1L358-L375 The source itself notes that `glReadPixels` produces OpenGL's bottom-left-origin row order and that conversion may need a flip. fileciteturn9file10L843-L850 Under libretro, `bottom_left_origin=true` explicitly means the core uses conventional OpenGL bottom-left origin; `false` means normal libretro top-left semantics. citeturn148673view0turn953012view1

A concrete failure is any accepted GL-core core that requests `bottom_left_origin=true`: its framebuffer is read bottom row first and sent unchanged into Omilator's top-left-oriented software framebuffer path, so the game appears vertically upside down. Conversely, a core preserving top-left semantics is unnecessarily flipped.

**Fix:** Flip raw OpenGL rows for the conventional bottom-left case, not for the libretro top-left case.

```kotlin
fun readPixels(w: Int, h: Int): ByteArray? {
    if (w <= 0 || h <= 0) return null

    val raw = gl?.readPixelsRGBA(w, h) ?: return null
    if (!bottomLeftOrigin) return raw

    val stride = w * 4
    val flipped = ByteArray(raw.size)

    for (y in 0 until h) {
        val src = y * stride
        val dst = (h - 1 - y) * stride
        raw.copyInto(flipped, dst, src, src + stride)
    }

    return flipped
}
```

### 5. [HIGH] Android downloader writes the archive's `_android.so` filename, but every consumer looks for the canonical `_libretro.so` filename

**File:** `data-library/src/androidMain/kotlin/com/omilator/data/library/AndroidCoreDownloader.kt` (`download`, `isInstalled`); `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt` (`onCreate` / `onPlayRom` core resolution)

**Problem:** The runtime-ABI fix now downloads URLs such as `mgba_libretro_android.so.zip`, but extraction writes the `.so` using `File(entry2.name).name`. At the same time, `isInstalled()` and `download()`'s `existing` check look for `mgba_libretro.so`. fileciteturn8file2L452-L499 `MainActivity` also resolves downloaded cores as `"$coreName.so"`, where `coreName` is `mgba_libretro`, `mesen_libretro`, etc. fileciteturn6file0L17-L24 Current buildbot artifacts use the `_libretro_android.so.zip` naming form, and Android libretro deployments commonly contain the extracted `_libretro_android.so` member. citeturn587838view0turn140680search0

A concrete failure is downloading mGBA on a device with no bundled core. The downloader can successfully extract `mgba_libretro_android.so` and report success, but `isInstalled()` still returns false and `MainActivity` checks for `mgba_libretro.so`, so the freshly downloaded core is never selected. The next download attempt repeats the same work.

**Fix:** Treat the archive member name as remote packaging only and always install it under Omilator's canonical local name.

```kotlin
fun download(entry: CoreEntry, onProgress: (String) -> Unit = {}): Boolean {
    coresDir.mkdirs()

    val soName = "${entry.name}_libretro.so"
    val target = File(coresDir, soName)
    if (target.exists()) return true

    val zipUrl = "$buildbotBase/${entry.urlName}_android.so.zip"

    val conn = URL(zipUrl).openConnection() as HttpURLConnection
    if (conn.responseCode != 200) {
        conn.disconnect()
        return false
    }

    ZipInputStream(conn.inputStream).use { zis ->
        var zipEntry = zis.nextEntry
        while (zipEntry != null) {
            if (!zipEntry.isDirectory && zipEntry.name.endsWith(".so")) {
                val tmp = File(coresDir, ".$soName.tmp")
                tmp.outputStream().use { output -> zis.copyTo(output) }

                try {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                return true
            }
            zipEntry = zis.nextEntry
        }
    }

    return false
}
```

### 6. [HIGH] Android's atomic settings writer is wired, but `SettingsViewModel` starts from defaults and can atomically erase persisted settings

**File:** `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt` (`onCreate`); `ui-shared/src/commonMain/kotlin/com/omilator/ui/settings/SettingsScreen.kt` (`SettingsViewModel`, `persist`)

**Problem:** `MainActivity` now constructs an atomic `SettingsStore` and passes it to `SettingsViewModel`, but unlike the desktop/iOS startup paths it never hydrates that ViewModel from `settings.json` before exposing settings actions. The ViewModel therefore begins with `theme=SYSTEM`, an empty directory list, and an empty TheGamesDB key. fileciteturn9file4L331-L372 Its `persist()` method then copies all three ViewModel-owned fields into the latest stored `AppSettings`, so any setter persists the stale/default snapshot wholesale. fileciteturn8file3L546-L626

A concrete failure is a user who already has library directories `[A, B]` and an API key persisted. After a cold Android launch, toggling the theme causes `persist()` to atomically replace the stored directory list with `[]` and the API key with `""`. Adding directory `C` can similarly replace `[A, B]` with `[C]`. The atomic writer prevents a torn file, but faithfully commits the wrong state.

**Fix:** Hydrate the ViewModel without invoking persistence before any UI mutation can occur. A constructor-level initial state avoids a load/persist race.

```kotlin
class SettingsViewModel(
    private val settingsStore: SettingsStore? = null,
    private val settingsPath: String = "",
    initial: AppSettings = AppSettings.DEFAULT,
) {
    private val _state = MutableStateFlow(
        SettingsUiState(
            theme = initial.theme,
            libraryDirectories = initial.libraryDirectories,
            theGamesDbApiKey = initial.theGamesDbApiKey,
        )
    )

    // existing setters/persist...
}
```

```kotlin
// MainActivity.onCreate
val initialSettings = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
    settingsStore.loadAppSettings(settingsPath)
}

settingsViewModel = SettingsViewModel(
    settingsStore = settingsStore,
    settingsPath = settingsPath,
    initial = initialSettings,
)
```

### 7. [MEDIUM] Android now truthfully writes environment string outputs, but production passes an empty system/save directory and returns it as valid

**File:** `app-android/src/androidMain/kotlin/com/omilator/app/MainActivity.kt` (`onCreate` player construction); `core-libretro/src/androidMain/kotlin/com/omilator/core/libretro/impl/JniCoreController.kt` (`onEnvironment`)

**Problem:** `MainActivity` constructs the Android controller with `createCoreController("")`. fileciteturn6file3L117-L135 The corrected environment table then handles `GET_SYSTEM_DIRECTORY` and `GET_SAVE_DIRECTORY` by writing that `systemDirectory` string and returning `true`, so cores receive a non-null pointer to `""` rather than a usable directory or an honest null result. fileciteturn5file1L33-L54 Libretro defines these commands as frontend-managed directory paths, with null used when no directory is available. citeturn148673view1turn143258search0

A concrete failure is PCSX ReARMed on Android: it uses the frontend's system directory for BIOS discovery. Even if a BIOS is provisioned in Omilator's app storage, the core is told the system path is the empty string, so it cannot discover that file through the frontend contract and may fall back to HLE BIOS or fail on titles that require a real BIOS. The same empty-path success can misdirect core-managed save files.

**Fix:** Create a real app-private libretro directory before controller construction. If system and save paths remain shared for now, one valid directory is still safer than a successful empty string; longer term, carry distinct system/save directories through the Android controller.

```kotlin
// MainActivity.onCreate
val libretroDir = File(filesDir, "libretro").apply { mkdirs() }

// Inside setContent/player construction:
val coreController = remember(core) {
    createCoreController(libretroDir.absolutePath)
}
```

A stronger controller shape is:

```kotlin
internal class JniCoreController(
    private val systemDirectory: String,
    private val saveDirectory: String,
) : CoreController {

    fun onEnvironment(cmd: Int, dataPtr: Long): Boolean = when (cmd) {
        GET_SYSTEM_DIRECTORY ->
            writeDirectoryOrNull(dataPtr, systemDirectory)

        GET_SAVE_DIRECTORY ->
            writeDirectoryOrNull(dataPtr, saveDirectory)

        // ...
        else -> false
    }
}
```

where `writeDirectoryOrNull()` writes a null pointer when no valid directory exists rather than returning success with a pointer to an empty C string.

[AUDIT-CHATGPT-3.md](sandbox:/mnt/data/AUDIT-CHATGPT-3.md)
