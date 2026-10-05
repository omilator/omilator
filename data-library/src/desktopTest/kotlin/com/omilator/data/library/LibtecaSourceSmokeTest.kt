package com.omilator.data.library

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract smoke against a fake libteca implementing exactly
 * libteca's docs/omilator-client-contract.md: libraries filter, works page,
 * work detail, Range-resumed download, progress POST. The real binary's
 * Range behavior was live-verified on the libteca side (1 GiB file); this
 * pins the client side of the same contract.
 */
class LibtecaSourceSmokeTest {

    private lateinit var server: HttpServer
    private lateinit var source: LibtecaLibrarySource
    private lateinit var cache: File
    private val rom = ByteArray(300_000) { (it % 251).toByte() }
    private var sawAuth = false
    private var sawRange: String? = null
    private var streamHits = 0
    private var progressBody: String? = null

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun start() {
        cache = Files.createTempDirectory("omilator-libteca").toFile()
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/core/libraries") { ex ->
            sawAuth = ex.requestHeaders.getFirst("Authorization") == "Bearer tok"
            ex.respond(
                200,
                """[{"id":1,"name":"Roms","type":"games","path":"/x"},{"id":2,"name":"Audio","type":"audiobooks"}]""",
            )
        }
        server.createContext("/api/core/libraries/1/works") { ex ->
            ex.respond(
                200,
                """[{"id":10,"title":"Super Mario World","hasCover":false,"editions":[{"id":100,"format":"game-snes","title":"Super Nintendo","files":1}]}]""",
            )
        }
        server.createContext("/api/core/works/10") { ex ->
            ex.respond(
                200,
                """{"id":10,"title":"Super Mario World","hasCover":false,"editions":[{"id":100,"format":"game-snes","title":"Super Nintendo","files":[{"id":500,"size":${rom.size}}]}]}""",
            )
        }
        server.createContext("/api/core/stream/500") { ex ->
            streamHits++
            sawRange = ex.requestHeaders.getFirst("Range")
            val from = sawRange?.substringAfter("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L
            if (from > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${rom.size - 1}/${rom.size}")
                ex.sendResponseHeaders(206, (rom.size - from).toLong())
                ex.responseBody.write(rom, from.toInt(), (rom.size - from).toInt())
            } else {
                ex.sendResponseHeaders(200, rom.size.toLong())
                ex.responseBody.write(rom)
            }
            ex.close()
        }
        server.createContext("/api/core/progress/100") { ex ->
            if (ex.requestMethod == "GET") {
                ex.respond(200, """{"editionId":100,"position":3600,"isFinished":false,"revision":0}""")
            } else {
                progressBody = ex.requestBody.readBytes().decodeToString()
                ex.respond(200, """{"ok":true}""")
            }
        }
        server.start()
        source = LibtecaLibrarySource(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            token = "tok",
            cacheDir = cache,
        )
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        cache.deleteRecursively()
    }

    private fun HttpExchange.respond(code: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test
    fun fullContractFlow() = runBlocking {
        val libs = source.gamesLibraries()
        assertEquals(listOf(1L), libs.map { it.id }, "audiobooks filtered out")
        assertTrue(sawAuth, "bearer token sent")

        val works = source.works(1)
        assertEquals(1, works.size)
        assertEquals("game-snes", works[0].editions[0].format)

        val detail = source.work(10)
        val file = detail.editions[0].files[0]
        assertEquals(500L, file.id)

        val local = source.downloadRom(file.id, file.size)
        assertTrue(local.length() == rom.size.toLong(), "download size")
        assertTrue(local.readBytes().contentEquals(rom), "download bytes")

        // Simulate a partial cache, then resume.
        java.io.RandomAccessFile(local, "rw").use { it.setLength(123_456) }
        val resumed = source.downloadRom(file.id, file.size)
        assertTrue(resumed.length() == rom.size.toLong(), "resumed size")
        assertEquals("bytes=123456-", sawRange, "range resume requested")
        assertTrue(resumed.readBytes().contentEquals(rom), "resumed bytes identical")

        // Cumulative playtime: the stored position is fetched first so the
        // client can add the session duration before POSTing the sum. An
        // unreadable position is null, not zero — zero is a real value.
        assertEquals(3600, source.playtimePosition(100), "stored playtime position")
        assertEquals(null, source.playtimePosition(999), "unknown edition reads as null")

        assertTrue(source.reportPlaytime(100, 42))
        assertTrue(progressBody!!.contains("\"position\":42"), "progress body: $progressBody")
    }

    @Test
    fun sameSizeCorruptCacheIsRejectedWhenShaKnown() = runBlocking {
        val digest = sha256Hex(rom)
        val local = source.downloadRom(500, rom.size.toLong(), digest)
        assertTrue(local.readBytes().contentEquals(rom), "initial download bytes")

        // Corrupt in place, keeping the exact byte length: only the digest
        // can tell this cache hit from a good one.
        java.io.RandomAccessFile(local, "rw").use {
            it.seek(10)
            it.write(byteArrayOf(1, 2, 3, 4))
        }

        val hitsBefore = streamHits
        val replaced = source.downloadRom(500, rom.size.toLong(), digest)
        assertTrue(streamHits > hitsBefore, "corrupt same-size cache re-contacted the stream endpoint")
        assertTrue(replaced.length() == rom.size.toLong(), "replaced size")
        assertTrue(replaced.readBytes().contentEquals(rom), "cache replaced with good bytes")
    }

    @Test
    fun sameSizeCacheIsTrustedWhenShaUnknown() = runBlocking {
        val local = source.downloadRom(500, rom.size.toLong())
        java.io.RandomAccessFile(local, "rw").use {
            it.seek(10)
            it.write(byteArrayOf(1, 2, 3, 4))
        }

        // No digest = byte-identical old size-only cache-hit path.
        val hitsBefore = streamHits
        val cached = source.downloadRom(500, rom.size.toLong())
        assertEquals(hitsBefore, streamHits, "size-only hit must not re-download")
        assertEquals(rom.size.toLong(), cached.length(), "cache returned unchanged")
    }
}
