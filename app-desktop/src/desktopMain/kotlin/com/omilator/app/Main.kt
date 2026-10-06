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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import com.omilator.data.library.GameSystem
import com.omilator.data.library.JvmLibraryScanner
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.DesktopPaths
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFrame

fun main() {
    // Bootstrap runs once, before any Compose code: constructing the store
    // and loading settings inside the composition (and hydrating through
    // the persisting setters) rewrote persisted libteca credentials with
    // empty strings on every cold start — the setters copy all owned
    // fields, including the two they never loaded — and re-ran on every
    // recomposition of the startup block.
    val configDir = defaultConfigDir()
    val settingsPath = File(configDir, "settings.json").absolutePath
    val settingsStore = SettingsStore(
        readText = { path -> File(path).takeIf { it.exists() }?.readText() },
        writeText = { path, content -> atomicWriteText(java.nio.file.Paths.get(path), content) },
    )
    val initialSettings = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        settingsStore.loadAppSettings(settingsPath)
    }

    application {
    val windowState = rememberWindowState(
        size = DpSize(1200.dp, 760.dp),
        position = WindowPosition(Alignment.Center),
    )

    /** A playing session tracks which ROM is up, which server game (if any)
     *  it belongs to, and when it started — server playtime is reported with
     *  the real session duration when the player exits. The reporting target
     *  is captured with the session: consulting the current page ViewModel
     *  at exit would report through whatever server the settings point at
     *  by then (or a closed scope, dropping the playtime silently). */
    data class PlayingSession(
        val romPath: String,
        val serverGame: ServerGame? = null,
        val reportPlaytime: ((ServerGame, Int) -> Unit)? = null,
        val startedAtNanos: Long = System.nanoTime(),
        /** Known platform when the filename cannot say: server downloads
         *  are extensionless .rom cache files, so the system travels with
         *  the session instead of being re-derived from the extension. */
        val systemOverride: GameSystem? = null,
    )

    var playing by remember { mutableStateOf<PlayingSession?>(null) }

    val coresDir = remember { DesktopPaths.coresDir }
    val libraryViewModel = remember {
        LibraryViewModel(
            repository = LibraryRepository(JvmLibraryScanner()),
            settingsStore = settingsStore,
            settingsPath = settingsPath,
        )
    }
    val settingsViewModel = remember {
        SettingsViewModel(settingsStore, settingsPath, initial = initialSettings)
    }

    // Libteca server library (PLAN-GAMES G4): the Server page appears as the
    // last tab in the library pager when a server is configured in Settings.
    // Derived from collected settings state, so configuring/removing a
    // server after startup adds/removes the tab on the spot.
    val settingsState by settingsViewModel.state.collectAsState()
    val serverConfigured = settingsState.libtecaServerUrl.isNotBlank() &&
        settingsState.libtecaServerToken.isNotBlank()

    // Fresh view-model per server configuration: switching servers must not
    // show the old server's games/downloads under new credentials.
    val serverViewModel = remember(settingsState.libtecaServerUrl, settingsState.libtecaServerToken) {
        ServerLibraryViewModel(connect = {
            val url = settingsViewModel.state.value.libtecaServerUrl.trim()
            val tok = settingsViewModel.state.value.libtecaServerToken.trim()
            if (url.isEmpty() || tok.isEmpty()) null
            else LibtecaServerConnection(url, tok, File(configDir, "rom-cache"))
        })
    }
    DisposableEffect(settingsState.libtecaServerUrl, settingsState.libtecaServerToken) {
        onDispose { serverViewModel.close() }
    }
    val stopPlaying = {
        val session = playing
        if (session != null) {
            val seconds = ((System.nanoTime() - session.startedAtNanos) / 1_000_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            session.serverGame?.let { game ->
                session.reportPlaytime?.invoke(game, seconds)
            }
            playing = null
        }
    }

    // Session reporting runs on this application-lifetime scope: it must
    // outlive the server page (and its ViewModel) that started the session,
    // and reporting must not leak a detached thread per session.
    val sessionReportScope = rememberCoroutineScope()
    var launchError by remember { mutableStateOf<String?>(null) }

    val serverPage: (@Composable () -> Unit)? = if (serverConfigured) {
        {
            ServerLibrarySection(
                viewModel = serverViewModel,
                onPlayGame = { file, game ->
                    // The download cache is extensionless (.rom): the
                    // platform is only known from server metadata. Refuse
                    // to guess a core from the filename.
                    val system = game.system
                    if (system == null) {
                        launchError = "Unsupported server platform: ${game.platformTag}"
                    } else {
                        playRom(file.absolutePath, system) { romPath ->
                            val url = settingsViewModel.state.value.libtecaServerUrl.trim()
                            val tok = settingsViewModel.state.value.libtecaServerToken.trim()
                            val reporter: ((ServerGame, Int) -> Unit)? =
                                if (url.isEmpty() || tok.isEmpty()) null
                                else {
                                    val conn = LibtecaServerConnection(url, tok, File(configDir, "rom-cache"))
                                    val report: (ServerGame, Int) -> Unit = { g, secs ->
                                        sessionReportScope.launch {
                                            try {
                                                conn.reportSession(g.editionId, secs)
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                System.err.println("[Omilator] Session report failed")
                                            }
                                        }
                                    }
                                    report
                                }
                            playing = PlayingSession(romPath, game, reporter, systemOverride = system)
                        }
                    }
                },
            )
        }
    } else null

    // ---- First-run auto-setup: download missing cores + emulators ----
    var setupNeeded by remember { mutableStateOf(false) }
    var setupStatus by remember { mutableStateOf("") }
    var setupProgress by remember { mutableStateOf(0f) }
    var setupRunId by remember { mutableStateOf(0) }
    var setupInProgress by remember { mutableStateOf(false) }

    val coreDownloader = remember { CoreDownloader(coresDir) }
    val emulatorInstaller = remember { EmulatorInstaller() }

    // First-run auto-download, keyed on setupRunId so Retry (or any later
    // re-run) restarts it; a failed attempt must not leave the loop having
    // only counted steps. The missing-components check lives INSIDE the
    // effect: a plain `if` in the composition body re-evaluated on every
    // recomposition, so clicking Skip was undone the next time any observed
    // state changed and the dialog came back. setupInProgress serializes
    // writers — coroutine cancellation cannot interrupt a blocking HTTP
    // download, so a Retry during a running install would otherwise start a
    // second concurrent one.
    LaunchedEffect(setupRunId) {
        if (!setupNeeded) {
            val coresMissing = coreDownloader.cores.size - coreDownloader.installedCount()
            val emulatorsMissing = emulatorInstaller.emulators.size - emulatorInstaller.installedCount()
            if (coresMissing > 0 || emulatorsMissing > 0) {
                setupNeeded = true
            }
        }
        if (!setupNeeded || setupInProgress) return@LaunchedEffect
        setupInProgress = true
        try {
            withContext(Dispatchers.IO) {
                setupProgress = 0f
                val coresMissing = coreDownloader.cores.count { !coreDownloader.isInstalled(it) }
                val emulatorsMissing = emulatorInstaller.emulators.count { !emulatorInstaller.isInstalled(it) }
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

                // Recompute reality instead of trusting the loop's completion:
                // a network failure leaves components missing, and declaring
                // success would dismiss setup while they stay absent.
                val stillMissingCores = coreDownloader.cores.count { !coreDownloader.isInstalled(it) }
                val stillMissingEmulators = emulatorInstaller.emulators.count { !emulatorInstaller.isInstalled(it) }
                if (stillMissingCores + stillMissingEmulators == 0) {
                    setupStatus = "Setup complete"
                    setupNeeded = false
                } else {
                    setupProgress = 1f
                    setupStatus = "Setup incomplete: $stillMissingCores core(s) and " +
                        "$stillMissingEmulators emulator(s) could not be installed"
                }
                settingsViewModel.setCoresStatus(coreDownloader.installedCount(), coreDownloader.cores.size)
                settingsViewModel.setEmulatorsStatus(emulatorInstaller.installedCount(), emulatorInstaller.emulators.size)
            }
        } finally {
            setupInProgress = false
        }
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
        // Startup marker for the CI smoke test: proves the Compose window
        // actually initialized, not just that the JVM process survived.
        LaunchedEffect(Unit) { println("OMILATOR_STARTUP_OK") }
        val session = playing
        if (session != null) {
            OmilatorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                                stopPlaying()
                                true
                            } else false
                        },
                ) {
                    // Key the player to the session identity: remembered
                    // engine/audio inside PlayerScreen are keyed by ROM path
                    // alone, so replacing a session in the same composition
                    // could reuse a released AudioOutput for a new engine.
                    key(session) {
                        PlayerScreen(
                            gameId = session.romPath,
                            onClose = stopPlaying,
                            systemOverride = session.systemOverride,
                        )
                    }
                }
            }
        } else {
            val appScope = rememberCoroutineScope()
            OmilatorApp(
                libraryViewModel = libraryViewModel,
                settingsViewModel = settingsViewModel,
                onAddRomDirectory = onAddRomDirectory,
                isDesktop = true,
                onPlayRom = { path -> playRom(path) { romPath -> playing = PlayingSession(romPath) } },
                onQuickPlay = {
                    pickRomFile()?.let { path -> playRom(path) { romPath -> playing = PlayingSession(romPath) } }
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
                    confirmButton = {
                        TextButton(
                            onClick = { setupRunId++ },
                            enabled = !setupInProgress,
                        ) {
                            Text("Retry")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { setupNeeded = false }) {
                            Text("Skip")
                        }
                    },
                )
            }
        }

        // Server games with an unrecognized platform tag cannot launch (the
        // .rom cache carries no extension to guess from) — surface it
        // instead of silently picking a core.
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

private fun playRom(
    romPath: String,
    systemOverride: GameSystem? = null,
    useLibretro: (String) -> Unit,
) {
    // The standalone-emulator fallback is a macOS .app launcher; on other
    // desktops the blocklist below only prevented the libretro path before
    // routing into a launcher that cannot work there.
    if (!isMacOS) {
        useLibretro(romPath)
        return
    }
    // Server downloads are extensionless .rom cache files: route them by
    // their known platform instead of the filename, or an explicit server
    // PSP/GameCube/Wii game would slip past the standalone safety route.
    val blockedSystemId = if (systemOverride != null) {
        when (systemOverride) {
            GameSystem.PSP -> "psp"
            GameSystem.GAMECUBE, GameSystem.WII -> "gamecube_wii"
            else -> null
        }
    } else {
        when (File(romPath).extension.lowercase()) {
            "iso", "cso", "prx" -> "psp"
            "wbfs", "gcz", "wad", "gcm" -> "gamecube_wii"
            else -> null
        }
    }
    if (blockedSystemId == null) {
        // Not a HW-render-blocked system — use libretro
        useLibretro(romPath)
        return
    }
    val registry = StandaloneRegistry()
    val standalone = registry.forSystem(blockedSystemId)
    if (standalone != null) {
        println("[Omilator] Routing $blockedSystemId to ${standalone.displayName} (standalone)")
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
