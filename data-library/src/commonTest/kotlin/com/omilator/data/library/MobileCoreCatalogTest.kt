package com.omilator.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass C finding 2: the mobile launch paths request
 * `<GameSystem.preferredCore>_libretro` (coreNameForRom); every such stem
 * must be an install stem in BOTH mobile catalogs — iOS requested
 * beetle_psx_hw/azahar/play while its downloader and bundle script
 * shipped `mednafen_psx_hw` and carried no 3DS/PS2 core, so PS1/3DS/PS2
 * ROMs dead-ended at "Core not installed" with a remedy that could never
 * succeed. Catalogs live in common (MobileCoreCatalog.kt) precisely so
 * this invariant is pinnable; the install names are
 * `<name>_libretro.dylib` (iOS Documents/cores + bundled Frameworks) and
 * `<name>_libretro.so` / `lib<name>_libretro.so` (Android downloads +
 * bundled jniLibs — setup-cores-android.sh bundles the same stems).
 */
class MobileCoreCatalogTest {

    private fun assertEveryRoutedStemIsInstallable(
        catalog: List<MobileCoreEntry>,
        platform: String,
    ) {
        val installable = catalog.map { "${it.name}_libretro" }.toSet()
        GameSystem.entries.forEach { system ->
            val requested = "${system.preferredCore}_libretro"
            assertTrue(
                requested in installable,
                "$requested (routed for $system) is not installed by the $platform catalog — " +
                    "a remedy that can never succeed",
            )
        }
    }

    @Test
    fun everyRoutedCoreStemIsInstallableOnIos() {
        assertEveryRoutedStemIsInstallable(IosCoreCatalog.entries, "iOS")
    }

    @Test
    fun everyRoutedCoreStemIsInstallableOnAndroid() {
        assertEveryRoutedStemIsInstallable(AndroidCoreCatalog.entries, "Android")
    }

    @Test
    fun installStemsAreUniquePerCatalog() {
        listOf(IosCoreCatalog.entries, AndroidCoreCatalog.entries).forEach { catalog ->
            val names = catalog.map { it.name }
            assertEquals(names.size, names.distinct().size, "duplicate install stems: $names")
        }
    }

    @Test
    fun beetlePsxHwInstallsUnderItsRoutedStemFromTheMednafenArtifact() {
        // The buildbot publishes beetle_psx_hw under its legacy
        // mednafen_psx_hw name (verified against the live ios-arm64
        // listing 2026-10-06); the install stem stays the routed one.
        val ios = IosCoreCatalog.entries.single { it.name == "beetle_psx_hw" }
        assertEquals("mednafen_psx_hw_libretro_ios", ios.urlName)
        val android = AndroidCoreCatalog.entries.single { it.name == "beetle_psx_hw" }
        assertEquals("mednafen_psx_hw_libretro", android.urlName)
    }

    @Test
    fun everyRoutedExtensionResolvesToAnInstallableIosStem() {
        // Routing-level check: every extension the shared table claims
        // routes to a system whose preferred core the iOS catalog installs.
        val installable = IosCoreCatalog.entries.map { "${it.name}_libretro" }.toSet()
        GameSystem.entries
            .flatMap { it.extensions }
            .distinct()
            .forEach { ext ->
                val system = GameSystem.detectByExtension(ext) ?: return@forEach
                val requested = "${system.preferredCore}_libretro"
                assertTrue(
                    requested in installable,
                    ".$ext routes to $requested which the iOS catalog does not install",
                )
            }
    }
}
