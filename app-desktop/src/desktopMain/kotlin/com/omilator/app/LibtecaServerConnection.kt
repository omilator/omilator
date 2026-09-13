package com.omilator.app

import com.omilator.data.library.LibtecaLibrarySource
import com.omilator.ui.library.ServerGame
import com.omilator.ui.library.ServerLibraryViewModel
import java.io.File

/**
 * Bridges LibtecaLibrarySource (the raw API client) to the ViewModel's
 * ServerConnection interface (what the UI consumes). Translates server
 * works/editions into ServerGame models the card rendering understands.
 */
class LibtecaServerConnection(
    baseUrl: String,
    token: String,
    cacheDir: File,
) : ServerLibraryViewModel.ServerConnection {

    private val source = LibtecaLibrarySource(baseUrl, token, cacheDir)

    override fun listGames(): List<ServerGame> {
        val games = mutableListOf<ServerGame>()
        for (lib in source.gamesLibraries()) {
            for (page in 0 until 100) { // bounded: 100 pages × 200 = 20k games
                val works = source.works(lib.id, limit = 200, offset = page * 200)
                if (works.isEmpty()) break
                for (work in works) {
                    for (ed in work.editions) {
                        // Strip "game-" prefix; skip non-game formats defensively
                        if (!ed.format.startsWith("game-")) continue
                        val detail = try {
                            source.work(work.id)
                        } catch (_: Exception) {
                            null
                        }
                        val file = detail?.editions
                            ?.firstOrNull { it.id == ed.id }
                            ?.files?.firstOrNull() ?: continue
                        games.add(
                            ServerGame(
                                workId = work.id,
                                editionId = ed.id,
                                fileId = file.id,
                                title = work.title,
                                platformTag = ed.format.removePrefix("game-"),
                                platformName = ed.title,
                                fileSizeBytes = file.size,
                            ),
                        )
                    }
                }
                if (works.size < 200) break
            }
        }
        return games
    }

    override fun downloadRom(
        game: ServerGame,
        onProgress: (Float) -> Unit,
    ): File? = try {
        kotlinx.coroutines.runBlocking {
            source.downloadRom(game.fileId, game.fileSizeBytes) { bytes, total ->
                if (total > 0) onProgress(bytes.toFloat() / total)
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun playtime(editionId: Long, seconds: Int) {
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    source.reportPlaytime(editionId, seconds)
                }
            } catch (_: Exception) {}
        }.start()
    }
}
