package com.omilator.data.library

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Download-cache identity regression tests: file ids are only unique
 * within one libteca server, so cached ROMs must be namespaced by server,
 * and completed cache entries must match the expected size exactly.
 */
class LibtecaCacheIdentityTest {

    private lateinit var serverA: HttpServer
    private lateinit var serverB: HttpServer
    private lateinit var cache: File

    private val size = 4096
    private val bytesA = ByteArray(size) { (it % 251).toByte() }
    private val bytesB = ByteArray(size) { ((it * 7 + 13) % 251).toByte() }
    private val hitsA = AtomicInteger()
    private val hitsB = AtomicInteger()

    @BeforeTest
    fun start() {
        cache = Files.createTempDirectory("omilator-libteca").toFile()
        serverA = startServer(bytesA, hitsA)
        serverB = startServer(bytesB, hitsB)
    }

    @AfterTest
    fun stop() {
        serverA.stop(0)
        serverB.stop(0)
        cache.deleteRecursively()
    }

    private fun startServer(bytes: ByteArray, hits: AtomicInteger): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/core/stream/42") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        return server
    }

    private fun source(server: HttpServer): LibtecaLibrarySource =
        LibtecaLibrarySource(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            token = "tok",
            cacheDir = cache,
        )

    @Test
    fun sameFileIdOnDifferentServersCannotShareCachedBytes() = runBlocking {
        val fromA = source(serverA).downloadRom(42, size.toLong())
        assertEquals(1, hitsA.get())
        assertTrue(fromA.readBytes().contentEquals(bytesA), "server A bytes")

        // Server B exposes the same file id + size with different bytes:
        // it must NOT be served server A's cached copy.
        val fromB = source(serverB).downloadRom(42, size.toLong())
        assertEquals(1, hitsB.get(), "server B was contacted")
        assertTrue(fromB.readBytes().contentEquals(bytesB), "server B bytes")
        assertTrue(fromA.readBytes().contentEquals(bytesA), "server A cache intact")
        assertTrue(fromA.absolutePath != fromB.absolutePath, "per-server cache paths")
    }

    @Test
    fun exactSizedCacheEntryIsServedWithoutServerContact() = runBlocking {
        val src = source(serverA)
        val local = src.downloadRom(42, size.toLong())
        assertEquals(1, hitsA.get())

        var completed = false
        val again = src.downloadRom(42, size.toLong()) { bytes, total ->
            if (bytes == total.toLong()) completed = true
        }
        assertEquals(1, hitsA.get(), "cache hit must not contact the server")
        assertEquals(local.absolutePath, again.absolutePath)
        assertTrue(completed, "completion progress reported")
    }

    @Test
    fun oversizedCacheEntryIsRejectedAndRedownloaded() = runBlocking {
        val src = source(serverA)
        val local = src.downloadRom(42, size.toLong())
        assertEquals(1, hitsA.get())

        // Corrupt the completed cache entry with trailing garbage.
        java.io.RandomAccessFile(local, "rw").use {
            it.seek(size.toLong())
            it.write(ByteArray(512))
        }
        assertEquals((size + 512).toLong(), local.length())

        val fixed = src.downloadRom(42, size.toLong())
        assertEquals(2, hitsA.get(), "oversized entry must be re-downloaded")
        assertEquals(size.toLong(), fixed.length(), "exact size restored")
        assertTrue(fixed.readBytes().contentEquals(bytesA), "exact bytes restored")
    }
}
