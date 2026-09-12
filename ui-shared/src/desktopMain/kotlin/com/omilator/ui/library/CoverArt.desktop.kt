package com.omilator.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.omilator.data.library.CoverArtService
import com.omilator.data.library.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

@Composable
actual fun rememberCoverArt(game: Game): ImageBitmap? {
    val coverState = produceState<ImageBitmap?>(null, game.id) {
        val cacheDir = File(System.getProperty("user.home"), "Library/Application Support/Omilator/covers")
        // The configured TheGamesDB key is read from the settings file: the
        // composable tree has no settings plumbing, and without this the key
        // stored by the settings screen never reached the resolver.
        val settingsFile = File(cacheDir.parentFile, "settings.json")
        val apiKey = runCatching {
            if (settingsFile.exists()) {
                val m = Regex("\"theGamesDbApiKey\"\\s*:\\s*\"([^\"]*)\"").find(settingsFile.readText())
                m?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
        val service = CoverArtService(cacheDir, theGamesDbKey = apiKey)
        value = withContext(Dispatchers.IO) {
            runCatching {
                service.resolveCover(game)?.let { file ->
                    ImageIO.read(file)?.toComposeImageBitmap()
                }
            }.getOrNull()
        }
    }
    return coverState.value
}
