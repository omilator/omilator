# Omilator

![License: MIT](https://img.shields.io/badge/license-MIT-blue) ![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF) ![Compose](https://img.shields.io/badge/UI-Compose_Multiplatform-4285F4) ![Status](https://img.shields.io/badge/status-alpha-orange)

A cross-platform libretro frontend and emulator launcher, built with Kotlin Multiplatform + Compose Multiplatform. Targets: macOS, Windows, Linux desktop + iOS, Android mobile.

**Homepage:** <https://omilator.com>

It does two things:
- **Plays libretro cores** (mGBA, etc.) directly via a native libretro bridge — no separate RetroArch required.
- **Launches standalone emulators** (Cemu, Dolphin, RPCS3, Xemu, PPSSPP) as a unified library.

## Architecture

Strict multi-module separation. UI is disposable per-platform; libretro glue is shared and stable.

```
core-libretro/    libretro C API bridge — FFM (foreign.memory) on desktop JVM, JNI on Android, cinterop on iOS
core-render/      platform video surfaces (GL desktop / Vulkan iOS HW / SurfaceView Android)
core-audio/       platform audio output (desktop / Oboe Android / AVAudioEngine iOS)
core-input/       gamepad/keyboard/touch -> libretro input state
data-library/     ROM scanner, metadata, system detection, multi-source cover art
data-launcher/    standalone-emulator backends (Cemu, Dolphin, RPCS3, Xemu, PPSSPP) + installer
data-settings/    per-game + global config (kotlinx.serialization)
data-saves/       save states, SRAM, battery saves
ui-shared/        shared Compose UI: library grid, console pages, player view, settings
app-desktop/      JVM entry point, Compose Desktop window
app-android/      Android Activity hosting Compose
iosApp/           Xcode project hosting Compose (iOS Native via cinterop)
```

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Compose Multiplatform 1.7.0 (Material 3) |
| Build | Gradle 8.10.2 (wrapper bundled) |
| Desktop libretro | JDK 21 Foreign Function & Memory API (no JNI) |
| Cores | libretro (vendored `libretro.h`); user-supplied cores at runtime |

## Build

Requires JDK 21 on PATH (or `org.gradle.java.home` set in `~/.gradle/gradle.properties`).

```sh
# Desktop (Mac/Windows/Linux) — runs in window
./gradlew :app-desktop:run

# Desktop distribution (.app / .exe / .deb)
./gradlew :app-desktop:packageDistributionForCurrentOS

# iOS framework (requires Xcode)
./gradlew :core-libretro:assembleCoreLibretroDebugFrameworkForIosSimulatorArm64

# Android (requires Android SDK at ANDROID_HOME)
./gradlew :app-android:assembleDebug
```

Libretro cores are not bundled — place a core (e.g. `mgba_libretro`) in `cores/` and a ROM to run it.

## Status

Alpha — playable on desktop, mobile bridges in progress.

**Desktop (verified).** The libretro path is implemented and verified end-to-end with mGBA via the harnesses in `core-libretro/.../jvm/`:
- **Phase 1** — core load, video frames, audio samples, and input wired through the FFM bridge.
- **Phase 2** — video rendering (frames to a `BufferedImage`, distinct-frame capture).
- **Phase 5** — save states (`retro_serialize_size` / serialize / deserialize).

**Cross-platform runtime.** Real audio (desktop / Oboe / AVAudioEngine), render (GL desktop, Vulkan iOS HW render — scaffolded, Android SurfaceView), and input (gamepad polling, iOS touch). Core controllers per platform: FFM (desktop), JNI (Android), Native (iOS). CI configured.

**Compose UI.** Working library grid, multi-source cover art (ScreenScraper + fallbacks), swipeable console pages, player view, settings, dark theme, and an iOS-adaptive single-screen layout.

**Standalone launcher.** `data-launcher/` backends for Cemu, Dolphin, RPCS3, Xemu, and PPSSPP, plus a macOS app launcher and an emulator installer — so Omilator also serves as a unified front-end for emulators that don't expose a libretro core.

## Reference

`_reference/` (gitignored) holds study-only clones of:
- `libretro/nanoarch` — minimal C frontend, ~1k LOC, used as translation reference
- `libretro/ludo` — complete Go frontend by the libretro team, architecture reference

Omilator is a clean-room reimplementation against `libretro.h` (MIT); it does not derive from ludo's code.
