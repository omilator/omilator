# omilator - Audit Pass 4 (2026-09-12)

> STATUS: all 3 findings fixed (same day). INTL wrapper's US pointer is
> dereferenced before parsing; every parseCoreOptions field read goes through
> the bounded record view; GET_VARIABLE writes an explicit NULL value for
> unknown/null keys so reused retro_variable structs cannot observe stale
> pointers. Verified: forced recompile of desktop, android (Kotlin + NDK),
> iosArm64, iosSimulatorArm64.
## Verdict

**3 demonstrable defects remain: 2 High, 1 Medium. No Critical findings.** This is not a clean bill.

The requested pass-3 fixes were re-verified narrowly. The legacy `SET_VARIABLES` path now splits the `|` choice list and uses the first choice as the default. fileciteturn1file0L95-L120 Android core extraction now installs to the canonical `<core>_libretro.so` name that `isInstalled()` and the launcher resolve. fileciteturn1file2L239-L280 fileciteturn5file0L28-L31 Android also hydrates the settings ViewModel before `setContent`, closing the original “first user action overwrites persisted defaults” failure mode, and it creates/passes a real app-private `filesDir/libretro` directory to the JNI controller, which returns that path for system/save-directory queries. fileciteturn1file1L167-L178 fileciteturn1file1L140-L143 fileciteturn1file1L198-L202 fileciteturn5file1L210-L214

`GET_VARIABLE(NULL)` now correctly reports support, but the missing-key output semantics are still incomplete (Finding 3). fileciteturn1file0L75-L92 Pass-3 finding #4 is not reopened: the current readback still flips only when `bottomLeftOrigin == false`, matching the recorded refutation; an on-device HW-core test remains the stated arbiter, not a static defect. fileciteturn4file0L14-L21 fileciteturn4file2L63-L74

## Findings

### 1. [HIGH] `SET_CORE_OPTIONS_INTL` still passes the wrapper struct itself to the definition parser

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`handleEnvironment`, missing `parseCoreOptionsIntl`)

The current `SET_CORE_OPTIONS_INTL` branch calls `parseCoreOptions(data)` directly. fileciteturn2file0L21-L28 But the pass-3 register correctly records the payload shape as `retro_core_options_intl { definitions *us; definitions *local; }` and the required fix as dereferencing the US pointer at offset 0 before calling `parseCoreOptions`. fileciteturn2file2L122-L146

**Concrete failure:** a core using the standard v1 internationalized helper sends the wrapper. Omilator interprets the wrapper's first pointer as though it were the first option's `key` pointer instead of following it to the US definition array. The definitions therefore cannot be parsed correctly; with the current parser this also falls into Finding 2 and the environment call is rejected.

**Fix:** add the recorded `parseCoreOptionsIntl()` helper, reinterpret the wrapper to 16 bytes, load `us = intl.get(ValueLayout.ADDRESS, 0)`, and call `parseCoreOptions(us)` only when that pointer is non-null.

### 2. [HIGH] `parseCoreOptions()` still reads fields from the zero-length upcall segment instead of its reinterpreted view

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`parseCoreOptions`)

The function explicitly notes that the FFM upcall supplies a zero-length pointer segment and creates `record = data.reinterpret(...)` for bounded access. It correctly reads `key` and each option value through `record`, but reads `desc`, `info`, each value label, and `default_value` through the original `data` segment. fileciteturn1file0L32-L58

**Concrete failure:** any core that sends a non-empty `SET_CORE_OPTIONS` definition array reaches the first `desc` read at offset 8 on the zero-length segment. JDK 21 FFM bounds-checks that access, so it throws `IndexOutOfBoundsException`; `onEnvironment` catches the exception and reports the environment command as unsupported. The core's v1 options are therefore unavailable even without `SET_CORE_OPTIONS_INTL`.

**Fix:** perform every struct-field read through the reinterpreted `record` view (or an equivalent correctly sized slice), including `descSeg`, `infoSeg`, `labelSeg`, and `defaultSeg`.

### 3. [MEDIUM] `GET_VARIABLE` returns success for an unknown key but does not clear the output value pointer

**File:** `core-libretro/src/desktopMain/kotlin/com/omilator/core/libretro/jvm/LibretroFfm.kt` (`handleGetVariable`)

The support-probe fix is present: `data == NULL` returns `true`. However, a null key or an absent `optionSelections[key]` returns `true` before writing offset 8, despite the comment claiming the value “stays NULL.” fileciteturn1file0L75-L92 The pass-3 fix explicitly required writing `MemorySegment.NULL` for missing keys. fileciteturn2file1L66-L98

**Concrete failure:** a core reuses one `retro_variable` struct. It first queries a known key and receives a non-null value pointer, then changes only `key` to an optional/unknown variable and queries again. Omilator returns success without overwriting `value`, so the stale pointer from the previous key remains visible and the core can treat the wrong value as the unknown variable's setting.

**Fix:** on every non-probe call, explicitly write offset 8. Write `MemorySegment.NULL` for a null/unknown key; otherwise write the selected value pointer, then return `true`.
