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
    private var progressBody: String? = null

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
            progressBody = ex.requestBody.readBytes().decodeToString()
            ex.respond(200, """{"ok":true}""")
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
        val partial = File(cache, "${file.id}.rom")
        java.io.RandomAccessFile(partial, "rw").use { it.setLength(123_456) }
        val resumed = source.downloadRom(file.id, file.size)
        assertTrue(resumed.length() == rom.size.toLong(), "resumed size")
        assertEquals("bytes=123456-", sawRange, "range resume requested")
        assertTrue(resumed.readBytes().contentEquals(rom), "resumed bytes identical")

        assertTrue(source.reportPlaytime(100, 42))
        assertTrue(progressBody!!.contains("\"position\":42"), "progress body: $progressBody")
    }
}
