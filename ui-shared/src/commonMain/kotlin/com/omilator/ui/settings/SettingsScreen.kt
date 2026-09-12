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
)

class SettingsViewModel(
    private val settingsStore: SettingsStore? = null,
    private val settingsPath: String = "",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setTheme(theme: AppTheme) {
        _state.value = _state.value.copy(theme = theme)
        persist()
    }

    fun setDirectories(dirs: List<String>) {
        _state.value = _state.value.copy(libraryDirectories = dirs)
        persist()
    }

    fun addDirectory(dir: String) {
        val current = _state.value.libraryDirectories.toMutableList()
        if (dir !in current) current += dir
        setDirectories(current)
    }

    fun removeDirectory(dir: String) {
        setDirectories(_state.value.libraryDirectories - dir)
    }

    fun setCoresStatus(installed: Int, total: Int) {
        _state.value = _state.value.copy(coresInstalled = installed, coresTotal = total)
    }

    fun setCoresDownloading(downloading: Boolean, status: String = "") {
        _state.value = _state.value.copy(coresDownloading = downloading, coresStatus = status)
    }

    fun setEmulatorsStatus(installed: Int, total: Int) {
        _state.value = _state.value.copy(emulatorsInstalled = installed, emulatorsTotal = total)
    }

    fun setEmulatorsDownloading(downloading: Boolean, status: String = "") {
        _state.value = _state.value.copy(emulatorsDownloading = downloading, emulatorsStatus = status)
    }

    fun setTheGamesDbApiKey(key: String) {
        _state.value = _state.value.copy(theGamesDbApiKey = key)
        persist()
    }

    /**
     * Persist the current theme + API key + libraryDirectories to the
     * SettingsStore. No-op if no store/path was provided (legacy callers).
     * A read-modify-write copy, so fields this screen does not own survive.
     */
    private fun persist() {
        val store = settingsStore ?: return
        val s = _state.value
        scope.launch {
            store.updateAppSettings(settingsPath) {
                it.copy(
                    theme = s.theme,
                    libraryDirectories = s.libraryDirectories,
                    theGamesDbApiKey = s.theGamesDbApiKey,
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
                                IconButton(onClick = { viewModel.removeDirectory(dir) }) {
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
