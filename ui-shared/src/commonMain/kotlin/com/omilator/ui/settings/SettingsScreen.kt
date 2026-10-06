package com.omilator.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omilator.data.settings.AppTheme
import com.omilator.data.settings.AppSettings
import com.omilator.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val libraryDirectories: List<String> = emptyList(),
    val coresInstalled: Int = 0,
    val coresTotal: Int = 14,
    val coresDownloading: Boolean = false,
    val coresStatus: String = "",
    val emulatorsInstalled: Int = 0,
    val emulatorsTotal: Int = 3,
    val emulatorsDownloading: Boolean = false,
    val emulatorsStatus: String = "",
    val theGamesDbApiKey: String = "",
    val libtecaServerUrl: String = "",
    val libtecaServerToken: String = "",
)

class SettingsViewModel(
    private val settingsStore: SettingsStore? = null,
    private val settingsPath: String = "",
    /** Persisted snapshot used to seed the UI state. Loading must not be
     *  expressed as user edits: the setters all schedule persistence, and a
     *  default-valued ViewModel hydrated through them rewrote the persisted
     *  fields they do not cover (libteca URL/token) with empty strings on
     *  every cold start. */
    initial: AppSettings = AppSettings.DEFAULT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(
        SettingsUiState(
            theme = initial.theme,
            libraryDirectories = initial.libraryDirectories,
            theGamesDbApiKey = initial.theGamesDbApiKey,
            libtecaServerUrl = initial.libtecaServerUrl,
            libtecaServerToken = initial.libtecaServerToken,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setTheme(theme: AppTheme) {
        _state.update { it.copy(theme = theme) }
        persist()
    }

    fun setDirectories(dirs: List<String>) {
        _state.update { it.copy(libraryDirectories = dirs) }
        persist()
    }

    fun addDirectory(dir: String) {
        // update{} (CAS) end to end: read-copy-write here could drop a
        // concurrent status write (see the class comment below).
        _state.update { s ->
            if (dir in s.libraryDirectories) s
            else s.copy(libraryDirectories = s.libraryDirectories + dir)
        }
        persist()
    }

    fun removeDirectory(dir: String) {
        _state.update { s -> s.copy(libraryDirectories = s.libraryDirectories - dir) }
        persist()
    }

    fun setCoresStatus(installed: Int, total: Int) {
        _state.update { it.copy(coresInstalled = installed, coresTotal = total) }
    }

    fun setCoresDownloading(downloading: Boolean, status: String = "") {
        _state.update { it.copy(coresDownloading = downloading, coresStatus = status) }
    }

    fun setEmulatorsStatus(installed: Int, total: Int) {
        _state.update { it.copy(emulatorsInstalled = installed, emulatorsTotal = total) }
    }

    fun setEmulatorsDownloading(downloading: Boolean, status: String = "") {
        _state.update { it.copy(emulatorsDownloading = downloading, emulatorsStatus = status) }
    }

    fun setTheGamesDbApiKey(key: String) {
        _state.update { it.copy(theGamesDbApiKey = key) }
        persist()
    }

    fun setLibtecaServer(url: String, token: String) {
        _state.update { it.copy(libtecaServerUrl = url, libtecaServerToken = token) }
        persist()
    }

    /**
     * Persist the current theme + API key + libraryDirectories to the
     * SettingsStore. No-op if no store/path was provided (legacy callers).
     * A read-modify-write copy, so fields this screen does not own survive.
     *
     * All setters use `_state.update` (atomic CAS): first-run setup and
     * core downloads stream status writes from IO workers while the UI
     * thread edits settings — the read-copy-write form used before let
     * whichever thread wrote last erase the other's field (a theme toggle
     * reverted mid-download status text, a status write dropped a typed
     * server URL). The snapshot for persistence is taken when the launched
     * coroutine runs, not at call time, so it reflects every update that
     * landed before the write.
     */
    private fun persist() {
        val store = settingsStore ?: return
        scope.launch {
            val s = _state.value
            store.updateAppSettings(settingsPath) {
                it.copy(
                    theme = s.theme,
                    libraryDirectories = s.libraryDirectories,
                    theGamesDbApiKey = s.theGamesDbApiKey,
                    libtecaServerUrl = s.libtecaServerUrl,
                    libtecaServerToken = s.libtecaServerToken,
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddDirectory: () -> Unit,
    onDownloadCores: () -> Unit = {},
    onDownloadEmulators: () -> Unit = {},
    isDesktop: Boolean = false,
    onBack: () -> Unit = {},
    /** Notified alongside the settings-side removal so the library drops
     *  the directory from its live scan roots — without it the removed
     *  root kept being scanned (and its games shown) until restart. */
    onRemoveDirectory: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("< Back", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item {
            SettingsCard(title = "Appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                theme.displayName(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Switch(
                                checked = state.theme == theme,
                                onCheckedChange = { if (it) viewModel.setTheme(theme) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            }
        }

        item {
            // Cores section shown on both desktop and iOS. iOS downloader uses
            // host macOS CLI tools (vtool + codesign) — simulator-only, see
            // IosCoreDownloader. Desktop uses the JVM CoreDownloader.
            SettingsCard(title = "Emulator cores") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${state.coresInstalled} of ${state.coresTotal} cores installed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.coresStatus.isNotEmpty()) {
                        Text(
                            state.coresStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onDownloadCores,
                        enabled = !state.coresDownloading && state.coresInstalled < state.coresTotal,
                    ) {
                        Text(if (state.coresDownloading) "Downloading..." else "Download missing cores")
                    }
                }
            }
        }

        item {
            SettingsCard(
                title = "Library directories",
                trailing = {
                    IconButton(onClick = onAddDirectory) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add directory")
                    }
                },
            ) {
                if (state.libraryDirectories.isEmpty()) {
                    Text(
                        "No directories added.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.libraryDirectories.forEach { dir ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    dir,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 10.dp).weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = {
                                    viewModel.removeDirectory(dir)
                                    onRemoveDirectory(dir)
                                }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        if (isDesktop) item {
            SettingsCard(title = "Standalone emulators") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${state.emulatorsInstalled} of ${state.emulatorsTotal} installed (PPSSPP, xemu, RPCS3)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.emulatorsStatus.isNotEmpty()) {
                        Text(
                            state.emulatorsStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onDownloadEmulators,
                        enabled = !state.emulatorsDownloading,
                    ) {
                        Text(if (state.emulatorsDownloading) "Downloading..." else "Download missing emulators")
                    }
                }
            }
        }

        if (isDesktop) item {
            SettingsCard(title = "Libteca server") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Connect to a libteca games library to browse and download ROMs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = state.libtecaServerUrl,
                        onValueChange = { viewModel.setLibtecaServer(it, state.libtecaServerToken) },
                        placeholder = { Text("http://your-server:8096") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = state.libtecaServerToken,
                        onValueChange = { viewModel.setLibtecaServer(state.libtecaServerUrl, it) },
                        placeholder = { Text("API token") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (isDesktop) item {
            SettingsCard(title = "Cover art") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "TheGamesDB API key (optional — enables Pokemon/Nintendo covers)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = state.theGamesDbApiKey,
                        onValueChange = { viewModel.setTheGamesDbApiKey(it) },
                        placeholder = { Text("Free key from thegamesdb.net") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SettingsCard(title = "About") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingLine("Omilator", "0.2.0")
                    SettingLine("Engine", "Kotlin Multiplatform + Compose")
                    SettingLine("Cores", "libretro via FFM")
                    SettingLine("Launcher", "Standalone for PSP/GC/Wii/PS3/WiiU/Xbox")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trailing?.invoke()
            }
            Box(modifier = Modifier.padding(top = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AppTheme.displayName(): String = when (this) {
    AppTheme.SYSTEM -> "Match system"
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
}
