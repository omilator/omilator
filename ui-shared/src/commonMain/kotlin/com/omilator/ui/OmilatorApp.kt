package com.omilator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.omilator.data.library.LibraryRepository
import com.omilator.data.library.LibraryScanner
import com.omilator.data.settings.SettingsStore
import com.omilator.ui.adaptive.WindowSizeClass
import com.omilator.ui.adaptive.currentWindowSizeClass
import com.omilator.ui.library.LibraryScreen
import com.omilator.ui.library.LibraryViewModel
import com.omilator.ui.settings.SettingsScreen
import com.omilator.ui.settings.SettingsViewModel

enum class OmilatorDestination { LIBRARY, SETTINGS }

@Composable
fun OmilatorApp(
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
    onAddRomDirectory: () -> Unit,
    onPlayRom: (String) -> Unit = {},
    onQuickPlay: () -> Unit = {},
    onLaunchStandalone: () -> Unit = {},
    onDownloadCores: () -> Unit = {},
    onOpenGameSettings: (String) -> Unit = {},
    onDownloadEmulators: () -> Unit = {},
    isDesktop: Boolean = false,
    singleScreen: Boolean = false,
    serverPage: (@Composable () -> Unit)? = null,
) {
    val windowClass = currentWindowSizeClass()
    var destination by rememberSaveable { mutableStateOf(OmilatorDestination.LIBRARY) }

    val isExpanded = windowClass == WindowSizeClass.EXPANDED

    val settingsState by settingsViewModel.state.collectAsState()
    val forceDark = when (settingsState.theme) {
        com.omilator.data.settings.AppTheme.SYSTEM -> null
        com.omilator.data.settings.AppTheme.LIGHT -> false
        com.omilator.data.settings.AppTheme.DARK -> true
    }

    OmilatorTheme(forceDark = forceDark) {
        if (singleScreen) {
            if (destination == OmilatorDestination.LIBRARY) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onAddDirectory = onAddRomDirectory,
                    onOpenGame = onPlayRom,
                    onQuickPlay = onQuickPlay,
                    onLaunchStandalone = onLaunchStandalone,
                    onOpenGameSettings = onOpenGameSettings,
                    showStandalone = isDesktop,
                    cardMinSize = 130,
                    onSettings = { destination = OmilatorDestination.SETTINGS },
                    showTitle = false,
                    showRefresh = !singleScreen,
                    showQuickPlay = !singleScreen,
                    serverPage = serverPage,
                )
            } else {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onAddDirectory = onAddRomDirectory,
                    onDownloadCores = onDownloadCores,
                    onDownloadEmulators = onDownloadEmulators,
                    isDesktop = isDesktop,
                    onBack = { destination = OmilatorDestination.LIBRARY },
                )
            }
        } else if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    NavigationRailItem(
                        selected = destination == OmilatorDestination.LIBRARY,
                        onClick = { destination = OmilatorDestination.LIBRARY },
                        icon = { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Library") },
                        label = { Text("Library") },
                    )
                    NavigationRailItem(
                        selected = destination == OmilatorDestination.SETTINGS,
                        onClick = { destination = OmilatorDestination.SETTINGS },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
                when (destination) {
                    OmilatorDestination.LIBRARY -> LibraryScreen(
                        viewModel = libraryViewModel,
                        onAddDirectory = onAddRomDirectory,
                        onOpenGame = onPlayRom,
                        onQuickPlay = onQuickPlay,
                        onLaunchStandalone = onLaunchStandalone,
                        showStandalone = isDesktop,
                        onOpenGameSettings = onOpenGameSettings,
                        serverPage = serverPage,
                )
                    OmilatorDestination.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onAddDirectory = onAddRomDirectory,
                        onDownloadCores = onDownloadCores,
                            onDownloadEmulators = onDownloadEmulators,
                            isDesktop = isDesktop,
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = destination == OmilatorDestination.LIBRARY,
                            onClick = { destination = OmilatorDestination.LIBRARY },
                            icon = { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Library") },
                            label = { Text("Library") },
                        )
                        NavigationBarItem(
                            selected = destination == OmilatorDestination.SETTINGS,
                            onClick = { destination = OmilatorDestination.SETTINGS },
                            icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (destination) {
                        OmilatorDestination.LIBRARY -> LibraryScreen(
                            viewModel = libraryViewModel,
                            onAddDirectory = onAddRomDirectory,
                            onOpenGame = onPlayRom,
                            onQuickPlay = onQuickPlay,
                            onLaunchStandalone = onLaunchStandalone,
                        showStandalone = isDesktop,
                        onOpenGameSettings = onOpenGameSettings,
                            serverPage = serverPage,
                )
                        OmilatorDestination.SETTINGS -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onAddDirectory = onAddRomDirectory,
                            onDownloadCores = onDownloadCores,
                            onDownloadEmulators = onDownloadEmulators,
                            isDesktop = isDesktop,
                        )
                    }
                }
            }
        }
    }
}
