@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.omilator.data.library.IosCoreDownloader
import com.omilator.data.library.IosLibraryScanner
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.IosSettingsPersistence
import com.omilator.data.settings.defaultIosSettingsPath
import com.omilator.ui.library.LibraryViewModel
import com.omilator.ui.settings.SettingsViewModel
import platform.Foundation.NSHomeDirectory
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
    )
    val settingsViewModel = SettingsViewModel(settingsStore, settingsPath).apply {
        // Pre-populate installed/total so the Settings UI reflects reality
        // before the user opens it.
        setCoresStatus(coreDownloader.installedCount(), coreDownloader.cores.size)
    }

    // Load persisted theme + API key + libraryDirectories.
    kotlinx.coroutines.runBlocking {
        val settings = settingsStore.loadAppSettings(settingsPath)
        settingsViewModel.setTheme(settings.theme)
        settingsViewModel.setTheGamesDbApiKey(settings.theGamesDbApiKey)
        settingsViewModel.setDirectories(settings.libraryDirectories)
    }

    // Initial scan — defer to loadSettingsAndScan() when persistence is set,
    // but iOS always re-scans Documents on cold launch regardless (cheap,
    // and ROMs come/go via Files app outside our control).
    val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    rootScope.launch {
        libraryViewModel.rescan(listOf("Documents"))
    }

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

        // Poll the URL-handler slot. StateFlow + collectAsState wasn't
        // reliably triggering recomposition when updated from Swift's
        // onOpenURL, so we poll a @Volatile var every 100ms instead.
        // Bulletproof, costs nothing.
        // Poll the URL-handler slot. StateFlow + collectAsState wasn't
        // reliably triggering recomposition when updated from Swift's
        // onOpenURL, so we poll a @Volatile var every 100ms instead.
        // Bulletproof, costs nothing.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                val pending = pendingPlayRom
                if (pending != null && playingRom == null) {
                    val ext = pending.substringAfterLast('.', "")
                    val coreName = when (ext.lowercase()) {
                        "gba", "gb", "gbc", "sgb" -> "mgba_libretro"
                        "nes", "nez" -> "mesen_libretro"
                        "sfc", "smc" -> "snes9x_libretro"
                        "iso", "cso", "prx" -> "ppsspp_libretro" // PSP
                        "n64", "z64", "v64" -> "mupen64plus_next_libretro"
                        else -> "mgba_libretro"
                    }
                    val resolved = corePathFor(coreName, coresDir)
                    if (resolved != null) {
                        playingRom = pending
                        playingCore = resolved
                    }
                    pendingPlayRom = null
                }
                kotlinx.coroutines.delay(100)
            }
        }

        val romPath = playingRom
        val corePath = playingCore

        if (romPath != null && corePath != null) {
            IosPlayerScreen(romPath = romPath, corePath = corePath, onExit = {
                playingRom = null
                playingCore = null
            })
        } else {
            OmilatorApp(
                libraryViewModel = libraryViewModel,
                settingsViewModel = settingsViewModel,
                onAddRomDirectory = {
                    pickDirectory(vc) { path ->
                        if (path != null) println("[Omilator] Picked directory: $path")
                    }
                },
                onPlayRom = { path ->
                    val ext = path.substringAfterLast('.', "")
                    val coreName = when (ext.lowercase()) {
                        "gba", "gb", "gbc", "sgb" -> "mgba_libretro"
                        "nes", "nez" -> "mesen_libretro"
                        "sfc", "smc" -> "snes9x_libretro"
                        else -> "mgba_libretro"
                    }
                    val found = corePathFor(coreName, coresDir)
                    if (found != null) {
                        playingCore = found
                        playingRom = path
                    }
                },
                onQuickPlay = {
                    pickFile(vc) { path ->
                        if (path != null) {
                            val ext = path.substringAfterLast('.', "")
                            val coreName = when (ext.lowercase()) {
                                "gba", "gb", "gbc", "sgb" -> "mgba_libretro"
                                "nes", "nez" -> "mesen_libretro"
                                else -> "mgba_libretro"
                            }
                            val found = corePathFor(coreName, coresDir)
                            if (found != null) {
                                playingCore = found
                                playingRom = path
                            }
                        }
                    }
                },
                onDownloadCores = onDownloadCores,
                isDesktop = false,
                singleScreen = true,
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
