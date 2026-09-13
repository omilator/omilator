# omilator - Audit Pass 5 (2026-09-12)

> STATUS: the single finding is fixed. The registered envCb now delegates to
> handleEnv (the raw 0/9/19/31/51/69 success list is gone), GET_LIBRETRO_PATH
> writes the loaded core path, and the iOS player passes a real app-private
> libretro directory so system/save queries resolve. Verified: forced recompile
> of desktop, android (Kotlin + NDK), iosArm64, iosSimulatorArm64.
## Verdict

**1 demonstrable defect remains: 1 High. No Critical findings. This is not a clean bill.**

The three pass-4 fixes are present and correctly shaped in the current source. `SET_CORE_OPTIONS_INTL` dereferences the wrapper's US definitions pointer before parsing; `parseCoreOptions()` performs every option-definition field read (`key`, `desc`, `info`, value, label, and `default_value`) through the explicitly reinterpreted bounded `record` view; and `handleGetVariable()` explicitly writes `MemorySegment.NULL` for both a null key and an unknown key, eliminating stale-value reuse. The legacy `SET_VARIABLES` first-choice default fix, Android canonical core extraction name, Android settings hydration/system-directory wiring, and the pass-3 `bottom_left_origin` refutation also remain intact. The desktop HW readback still returns raw GL-order rows when `bottomLeftOrigin == true` and flips only when it is false; that point remains an on-device HW-core validation item, not a static defect.

The remaining defect is outside those pass-4 desktop changes: the iOS environment callback still registers and executes the obsolete false-success branch instead of the corrected `handleEnv()` implementation.

## Findings

### 1. [HIGH] iOS bypasses its corrected environment handler and still reports write-required commands as successful without writing their outputs

**Files/functions:** `core-libretro/src/iosMain/kotlin/com/omilator/core/libretro/impl/NativeCoreController.kt` (`envCb`, `loadCore`, `handleEnv`); `ui-shared/src/iosMain/kotlin/com/omilator/ui/IosPlayerScreen.kt` (`IosPlayerScreen`)

`loadCore()` registers the top-level `envCb` with `retro_set_environment`. That callback still contains `0, 9, 19, 31, 51, 69 -> true`, so `GET_SYSTEM_DIRECTORY` (9), `GET_LIBRETRO_PATH` (19), and `GET_SAVE_DIRECTORY` (31) return success without writing the caller's output pointer. The newer `handleEnv()` contains the corrective behavior for 9/31 and explicitly documents that the raw-success list was wrong, but nothing calls `handleEnv()`. The path is therefore not dead: the obsolete callback is the one actually installed.

There is a second blocker on the same path: `IosPlayerScreen()` constructs the controller with `createCoreController("")`. `CoreControllerFactory` stores that argument as `systemDir`, so even after wiring `envCb` to `handleEnv()`, the corrected 9/31 branch would reject the request because the directory is empty.

**Concrete failure:** an iOS core requests `RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY` or `RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY`. Omilator currently returns `true` while leaving the supplied `const char **` unchanged (normally null, or potentially stale if the core reuses storage). A BIOS-dependent core therefore receives no usable system directory despite a successful environment return, and a core relying on the save-directory query receives no usable save path. `GET_LIBRETRO_PATH` has the same false-success behavior.

**Fix:** make the registered `envCb` delegate to `ctrl.handleEnv(cmd, data)` rather than duplicating command handling; pass a real app-private system/save directory to `createCoreController()` on iOS; and either implement output-writing for additional getter commands such as 19 or return `false` for them. Unsupported experimental commands must likewise be declined rather than matched by raw, unflagged command numbers.
