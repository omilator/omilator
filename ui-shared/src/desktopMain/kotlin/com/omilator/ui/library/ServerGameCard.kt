package com.omilator.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omilator.data.library.GameSystem

/**
 * The UI-facing model for a game on a libteca server. Carries everything
 * the card needs: identity, platform, download state, and the local file
 * once downloaded.
 */
data class ServerGame(
    val workId: Long,
    val editionId: Long,
    val fileId: Long,
    val title: String,
    val platformTag: String,
    val platformName: String,
    val fileSizeBytes: Long,
) {
    val system: GameSystem? get() = gameSystemFromPlatformTag(platformTag)

    /** Set by the ViewModel once the ROM is fully downloaded. */
    var localFile: java.io.File? = null
    var downloadProgress: Float = -1f // -1 = not started, 0..1 = downloading, 1 = done
}

/**
 * Maps libteca's `game-<platform>` edition format tag to omilator's
 * GameSystem, so the same system filter chips and platform badge colors
 * work identically for local and server games.
 */
fun gameSystemFromPlatformTag(tag: String): GameSystem? = when (tag) {
    "nes" -> GameSystem.NES
    "snes" -> GameSystem.SNES
    "gb" -> GameSystem.GAME_BOY
    "gbc" -> GameSystem.GAME_BOY_COLOR
    "gba" -> GameSystem.GAME_BOY_ADVANCE
    "genesis" -> GameSystem.GENESIS
    "n64" -> GameSystem.NINTENDO_64
    "psx" -> GameSystem.PLAYSTATION
    "nds" -> GameSystem.NINTENDO_DS
    "psp" -> GameSystem.PSP
    "gamecube" -> GameSystem.GAMECUBE
    "wii" -> GameSystem.WII
    "n3ds" -> GameSystem.NINTENDO_3DS
    "ps2" -> GameSystem.PLAYSTATION_2
    "dreamcast" -> GameSystem.DREAMCAST
    "saturn" -> GameSystem.SATURN
    else -> null
}

/**
 * Card for a server game: same shape as GameCard (gradient + platform
 * badge + title) but with a download/play action overlay instead of a
 * click-to-open. States: not downloaded (download button), downloading
 * (progress bar), downloaded (play button).
 */
@Composable
fun ServerGameCard(
    game: ServerGame,
    onDownload: () -> Unit,
    onPlay: (java.io.File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.04f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "serverCardScale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (isHovered) 24f else 6f,
        animationSpec = tween(durationMillis = 180),
        label = "serverCardElevation",
    )

    val system = game.system
    val topColor = system?.let { coverTopFor(it) } ?: Color(0xFF555555)
    val bottomColor = topColor.copy(alpha = 0.55f)

    Column(
        modifier = modifier
            .hoverable(interactionSource)
            .graphicsLayer(scaleX = scale, scaleY = scale),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.88f)
                .shadow(
                    elevation = elevation.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.6f),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(topColor, bottomColor))),
            contentAlignment = Alignment.BottomStart,
        ) {
            system?.let { sys ->
                Box(
                    modifier = Modifier.padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = sys.shortLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Action overlay — top-right
            val isDownloaded = game.localFile != null
            val isDownloading = game.downloadProgress in 0f..1f && !isDownloaded

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                when {
                    isDownloaded -> {
                        IconButton(
                            onClick = { game.localFile?.let(onPlay) },
                            modifier = Modifier.padding(2.dp),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Play ${game.title}",
                                tint = Color.White,
                            )
                        }
                    }
                    isDownloading -> {
                        // Progress ring
                        Box(
                            modifier = Modifier.padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                progress = { game.downloadProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.padding(2.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier.padding(2.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Download ${game.title}",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // Download progress bar — bottom edge
            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { game.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = game.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// Local access to the private cover colors from GameCard.kt — mirrored here
// because Kotlin private top-level functions can't be shared across files.
private fun coverTopFor(system: GameSystem): Color = when (system) {
    GameSystem.NES -> Color(0xFFB23A48)
    GameSystem.SNES -> Color(0xFF4A6FA5)
    GameSystem.GAME_BOY -> Color(0xFF6B8E23)
    GameSystem.GAME_BOY_COLOR -> Color(0xFFD1772B)
    GameSystem.GAME_BOY_ADVANCE -> Color(0xFF8B3A62)
    GameSystem.GENESIS -> Color(0xFF2D4356)
    GameSystem.NINTENDO_64 -> Color(0xFF5D4E75)
    GameSystem.PLAYSTATION -> Color(0xFF1F4E79)
    GameSystem.NINTENDO_DS -> Color(0xFF777777)
    GameSystem.PSP -> Color(0xFF003366)
    GameSystem.GAMECUBE -> Color(0xFF6A0DAD)
    GameSystem.WII -> Color(0xFF8BABC4)
    GameSystem.NINTENDO_3DS -> Color(0xFFA52A2A)
    GameSystem.PLAYSTATION_2 -> Color(0xFF1E4D88)
    GameSystem.DREAMCAST -> Color(0xFFFF6600)
    GameSystem.SATURN -> Color(0xFF3399CC)
}
