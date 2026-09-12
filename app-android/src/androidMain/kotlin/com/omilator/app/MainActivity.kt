package com.omilator.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.omilator.core.audio.createAudioOutputFactory
import com.omilator.core.libretro.createCoreController
import com.omilator.data.library.AndroidCoreDownloader
import com.omilator.data.library.AndroidLibraryScanner
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.SettingsStore
import com.omilator.ui.OmilatorApp
import com.omilator.ui.library.LibraryViewModel
import com.omilator.ui.player.MobilePlayerScreen
import com.omilator.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var libraryViewModel: LibraryViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var coreDownloader: AndroidCoreDownloader

    /**
     * Current ROM+core paths when the player is active. Null = library view.
     * Hoisted out of Compose so it survives configuration changes (rotation).
     */
    private val playingRom = mutableStateOf<String?>(null)
    private val playingCore = mutableStateOf<String?>(null)

    /**
     * SAF directory picker. Android requires the Storage Access Framework for
     * directory access — the standard java.io.File picker doesn't work for
     * external storage on API 30+. We persist the resulting URI so we can
     * re-request access on subsequent launches without re-prompting.
     */
    private val dirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist permission so we keep access across launches.
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            // For now we treat the URI opaquely — IosLibraryScanner/AndroidLibraryScanner
            // work off filesystem paths. The user is expected to also drop ROMs into
            // <filesDir>/Documents which is auto-scanned.
            libraryViewModel.addDirectory(uri.toString())
            settingsViewModel.addDirectory(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configDir = File(filesDir, "config").apply { mkdirs() }
        val coresDir = File(filesDir, "cores").apply { mkdirs() }
        val settingsPath = File(configDir, "settings.json").absolutePath
        val settingsStore = SettingsStore(
            readText = { path -> File(path).takeIf { it.exists() }?.readText() },
            writeText = { path, content -> File(path).writeText(content) },
        )

        libraryViewModel = LibraryViewModel(
            repository = LibraryRepository(AndroidLibraryScanner(applicationContext)),
            settingsStore = settingsStore,
            settingsPath = settingsPath,
        )
        settingsViewModel = SettingsViewModel(settingsStore, settingsPath)
        coreDownloader = AndroidCoreDownloader(coresDir)

        // Pre-populate core counts so Settings reflects reality.
        settingsViewModel.setCoresStatus(
            coreDownloader.installedCount(),
            coreDownloader.cores.size,
        )

        // Auto-scan the app's private Documents directory on cold launch.
        // ROMs in /sdcard/Download require the SAF picker — see dirPickerLauncher.
        lifecycleScope.launch(Dispatchers.IO) {
            libraryViewModel.rescan(listOf(File(filesDir, "Documents").absolutePath))
        }

        setContent {
            val rom by remember { playingRom }
            val core by remember { playingCore }
            val downloadScope = rememberCoroutineScope()

            if (rom != null && core != null) {
                // Player screen — constructed fresh per session so the core
                // + audio get clean state. onExit returns to library.
                val coreController = remember { createCoreController("") }
                val audioOutput = remember { createAudioOutputFactory().create() }
                MobilePlayerScreen(
                    romPath = rom!!,
                    corePath = core!!,
                    coreController = coreController,
                    audioOutput = audioOutput,
                    onExit = {
                        playingRom.value = null
                        playingCore.value = null
                    },
                )
            } else {
                OmilatorApp(
                    libraryViewModel = libraryViewModel,
                    settingsViewModel = settingsViewModel,
                    onAddRomDirectory = { dirPickerLauncher.launch(null) },
                    isDesktop = false,
                    singleScreen = true,
                    onPlayRom = { path ->
                        // Resolve core by ROM extension, check bundled first
                        // then downloaded cores.
                        downloadScope.launch(Dispatchers.IO) {
                            // SAF content:// URIs are not paths a core can
                            // fopen — materialize the ROM into the cache dir
                            // and play that copy.
                            val playPath = if (path.startsWith("content://")) {
                                runCatching {
                                    val name = path.substringAfterLast('%').substringAfterLast('/')
                                    val out = File(cacheDir, "rom-$name")
                                    contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                                        out.outputStream().use { input.copyTo(it) }
                                    } ?: return@launch
                                    out.absolutePath
                                }.getOrElse { return@launch }
                            } else path
                            val coreName = coreNameForRom(playPath)
                            val bundled = bundledCorePath(coreName)
                            val downloaded = File(coresDir, "$coreName.so").absolutePath
                            val resolved = bundled ?: downloaded.takeIf { File(it).exists() }
                            if (resolved != null) {
                                playingCore.value = resolved
                                playingRom.value = playPath
                            }
                        }
                    },
                    onDownloadCores = {
                        downloadScope.launch(Dispatchers.IO) {
                            settingsViewModel.setCoresDownloading(true, "Starting...")
                            val installed = coreDownloader.downloadAll { status ->
                                settingsViewModel.setCoresDownloading(true, status)
                            }
                            settingsViewModel.setCoresStatus(installed, coreDownloader.cores.size)
                            settingsViewModel.setCoresDownloading(false, "Installed $installed/${coreDownloader.cores.size}")
                        }
                    },
                )
            }
        }
    }

    /** Returns the bundled .so path if a core ships inside the APK's jniLibs. */
    private fun bundledCorePath(coreName: String): String? {
        // Cores bundled via jniLibs/<abi>/lib<coreName>.so are auto-loaded
        // into the app's nativeLibraryDir.
        val nativeDir = applicationInfo.nativeLibraryDir
        val candidate = File(nativeDir, "lib$coreName.so")
        return if (candidate.exists()) candidate.absolutePath else null
    }

    private fun coreNameForRom(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gba", "gb", "gbc", "sgb" -> "mgba_libretro"
            "nes", "nez" -> "mesen_libretro"
            "sfc", "smc" -> "snes9x_libretro"
            "iso", "cso", "prx" -> "ppsspp_libretro"
            "n64", "z64", "v64" -> "mupen64plus_next_libretro"
            else -> "mgba_libretro"
        }
    }
}
