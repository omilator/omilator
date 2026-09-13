package com.omilator.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Libteca games library source (PLAN-GAMES G4): browses a libteca server's
 * games libraries over the documented client contract
 * (libteca docs/omilator-client-contract.md), downloads ROMs into a local
 * cache with resume, and reports playtime back.
 *
 * JVM implementation - HttpURLConnection, same convention as every other
 * downloader in this module. The contract is the core API; there is no
 * named protocol because this app is the only client.
 */
class LibtecaLibrarySource(
    private val baseUrl: String,
    private val token: String,
    private val cacheDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun interface ProgressListener {
        fun onProgress(bytes: Long, total: Long)
    }

    @Serializable
    data class Library(val id: Long, val name: String, val type: String, val path: String? = null)

    @Serializable
    data class Edition(
        val id: Long,
        val format: String,
        val title: String,
        val files: Long = 0,
    )

    @Serializable
    data class Work(
        val id: Long,
        val title: String,
        val author: String? = null,
        val hasCover: Boolean = false,
        val editions: List<Edition> = emptyList(),
    )

    @Serializable
    data class WorksPage(val works: List<Work>, val total: Int = 0)

    @Serializable
    data class WorkDetail(
        val id: Long,
        val title: String,
        val hasCover: Boolean = false,
        val editions: List<EditionDetail> = emptyList(),
    )

    @Serializable
    data class EditionDetail(
        val id: Long,
        val format: String,
        val title: String,
        val files: List<FileDetail> = emptyList(),
    )

    @Serializable
    data class FileDetail(val id: Long, val size: Long = 0)

    /** Games libraries on the server, in server order. */
    fun gamesLibraries(): List<Library> {
        val conn = URL(baseUrl.trimEnd('/') + "/api/core/libraries").openAuthed()
        val body = conn.inputStream.use { it.readBytes() }
        val code = conn.responseCode
        conn.disconnect()
        require(code == 200) { "libraries: HTTP $code" }
        return json.decodeFromString<List<Library>>(body.decodeToString()).filter { it.type == "games" }
    }

    /** One page of games in a library. */
    fun works(libraryId: Long, limit: Int = 200, offset: Int = 0): List<Work> {
        val u = URL(
            baseUrl.trimEnd('/') + "/api/core/libraries/$libraryId/works?sort=title&limit=$limit&offset=$offset",
        )
        val conn = u.openAuthed()
        val body = conn.inputStream.use { it.readBytes() }
        val code = conn.responseCode
        conn.disconnect()
        require(code == 200) { "works: HTTP $code" }
        return json.decodeFromString<List<Work>>(body.decodeToString())
    }

    /** Detail for one work: editions with downloadable file ids. */
    fun work(workId: Long): WorkDetail {
        val u = URL(baseUrl.trimEnd('/') + "/api/core/works/$workId")
        val conn = u.openAuthed()
        val body = conn.inputStream.use { it.readBytes() }
        val code = conn.responseCode
        conn.disconnect()
        require(code == 200) { "work: HTTP $code" }
        return json.decodeFromString<WorkDetail>(body.decodeToString())
    }

    /**
     * Cover bytes for a work, or null. The endpoint takes the cover file
     * name from the works listing; callers that did not fetch one skip this.
     */
    fun cover(coverPath: String): ByteArray? = try {
        val conn = URL(baseUrl.trimEnd('/') + "/api/core/covers/" + coverPath.trimStart('/')).openAuthed()
        val body = if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        conn.disconnect()
        body
    } catch (_: Exception) {
        null
    }

    /**
     * Downloads a ROM into cacheDir/<fileId>.rom with Range resume, keyed on
     * the stable file id + size per the contract. Returns the local file.
     */
    suspend fun downloadRom(
        fileId: Long,
        size: Long,
        onProgress: ProgressListener? = null,
    ): File = withContext(Dispatchers.IO) {
        val dst = File(cacheDir.apply { mkdirs() }, "$fileId.rom")
        var have = if (dst.exists()) dst.length() else 0L
        if (size > 0 && have >= size) {
            onProgress?.onProgress(size, size)
            return@withContext dst
        }
        val u = URL(baseUrl.trimEnd('/') + "/api/core/stream/$fileId")
        val conn = u.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val partial = conn.responseCode == 206
            require(conn.responseCode == 200 || partial) { "stream: HTTP ${conn.responseCode}" }
            if (conn.responseCode == 200 && have > 0) {
                // Server ignored the range: restart the file.
                have = 0
            }
            val total = if (size > 0) size else (have + conn.contentLengthLong.coerceAtLeast(0))
            val out = java.io.RandomAccessFile(dst, "rw")
            out.seek(have)
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    have += n
                    onProgress?.onProgress(have, total)
                }
            }
            out.close()
            dst
        } finally {
            conn.disconnect()
        }
    }

    /** Reports play seconds back as the contract's progress position. */
    suspend fun reportPlaytime(editionId: Long, secondsPlayed: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val u = URL(baseUrl.trimEnd('/') + "/api/core/progress/$editionId")
            val conn = u.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"position":$secondsPlayed,"percent":null}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun URL.openAuthed(): HttpURLConnection =
        (openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }
}
