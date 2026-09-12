package com.omilator.data.saves

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SaveState(
    val id: String,
    val gameId: String,
    val slot: Int,
    val createdAt: Instant,
    val thumbnailPath: String? = null,
    val sizeBytes: Long,
)

class SaveStateRepository(
    private val listDir: suspend (String) -> List<String>,
    private val fileSize: suspend (String) -> Long?,
    private val fileModifiedAt: suspend (String) -> Long?,
) {
    suspend fun list(gameId: String, directory: String): List<SaveState> {
        val gameKey = gameId.substringAfterLast('/')
        val pattern = Regex("^" + Regex.escape(gameKey) + """\.slot(\d+)\.state$""")
        return listDir(directory).mapNotNull { path ->
            val match = pattern.matchEntire(path.substringAfterLast('/')) ?: return@mapNotNull null
            SaveState(
                id = path,
                gameId = gameId,
                slot = match.groupValues[1].toInt(),
                createdAt = Instant.fromEpochMilliseconds(fileModifiedAt(path) ?: 0L),
                sizeBytes = fileSize(path) ?: 0L,
            )
        }.sortedBy { it.slot }
    }
}
