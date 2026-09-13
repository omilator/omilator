package com.omilator.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omilator.data.library.Game
import com.omilator.data.library.GameSystem
import androidx.compose.material3.OutlinedTextField

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onAddDirectory: () -> Unit,
    onOpenGame: (String) -> Unit,
    onQuickPlay: () -> Unit = {},
    onLaunchStandalone: () -> Unit = {},
    onOpenGameSettings: (String) -> Unit = {},
    showStandalone: Boolean = true,
    cardMinSize: Int = 180,
    onSettings: () -> Unit = {},
    showTitle: Boolean = true,
    showRefresh: Boolean = true,
    showQuickPlay: Boolean = true,
    serverPage: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()

    // Build pages: "All" + one per available system + optional "Server"
    val hasServer = serverPage != null
    val pages: List<GameSystem?> = if (hasServer) {
        listOf<GameSystem?>(null) + state.availableSystems + listOf<GameSystem?>(null)
    } else {
        listOf<GameSystem?>(null) + state.availableSystems
    }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size },
    )
    val currentPage by derivedStateOf { pagerState.currentPage }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { if (showTitle) Text("", style = MaterialTheme.typography.headlineLarge) },
                navigationIcon = {
                    if (onSettings != {}) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                actions = {
                    if (showQuickPlay) {
                        IconButton(onClick = onQuickPlay) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Open ROM")
                        }
                    }
                    if (showRefresh) {
                        IconButton(onClick = { viewModel.rescan() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                        }
                    }
                    IconButton(onClick = onAddDirectory) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )

            state.error?.let { err ->
                Text("Error: $err", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                state.games.isEmpty() -> EmptyState(onAddDirectory, onQuickPlay)
                else -> {
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { pageIndex ->
                        // Server page is the last page
                        if (hasServer && pageIndex == pages.size - 1) {
                            serverPage?.invoke()
                            return@HorizontalPager
                        }
                        val systemFilter = pages.getOrNull(pageIndex)
                        val pageGames = if (systemFilter != null) {
                            state.games.filter { it.system == systemFilter }
                        } else {
                            val q = state.searchQuery.trim().lowercase()
                            if (q.isEmpty()) state.games
                            else state.games.filter { it.title.lowercase().contains(q) }
                        }

                        Column {
                            if (pageIndex == 0 && !(hasServer && pageIndex == pages.size - 1)) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = { viewModel.setSearch(it) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    placeholder = { Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
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
                            }
                            LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = cardMinSize.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(pageGames, key = { it.id }) { game ->
                                GameCard(
                                    game = game,
                                    onClick = { onOpenGame(game.filePath) },
                                    onOpenSettings = { onOpenGameSettings(game.filePath) },
                                )
                            }
                        }
                        }
                    }

                    // Dot indicator — ALL dots visible, label BELOW each
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        pages.forEachIndexed { index, system ->
                            val label = when {
                                hasServer && index == pages.size - 1 -> "Server"
                                system == null -> "All"
                                else -> system.shortLabel()
                            }
                            val isSelected = index == currentPage
                            Column(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                    .padding(horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(3.5.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant
                                        )
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = {
            Text(
                "Search",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SystemFilterRow(
    systems: List<GameSystem>,
    selected: GameSystem?,
    onSelect: (GameSystem?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        items(systems) { system ->
            FilterChip(
                selected = selected == system,
                onClick = { onSelect(system) },
                label = { Text(system.shortLabel()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
            Text(
                "Scanning library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(onAddDirectory: () -> Unit, onQuickPlay: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Large glyph-style emoji-free icon — use a stylized text char
            Text(
                "○",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your library is empty",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Add a directory of ROMs to begin, or open a single file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AssistChip(
                    onClick = onAddDirectory,
                    label = { Text("Add directory") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                AssistChip(
                    onClick = onQuickPlay,
                    label = { Text("Open ROM") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun NoMatchesState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "No matches",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Try a different search or system filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GridState(
    games: List<Game>,
    onOpenGame: (String) -> Unit,
    onOpenGameSettings: (String) -> Unit = {},
    cardMinSize: Int = 180,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = cardMinSize.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(games, key = { it.id }) { game ->
            GameCard(
                game = game,
                onClick = { onOpenGame(game.filePath) },
                onOpenSettings = { onOpenGameSettings(game.filePath) },
            )
        }
    }
}

// shortLabel() is defined in GameCard.kt

