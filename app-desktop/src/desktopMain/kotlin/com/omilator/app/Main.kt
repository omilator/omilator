package com.omilator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.omilator.data.launcher.EmulatorInstaller
import com.omilator.data.launcher.StandaloneRegistry
import com.omilator.data.library.CoreDownloader
import com.omilator.data.library.JvmLibraryScanner
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.AppSettings
import com.omilator.data.settings.SettingsStore
import com.omilator.data.settings.defaultConfigDir
import com.omilator.ui.OmilatorApp
import com.omilator.ui.OmilatorTheme
import com.omilator.ui.library.LibraryViewModel
import com.omilator.ui.library.ServerGame
import com.omilator.ui.library.ServerLibraryViewModel
import com.omilator.ui.library.ServerLibrarySection
import com.omilator.data.library.LibtecaLibrarySource
import com.omilator.ui.player.PlayerScreen
import com.omilator.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFrame

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(1200.dp, 760.dp),
        position = WindowPosition(Alignment.Center),
    )

    var playing by remember { mutableStateOf<String?>(null) }

    val configDir = remember { defaultConfigDir() }
    val settingsPath = remember { File(configDir, "settings.json").absolutePath }
    val settingsStore = remember {
        SettingsStore(
            readText = { path -> File(path).takeIf { it.exists() }?.readText() },
            writeText = { path, content -> atomicWriteText(java.nio.file.Paths.get(path), content) },
        )
    }
    val coresDir = remember { File(configDir, "cores") }
    val libraryViewModel = remember {
        LibraryViewModel(
            repository = LibraryRepository(JvmLibraryScanner()),
            settingsStore = settingsStore,
            settingsPath = settingsPath,
        )
    }
    val settingsViewModel = remember { SettingsViewModel(settingsStore, settingsPath) }

    // Libteca server library (PLAN-GAMES G4): the Server page appears as the
    // last tab in the library pager when a server is configured in Settings.
    val serverViewModel = remember {
        ServerLibraryViewModel(connect = {
            val url = settingsViewModel.state.value.libtecaServerUrl.trim()
            val tok = settingsViewModel.state.value.libtecaServerToken.trim()
            if (url.isEmpty() || tok.isEmpty()) null
            else LibtecaServerConnection(url, tok, File(configDir, "rom-cache"))
        })
    }
    val serverPage = remember<(@Composable () -> Unit)?> {
        if (settingsViewModel.state.value.libtecaServerUrl.isNotBlank()) {
            {
                ServerLibrarySection(
                    viewModel = serverViewModel,
                    onPlayGame = { file, game ->
                        playRom(file.absolutePath) { romPath -> playing = romPath }
                        serverViewModel.reportPlaytime(game, 0)
                    },
                )
            }
        } else null
    }

    // Load persisted settings on startup
    kotlinx.coroutines.runBlocking {
        val settings = settingsStore.loadAppSettings(settingsPath)
        settingsViewModel.setTheme(settings.theme)
        settingsViewModel.setTheGamesDbApiKey(settings.theGamesDbApiKey)
        settingsViewModel.setDirectories(settings.libraryDirectories)
    }

    // ---- First-run auto-setup: download missing cores + emulators ----
    var setupNeeded by remember { mutableStateOf(false) }
    var setupStatus by remember { mutableStateOf("") }
    var setupProgress by remember { mutableStateOf(0f) }

    // Check on startup what's missing
    val coreDownloader = remember { CoreDownloader(coresDir) }
    val emulatorInstaller = remember { EmulatorInstaller() }
    val coresMissing = coreDownloader.cores.size - coreDownloader.installedCount()
    val emulatorsMissing = emulatorInstaller.emulators.size - emulatorInstaller.installedCount()

    if (coresMissing > 0 || emulatorsMissing > 0) {
        setupNeeded = true
    }

    // Run the auto-download
    LaunchedEffect(Unit) {
        if (!setupNeeded) return@LaunchedEffect
        val totalSteps = coresMissing + emulatorsMissing
        var done = 0

        // Cores first
        for (entry in coreDownloader.cores) {
            if (!coreDownloader.isInstalled(entry)) {
                setupStatus = "Downloading ${entry.name} (${entry.system})..."
                coreDownloader.download(entry) { }
                done++
                setupProgress = done.toFloat() / totalSteps
            }
        }

        // Then emulators
        for (spec in emulatorInstaller.emulators) {
            if (!emulatorInstaller.isInstalled(spec)) {
                setupStatus = "Downloading ${spec.displayName}..."
                emulatorInstaller.install(spec) { msg -> setupStatus = msg }
                done++
                setupProgress = done.toFloat() / totalSteps
            }
        }

        setupStatus = "Setup complete"
        settingsViewModel.setCoresStatus(coreDownloader.installedCount(), coreDownloader.cores.size)
        settingsViewModel.setEmulatorsStatus(emulatorInstaller.installedCount(), emulatorInstaller.emulators.size)
        setupNeeded = false
    }

    val onAddRomDirectory: () -> Unit = {
        pickRomDirectory()?.let { dir ->
            libraryViewModel.addDirectory(dir)
            settingsViewModel.addDirectory(dir)
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Omilator",
        state = windowState,
    ) {
        val romPath = playing
        if (romPath != null) {
            OmilatorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                                playing = null
                                true
                            } else false
                        },
                ) {
                    PlayerScreen(
                        gameId = romPath,
                        onClose = { playing = null },
                    )
                }
            }
        } else {
            val appScope = rememberCoroutineScope()
            OmilatorApp(
                libraryViewModel = libraryViewModel,
                settingsViewModel = settingsViewModel,
                onAddRomDirectory = onAddRomDirectory,
                isDesktop = true,
                onPlayRom = { path -> playRom(path) { romPath -> playing = romPath } },
                onQuickPlay = {
                    pickRomFile()?.let { path -> playRom(path) { romPath -> playing = romPath } }
                },
                onLaunchStandalone = {
                    pickRomFile()?.let { path -> launchStandalone(path) }
                },
                onDownloadCores = {
                    appScope.launch(Dispatchers.IO) {
                        val downloader = CoreDownloader(coresDir)
                        settingsViewModel.setCoresDownloading(true, "Starting...")
                        settingsViewModel.setCoresStatus(downloader.installedCount(), downloader.cores.size)
                        val installed = downloader.downloadAll { status ->
                            settingsViewModel.setCoresDownloading(true, status)
                        }
                        settingsViewModel.setCoresStatus(installed, downloader.cores.size)
                        settingsViewModel.setCoresDownloading(false, "Done: $installed/${downloader.cores.size} cores installed")
                    }
                },
                onOpenGameSettings = { romPath -> openGameSettings(romPath) },
                serverPage = serverPage,
                onDownloadEmulators = {
                    appScope.launch(Dispatchers.IO) {
                        val installer = EmulatorInstaller()
                        settingsViewModel.setEmulatorsStatus(installer.installedCount(), installer.emulators.size)
                        settingsViewModel.setEmulatorsDownloading(true, "Starting...")
                        for (spec in installer.emulators) {
                            if (!installer.isInstalled(spec)) {
                                installer.install(spec) { status ->
                                    settingsViewModel.setEmulatorsDownloading(true, status)
                                }
                            }
                        }
                        settingsViewModel.setEmulatorsStatus(installer.installedCount(), installer.emulators.size)
                        settingsViewModel.setEmulatorsDownloading(false, "Done: ${installer.installedCount()}/${installer.emulators.size} emulators installed")
                    }
                },
            )
        }

        // First-run setup dialog overlay
        if (setupNeeded) {
            OmilatorTheme {
                AlertDialog(
                    onDismissRequest = { /* don't dismiss — wait for completion */ },
                    title = { Text("Setting up Omilator") },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                        ) {
                            CircularProgressIndicator(
                                progress = { setupProgress },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                setupStatus.ifBlank { "Checking for missing components..." },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { setupNeeded = false }) {
                            Text("Skip")
                        }
                    },
                )
            }
        }
    }
}

/**
 * Smart play: macOS blocks libretro HW render for PSP/GameCube/Wii
 * (GLFW main-thread conflict). For these systems:
 *   - If standalone installed → launch it
 *   - If not installed → show error dialog (do NOT attempt libretro,
 *     which would SIGBUS the JVM)
 * For all other systems → use libretro via the in-process player.
 */
private val isMacOS = System.getProperty("os.name").contains("Mac", ignoreCase = true)

private fun atomicWriteText(target: java.nio.file.Path, content: String) {
    val tmp = target.resolveSibling(".arcade-tmp-settings")
    java.nio.file.Files.writeString(tmp, content)
    try {
        java.nio.file.Files.move(tmp, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun playRom(romPath: String, useLibretro: (String) -> Unit) {
    // The standalone-emulator fallback is a macOS .app launcher; on other
    // desktops the blocklist below only prevented the libretro path before
    // routing into a launcher that cannot work there.
    if (!isMacOS) {
        useLibretro(romPath)
        return
    }
    val ext = File(romPath).extension.lowercase()
    val blockedSystemId = when (ext) {
        "iso", "cso", "prx" -> "psp"
        "wbfs", "gcz", "wad", "gcm" -> "gamecube_wii"
        else -> null
    }
    if (blockedSystemId == null) {
        // Not a HW-render-blocked system — use libretro
        useLibretro(romPath)
        return
    }
    val registry = StandaloneRegistry()
    val standalone = registry.forSystem(blockedSystemId)
    if (standalone != null) {
        println("[Omilator] Routing $ext to ${standalone.displayName} (standalone)")
        standalone.launch(romPath)
    } else {
        // HW-render-blocked system with no standalone installed.
        // Show error instead of crashing the JVM via libretro+GLFW.
        val appName = when (blockedSystemId) {
            "psp" -> "PPSSPP"
            "gamecube_wii" -> "Dolphin"
            "ps3" -> "RPCS3"
            "wii_u" -> "Cemu"
            "xbox" -> "xemu"
            else -> "the standalone emulator"
        }
        val url = when (blockedSystemId) {
            "psp" -> "https://ppsspp.org/downloads"
            "gamecube_wii" -> "https://dolphin-emu.org/download/"
            "ps3" -> "https://rpcs3.net/download"
            "wii_u" -> "https://cemu.info/releases/"
            "xbox" -> "https://xemu.app/releases/"
            else -> ""
        }
        println("[Omilator] BLOCKED: $blockedSystemId requires $appName (not installed)")
        println("[Omilator]   Install: $url")
        // Defer showing a dialog — for now just print.
        // TODO: surface as a Compose dialog.
    }
}

/**
 * Pick a ROM, detect its system, find a matching standalone backend,
 * and launch it. If no backend is installed for the system, prints to
 * stderr so the user knows which app to install.
 */
/**
 * Opens the appropriate settings for a game:
 * - Standalone systems: launches the standalone emulator's settings/config UI
 * - Libretro systems: prints a note (future: in-app settings dialog)
 */
private fun openGameSettings(romPath: String) {
    val ext = File(romPath).extension.lowercase()
    val systemId = when (ext) {
        "iso", "cso", "prx" -> "psp"
        "wbfs", "gcz", "wad", "gcm" -> "gamecube_wii"
        "pkg", "rap" -> "ps3"
        "wud", "wux" -> "wii_u"
        "xiso" -> "xbox"
        else -> null
    }
    val backend = systemId?.let { StandaloneRegistry().forSystem(it) }
    if (backend != null) {
        println("[Omilator] Opening ${backend.displayName} settings for $romPath")
        val proc = backend.openSettings()
        if (proc == null) {
            // Emulator has no settings-only launch mode.
            // Launch the GUI WITHOUT the ROM — user accesses settings there.
            // Do NOT launch the game.
            println("[Omilator] ${backend.displayName} has no settings flag — launching GUI without ROM")
            backend.openSettingsGuiOnly()
        }
    } else {
        println("[Omilator] No standalone settings for .$ext — libretro in-app settings coming soon")
    }
}

private fun launchStandalone(romPath: String) {
    val ext = File(romPath).extension.lowercase()
    val systemId = when (ext) {
        "iso" -> "psp"  // assume PSP for ISOs (most common modern-retro ISO)
        "cso", "prx" -> "psp"
        "wbfs", "gcz", "wad" -> "gamecube_wii"
        "gcm" -> "gamecube_wii"
        "pkg", "rap" -> "ps3"
        "wud", "wux" -> "wii_u"
        "xiso" -> "xbox"
        else -> null
    }
    if (systemId == null) {
        println("[Omilator] No standalone backend mapping for .$ext files")
        return
    }
    val backend = StandaloneRegistry().forSystem(systemId)
    if (backend == null) {
        println("[Omilator] No standalone backend installed for $systemId")
        println("[Omilator]   Install one of: PPSSPP (PSP), Dolphin (GC/Wii), RPCS3 (PS3), Cemu (Wii U), xemu (Xbox)")
        return
    }
    println("[Omilator] Launching ${backend.displayName} for $romPath")
    val proc = backend.launch(romPath)
    if (proc == null) {
        println("[Omilator] ${backend.displayName} launch failed")
    }
}

/**

 * Native macOS file picker via AWT FileDialog. Far more reliable than
 * JFileChooser on macOS — actually appears in front and respects system theme.
 */
/**
 * Native macOS directory picker. Uses apple.awt.fileDialogForDirectories
 * to show a proper folder-selection dialog (the default FileDialog only
 * selects files, which is why "Add directory" wasn't working).
 */
private fun pickRomDirectory(): String? {
    // Tell macOS to use directory-selection mode
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val frame = JFrame().apply { isUndecorated = true; isVisible = true; extendedState = Frame.ICONIFIED }
    return try {
        val dialog = FileDialog(frame, "Select ROM directory", FileDialog.LOAD)
        dialog.isVisible = true  // blocks until user picks or cancels
        // When fileDialogForDirectories=true, directory+file together form the path
        val dir = dialog.directory
        val file = dialog.file
        when {
            dir != null && file != null -> File(dir, file).takeIf { it.isDirectory }?.absolutePath
            dir != null -> File(dir).takeIf { it.isDirectory }?.absolutePath
            else -> null
        }
    } finally {
        frame.dispose()
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}

private fun pickRomFile(): String? {
    val frame = JFrame().apply { isUndecorated = true; isVisible = true; extendedState = Frame.ICONIFIED }
    return try {
        val dialog = FileDialog(frame, "Select ROM file", FileDialog.LOAD).apply {
            isMultipleMode = false
            // No filename filter — let the user pick any file. The macOS
            // native FileDialog greys out otherwise-valid files when a
            // FilenameFilter is set, which blocks .iso/.cso/.pbp etc.
            // We resolve the right core from the extension downstream.
            setVisible(true)
        }
        if (dialog.file != null && dialog.directory != null) {
            File(dialog.directory, dialog.file).absolutePath
        } else null
    } finally {
        frame.dispose()
    }
}

private fun exitApplication() {
    // Settings saved via the onCloseRequest handler in main()
    kotlin.system.exitProcess(0)
}
