package com.omilator.data.library

/**
 * Core-install catalogs for the mobile platforms, declared in common so
 * the routing invariant is pinnable by common tests: every core name the
 * shared [GameSystem] table routes to (`<preferredCore>_libretro`) must
 * be an install stem in BOTH mobile catalogs. A routed stem that no
 * provisioning path can install turns "Core not installed" into a remedy
 * that can never succeed — pass C finding 2: iOS requested
 * beetle_psx_hw/azahar/play while its downloader and bundle script
 * shipped `mednafen_psx_hw` and carried no 3DS or PS2 core at all.
 *
 * [name] is the canonical install stem shared by every consumer:
 * `<name>_libretro.dylib` (iOS Documents/cores + bundled Frameworks) and
 * `<name>_libretro.so` (Android cores dir + `lib<name>_libretro.so` in a
 * bundled APK's jniLibs) — and it must equal the GameSystem
 * preferredCore that routes to it.
 *
 * [urlName] is the buildbot artifact identity: the iOS downloader fetches
 * `<urlName>.dylib.zip`, the Android downloader fetches
 * `<urlName>_android.so.zip` (or [artifact] verbatim). Apple's ios-arm64
 * buildbot mostly publishes `<core>_libretro_ios`, with
 * mgba/ppsspp/dolphin/flycast/azahar dropping the infix and beetle_psx_hw
 * published under its legacy `mednafen_psx_hw` name — the same mapping
 * the Android and desktop catalogs already carry. Stems below were
 * verified against the live buildbot listings on 2026-10-06.
 */
data class MobileCoreEntry(
    val name: String,
    val system: String,
    val urlName: String,
    /** Full zip file name when it deviates from `<urlName>_android.so.zip`
     *  (Android only: azahar drops the `_android` infix). */
    val artifact: String? = null,
)

object IosCoreCatalog {
    val entries: List<MobileCoreEntry> = listOf(
        MobileCoreEntry("mgba", "GB / GBC / GBA", "mgba_libretro"),
        MobileCoreEntry("mesen", "NES", "mesen_libretro_ios"),
        MobileCoreEntry("snes9x", "SNES", "snes9x_libretro_ios"),
        MobileCoreEntry("genesis_plus_gx", "Genesis / Mega Drive", "genesis_plus_gx_libretro_ios"),
        MobileCoreEntry("mupen64plus_next", "N64 (software render)", "mupen64plus_next_libretro_ios"),
        // Routed stem beetle_psx_hw; the buildbot publishes it as mednafen_psx_hw.
        MobileCoreEntry("beetle_psx_hw", "PS1 (accurate)", "mednafen_psx_hw_libretro_ios"),
        MobileCoreEntry("pcsx_rearmed", "PS1 (fast)", "pcsx_rearmed_libretro_ios"),
        MobileCoreEntry("melonds", "DS", "melonds_libretro_ios"),
        MobileCoreEntry("mednafen_saturn", "Saturn", "mednafen_saturn_libretro_ios"),
        MobileCoreEntry("nestopia", "NES (alt)", "nestopia_libretro_ios"),
        MobileCoreEntry("gambatte", "GB / GBC (alt)", "gambatte_libretro_ios"),
        MobileCoreEntry("sameboy", "GB / GBC (accurate)", "sameboy_libretro_ios"),
        MobileCoreEntry("fbneo", "Arcade", "fbneo_libretro_ios"),
        MobileCoreEntry("picodrive", "Genesis / 32X", "picodrive_libretro_ios"),
        // 3DS / PS2 (routed by the shared table; azahar drops the _ios infix).
        MobileCoreEntry("azahar", "3DS", "azahar_libretro"),
        MobileCoreEntry("play", "PS2", "play_libretro_ios"),
        // HW-render cores (Vulkan via MoltenVK). Require MoltenVK.xcframework
        // bundled in the app (see setup-moltenvk.sh + iosApp/project.yml).
        // NOTE: actual rendering needs swapchain plumbing — cores will load
        // but render black until VulkanHwRender.kt is finished.
        MobileCoreEntry("ppsspp", "PSP (HW render — Vulkan)", "ppsspp_libretro"),
        MobileCoreEntry("dolphin", "GameCube / Wii (HW render — Vulkan)", "dolphin_libretro"),
        MobileCoreEntry("flycast", "Dreamcast (HW render — Vulkan)", "flycast_libretro"),
    )
}

object AndroidCoreCatalog {
    val entries: List<MobileCoreEntry> = listOf(
        MobileCoreEntry("mgba", "GB / GBC / GBA", "mgba_libretro"),
        MobileCoreEntry("mesen", "NES", "mesen_libretro"),
        MobileCoreEntry("snes9x", "SNES", "snes9x_libretro"),
        MobileCoreEntry("genesis_plus_gx", "Genesis / Mega Drive", "genesis_plus_gx_libretro"),
        MobileCoreEntry("beetle_psx_hw", "PS1 (accurate)", "mednafen_psx_hw_libretro"),
        MobileCoreEntry("pcsx_rearmed", "PS1 (fast)", "pcsx_rearmed_libretro"),
        MobileCoreEntry("melonds", "DS", "melonds_libretro"),
        MobileCoreEntry("mednafen_saturn", "Saturn", "mednafen_saturn_libretro"),
        MobileCoreEntry("nestopia", "NES (alt)", "nestopia_libretro"),
        MobileCoreEntry("gambatte", "GB / GBC (alt)", "gambatte_libretro"),
        MobileCoreEntry("sameboy", "GB / GBC (accurate)", "sameboy_libretro"),
        MobileCoreEntry("fbneo", "Arcade", "fbneo_libretro"),
        MobileCoreEntry("picodrive", "Genesis / 32X", "picodrive_libretro"),
        MobileCoreEntry("mupen64plus_next", "N64 (software render)", "mupen64plus_next_libretro"),
        MobileCoreEntry("azahar", "3DS", "azahar_libretro", artifact = "azahar_libretro.so.zip"),
        MobileCoreEntry("play", "PS2", "play_libretro"),
        // PSP/GC/Wii/Dreamcast: available on buildbot but require Vulkan
        // (Android has native Vulkan — no MoltenVK needed). Untested.
        MobileCoreEntry("ppsspp", "PSP (Vulkan)", "ppsspp_libretro"),
        MobileCoreEntry("flycast", "Dreamcast (Vulkan)", "flycast_libretro"),
        MobileCoreEntry("dolphin", "GameCube / Wii (Vulkan)", "dolphin_libretro"),
    )
}
