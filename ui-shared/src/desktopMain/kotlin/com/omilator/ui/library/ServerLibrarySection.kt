package com.omilator.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omilator.data.library.GameSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for the server library page: fetches games from a libteca
 * server, tracks per-game download state, and exposes a list that the
 * same grid rendering as local games can display.
 *
 * Desktop-only (uses LibtecaLibrarySource via the source lambda) — the
 * composable lives in commonMain so mobile can adopt it when the mobile
 * client arrives.
 */
class ServerLibraryViewModel(
    private val connect: () -> ServerConnection?,
) {
    /** Per-fileId download state. Absent from the map = never downloaded. */
    data class DownloadState(
        val progress: Float = 0f,
        val localFile: File? = null,
    )

    data class State(
        val isLoading: Boolean = true,
        val error: String? = null,
        val games: List<ServerGame> = emptyList(),
        val downloads: Map<Long, DownloadState> = emptyMap(),
        val searchQuery: String = "",
        val selectedSystem: GameSystem? = null,
    ) {
        val availableSystems: List<GameSystem>
            get() = games.mapNotNull { it.system }.distinct().sortedBy { it.name }

        val visibleGames: List<ServerGame>
            get() {
                val bySystem = selectedSystem?.let { sys -> games.filter { it.system == sys } } ?: games
                val q = searchQuery.trim().lowercase()
                return if (q.isEmpty()) bySystem else bySystem.filter { it.title.lowercase().contains(q) }
            }
    }

    /** Abstraction over LibtecaLibrarySource so commonMain stays clean. */
    interface ServerConnection {
        fun listGames(): List<ServerGame>
        fun downloadRom(game: ServerGame, onProgress: (Float) -> Unit): File?
        fun playtime(editionId: Long, seconds: Int)
        /** Current playtime position (seconds played) for an edition, or 0. */
        fun playtimePosition(editionId: Long): Int
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0

    fun refresh() {
        // Cancel-and-replace: a slow earlier refresh must not publish stale
        // games (or stale-credential errors) over a newer one's results.
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        _state.value = _state.value.copy(isLoading = true, error = null)
        refreshJob = scope.launch(Dispatchers.IO) {
            try {
                val conn = connect() ?: run {
                    if (generation == refreshGeneration) {
                        _state.value = _state.value.copy(isLoading = false, error = "Not configured")
                    }
                    return@launch
                }
                val games = conn.listGames()
                if (generation == refreshGeneration) {
                    _state.value = _state.value.copy(isLoading = false, games = games)
                }
            } catch (e: Exception) {
                if (generation == refreshGeneration) {
                    _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Connection failed")
                }
            }
        }
    }

    /** Cancels the private scope when the owning screen discards this VM. */
    fun close() {
        scope.cancel()
    }

    fun setSearch(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
    }

    fun selectSystem(system: GameSystem?) {
        _state.value = _state.value.copy(selectedSystem = system)
    }

    fun download(game: ServerGame) {
        // In-flight or already downloaded: nothing to do. Derived from
        // state, so the old unsynchronized in-flight set is gone.
        if (_state.value.downloads[game.fileId] != null) return
        _state.value = _state.value.copy(
            downloads = _state.value.downloads + (game.fileId to DownloadState(progress = 0f)),
        )

        scope.launch(Dispatchers.IO) {
            try {
                val conn = connect() ?: run {
                    clearDownload(game.fileId)
                    return@launch
                }
                val file = conn.downloadRom(game) { progress ->
                    // Throttle UI updates to avoid recomposition storm.
                    // StateFlow is updated with fresh immutable copies, so
                    // every emission is a real change Compose can observe.
                    if (progress >= 1f || (progress * 100).toInt() % 10 == 0) {
                        _state.update { s ->
                            val d = s.downloads[game.fileId] ?: return@update s
                            s.copy(downloads = s.downloads + (game.fileId to d.copy(progress = progress)))
                        }
                    }
                }
                if (file != null && file.exists()) {
                    _state.update { s ->
                        s.copy(
                            downloads = s.downloads +
                                (game.fileId to DownloadState(progress = 1f, localFile = file)),
                        )
                    }
                } else {
                    clearDownload(game.fileId)
                }
            } catch (e: Exception) {
                clearDownload(game.fileId)
            }
        }
    }

    private fun clearDownload(fileId: Long) {
        _state.update { s -> s.copy(downloads = s.downloads - fileId) }
    }

    fun reportPlaytime(game: ServerGame, sessionSeconds: Int) {
        if (_state.value.downloads[game.fileId]?.localFile == null) return
        scope.launch(Dispatchers.IO) {
            try {
                val conn = connect() ?: return@launch
                // The server's progress position is a cumulative play-seconds
                // counter set by the POST, so the session duration adds onto
                // the stored position. Capped just under the server's 30-day
                // policy limit for editions without a known duration.
                val existing = conn.playtimePosition(game.editionId)
                val total = (existing + sessionSeconds).coerceAtMost(30 * 24 * 60 * 60 - 1)
                conn.playtime(game.editionId, total)
            } catch (_: Exception) {}
        }
    }
}

/**
 * The "Server" page inside the library pager: same grid + filter chips +
 * search as local games, but with download/play overlay on each card.
 */
@Composable
fun ServerLibrarySection(
    viewModel: ServerLibraryViewModel,
    onPlayGame: (File, ServerGame) -> Unit,
    cardMinSize: Int = 180,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.setSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = {
                Text(
                    "Search server",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            state.error != null -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Cannot reach server: ${state.error}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.games.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No games on the server",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // System filter chips (same style as local)
                if (state.availableSystems.size > 1) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedSystem == null,
                                onClick = { viewModel.selectSystem(null) },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                        rowItems(state.availableSystems) { system ->
                            FilterChip(
                                selected = state.selectedSystem == system,
                                onClick = { viewModel.selectSystem(system) },
                                label = { Text(system.shortLabel()) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = cardMinSize.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.visibleGames, key = { it.fileId }) { game ->
                        ServerGameCard(
                            game = game,
                            download = state.downloads[game.fileId],
                            onDownload = { viewModel.download(game) },
                            onPlay = { file -> onPlayGame(file, game) },
                        )
                    }
                }
            }
        }
    }
}
