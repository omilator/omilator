package com.omilator.ui.library

import com.omilator.data.library.Game
import com.omilator.data.library.GameSystem
import com.omilator.data.library.LibraryRepository
import com.omilator.data.settings.AppSettings
import com.omilator.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val games: List<Game> = emptyList(),
    val selectedSystem: GameSystem? = null,
    val searchQuery: String = "",
    val scannedDirectories: List<String> = emptyList(),
    val error: String? = null,
) {
    val visibleGames: List<Game>
        get() {
            val bySystem = selectedSystem?.let { sys -> games.filter { it.system == sys } } ?: games
            val q = searchQuery.trim().lowercase()
            return if (q.isEmpty()) bySystem else bySystem.filter { it.title.lowercase().contains(q) }
        }

    val availableSystems: List<GameSystem>
        get() = games.map { it.system }.distinct().sortedBy { it.displayName }
}

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val settingsStore: SettingsStore?,
    private val settingsPath: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        loadSettingsAndScan()
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun selectSystem(system: GameSystem?) {
        _state.value = _state.value.copy(selectedSystem = system)
    }

    fun addDirectory(directory: String) {
        val current = _state.value.scannedDirectories.toMutableList()
        if (directory !in current) current += directory
        val updated = _state.value.copy(scannedDirectories = current)
        _state.value = updated
        persistSettings(current)
        rescan(current)
    }

    fun removeDirectory(directory: String) {
        val current = _state.value.scannedDirectories - directory
        _state.value = _state.value.copy(scannedDirectories = current)
        persistSettings(current)
        rescan(current)
    }

    fun rescan() = rescan(_state.value.scannedDirectories)

    fun rescan(directories: List<String>) {
        if (directories.isEmpty()) return
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val all = mutableListOf<Game>()
                for (dir in directories) {
                    all += repository.rescan(dir)
                }
                _state.value = _state.value.copy(isLoading = false, games = all)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(isLoading = false, error = t.message ?: "Scan failed")
            }
        }
    }

    private fun loadSettingsAndScan() {
        scope.launch {
            val store = settingsStore ?: return@launch
            val settings = store.loadAppSettings(settingsPath)
            _state.value = _state.value.copy(scannedDirectories = settings.libraryDirectories)
            if (settings.libraryDirectories.isNotEmpty()) rescan(settings.libraryDirectories)
        }
    }

    private fun persistSettings(directories: List<String>) {
        val store = settingsStore ?: return
        scope.launch {
            store.updateAppSettings(settingsPath) { it.copy(libraryDirectories = directories) }
        }
    }
}
