@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.omilator.core.audio.IosAudioOutput
import com.omilator.data.library.IosCoreDownloader
import com.omilator.data.library.IosLibraryScanner
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.IosSettingsPersistence
import com.omilator.data.settings.defaultIosSettingsPath
import com.omilator.core.libretro.createCoreController
import com.omilator.ui.library.LibraryViewModel
import com.omilator.ui.player.IosSramStore
import com.omilator.ui.player.MobilePlayerScreen
import com.omilator.ui.player.coreNameForRom
import com.omilator.ui.settings.SettingsViewModel
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSDocumentDirectory
import platform.UIKit.UIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun RootViewController(): UIViewController {
    lateinit var vc: UIViewController

    // Resolve iOS sandbox paths up front. Documents is user-visible (Files
    // app integration via UIFileSharingEnabled). Library/Application Support
    // would be cleaner for settings but Documents is what survives an app
    // reinstall + is backup-included.
    val documentsDir = documentsDirectory() ?: error("iOS Documents dir unavailable")
    val coresDir = "$documentsDir/cores"
    val savesDir = "$documentsDir/saves"
    val settingsPath = defaultIosSettingsPath(documentsDir)

    // Phase 9: real persistence so scannedDirectories survives restarts.
    val settingsPersistence = IosSettingsPersistence(settingsPath)
    val settingsStore = settingsPersistence.settingsStore()

    // Phase 6C: simulator-only core downloader (curl + unzip + vtool + codesign).
    val coreDownloader = IosCoreDownloader(coresDir)

    // Hoist ViewModels OUTSIDE the composable so they survive player ↔ library switches
    val libraryViewModel = LibraryViewModel(
        repository = LibraryRepository(IosLibraryScanner()),
        settingsStore = settingsStore,
        settingsPath = settingsPath,
        // Built-in scan root: Documents is re-scanned on every scan (cold
        // start included) as a union with the persisted directories — no
        // separate one-off rescan racing loadSettingsAndScan() and dropping
        // configured directories (same fix as Android's MainActivity).
        defaultScanDirectories = listOf(documentsDir),
    )
    val settingsViewModel = SettingsViewModel(
        settingsStore,
        settingsPath,
        // Seed from the persisted snapshot: hydrating through the
        // persisting setters rewrote fields they do not cover (libteca
        // URL/token) with empty strings on every cold start.
        initial = kotlinx.coroutines.runBlocking { settingsStore.loadAppSettings(settingsPath) },
    ).apply {
        // Pre-populate installed/total so the Settings UI reflects reality
        // before the user opens it.
        setCoresStatus(coreDownloader.installedCount(), coreDownloader.cores.size)
    }

    val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Wire core download UI updates. Each onProgress callback from
    // downloadAll() flows into the SettingsViewModel so the user sees
    // "Downloading mgba..." etc.
    val onDownloadCores: () -> Unit = {
        val svm = settingsViewModel
        if (!svm.state.value.coresDownloading) {
            rootScope.launch {
                svm.setCoresDownloading(true, "Starting...")
                val installed = coreDownloader.downloadAll { status ->
                    svm.setCoresDownloading(true, status)
                }
                svm.setCoresStatus(installed, coreDownloader.cores.size)
                svm.setCoresDownloading(false, "Installed $installed/${coreDownloader.cores.size}")
            }
        }
    }

    vc = ComposeUIViewController {
        var playingRom by remember { mutableStateOf<String?>(null) }
        var playingCore by remember { mutableStateOf<String?>(null) }
        var launchError by remember { mutableStateOf<String?>(null) }

        // Single routing seam for every launch path (library play,
        // quick-play, deep link): the shared GameSystem table decides the
        // core — the three hand-rolled extension tables this replaces
        // disagreed with the shared table AND each other (library .iso →
        // mGBA, deep-link .iso → PPSSPP, unknown ext → silent mGBA).
        // No mapping / missing core is surfaced, never silently routed.
        fun requestPlay(romPath: String) {
            val coreName = coreNameForRom(romPath)
            if (coreName == null) {
                launchError = "No core mapping for \"${romPath.substringAfterLast('/')}\" — " +
                    "this file type is not recognized."
                return
            }
            val resolved = corePathFor(coreName, coresDir)
            if (resolved == null) {
                launchError = "Core not installed: $coreName. " +
                    "Download cores from Settings → Cores first."
                return
            }
            playingRom = romPath
            playingCore = resolved
        }

        // Poll the URL-handler slot. StateFlow + collectAsState wasn't
        // reliably triggering recomposition when updated from Swift's
        // onOpenURL, so we poll a @Volatile var every 100ms instead.
        // Bulletproof, costs nothing.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                val pending = pendingPlayRom
                if (pending != null && playingRom == null) {
                    requestPlay(pending)
                    pendingPlayRom = null
                }
                kotlinx.coroutines.delay(100)
            }
        }

        val romPath = playingRom
        val corePath = playingCore

        if (romPath != null && corePath != null) {
            // Shared MobilePlayerScreen (same screen Android composes):
            // SRAM restore/flush with the migration + notice stack,
            // TouchInputSource (analog polls stay neutral), and per-frame
            // geometry tracking all come with it. The legacy iOS fork had
            // none of that — battery saves never persisted at all.
            // A real app-private directory: the env handler refuses
            // system/save queries when the configured directory is empty,
            // and BIOS-dependent cores need a usable path.
            val libretroDir = remember {
                "$documentsDir/../libretro".also {
                    platform.Foundation.NSFileManager.defaultManager().createDirectoryAtPath(
                        it, withIntermediateDirectories = true, attributes = null, error = null,
                    )
                }
            }
            val coreController = remember { createCoreController(libretroDir) }
            val audioOutput = remember { IosAudioOutput() }
            val sramStore = remember(romPath) {
                IosSramStore(savesDir = savesDir, romPath = romPath, romIdentity = romPath)
            }
            MobilePlayerScreen(
                romPath = romPath,
                corePath = corePath,
                coreController = coreController,
                audioOutput = audioOutput,
                sramStore = sramStore,
                onExit = {
                    playingRom = null
                    playingCore = null
                },
            )
        } else {
            OmilatorApp(
                libraryViewModel = libraryViewModel,
                settingsViewModel = settingsViewModel,
                onAddRomDirectory = {
                    pickDirectory(vc) { path ->
                        if (path != null) println("[Omilator] Picked directory: $path")
                    }
                },
                onPlayRom = ::requestPlay,
                onQuickPlay = {
                    pickFile(vc) { path ->
                        if (path != null) requestPlay(path)
                    }
                },
                onDownloadCores = onDownloadCores,
                isDesktop = false,
                singleScreen = true,
            )
        }

        // Launch failures are visible (no core mapping / core not
        // installed) instead of the old silent no-op or wrong-core launch.
        launchError?.let { message ->
            AlertDialog(
                onDismissRequest = { launchError = null },
                title = { Text("Cannot launch game") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { launchError = null }) { Text("OK") }
                },
            )
        }
    }
    return vc
}

/** Returns the first existing core path: tries Documents/cores/, then bundled Frameworks/. */
private fun corePathFor(coreName: String, coresDir: String): String? {
    val candidate = "$coresDir/$coreName.dylib"
    if (exists(candidate)) return candidate
    // Fall back to a copy shipped inside the app bundle (see setup-cores.sh).
    val bundlePath = platform.Foundation.NSBundle.mainBundle.bundlePath
    val bundled = "$bundlePath/Frameworks/$coreName.dylib"
    if (exists(bundled)) {
        logI("Root", "using bundled core for $coreName: $bundled")
        return bundled
    }
    return null
}

private fun documentsDirectory(): String? {
    val paths = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    )
    return paths.firstOrNull() as? String
}

private fun exists(path: String): Boolean {
    return try {
        platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(path)
    } catch (_: Exception) {
        false
    }
}
