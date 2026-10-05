package com.omilator.data.library

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ROM download checksum verification: when the server payload provides a
 * sha256, the completed download must match it (stream-hashed); a mismatch
 * must fail the download and truncate the cache so a retry re-downloads;
 * an absent hash must behave exactly as the size-only path.
 */
class LibtecaChecksumTest {

    private lateinit var server: HttpServer
    private lateinit var cache: File

    private val size = 4096
    private val bytes = ByteArray(size) { (it % 251).toByte() }
    private val hits = AtomicInteger()

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun start() {
        cache = Files.createTempDirectory("omilator-libteca").toFile()
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/core/stream/42") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        cache.deleteRecursively()
    }

    private fun source(): LibtecaLibrarySource =
        LibtecaLibrarySource(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            token = "tok",
            cacheDir = cache,
        )

    @Test
    fun matchingChecksumPasses() = runBlocking {
        val local = source().downloadRom(42, size.toLong(), sha256Hex(bytes))
        assertEquals(1, hits.get())
        assertTrue(local.readBytes().contentEquals(bytes), "verified download kept")
    }

    @Test
    fun mismatchingChecksumFailsTruncatesAndRetryingRedownloads() = runBlocking {
        val src = source()
        val wrong = sha256Hex(ByteArray(size) { (it % 249).toByte() })

        val failure = assertFailsWith<IllegalArgumentException> {
            src.downloadRom(42, size.toLong(), wrong)
        }
        assertTrue(failure.message.orEmpty().contains("sha256 mismatch"))
        assertEquals(1, hits.get())

        // The failed download must not leave its bytes behind as a
        // resumable cache entry.
        val cached = cache.walkTopDown().filter { it.isFile }.single()
        assertEquals(0L, cached.length(), "mismatched cache truncated to zero")

        val fixed = src.downloadRom(42, size.toLong(), sha256Hex(bytes))
        assertEquals(2, hits.get(), "retry re-downloaded from zero")
        assertTrue(fixed.readBytes().contentEquals(bytes), "retry produced good bytes")
    }

    @Test
    fun absentChecksumBehavesAsSizeOnly() = runBlocking {
        val local = source().downloadRom(42, size.toLong())
        assertEquals(1, hits.get())
        assertTrue(local.readBytes().contentEquals(bytes))
    }

    @Test
    fun fileDetailSha256FieldIsOptional() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val without = json.decodeFromString<LibtecaLibrarySource.FileDetail>("""{"id":7,"size":123}""")
        assertEquals(null, without.sha256)
        val with = json.decodeFromString<LibtecaLibrarySource.FileDetail>(
            """{"id":7,"size":123,"sha256":"abc123"}""",
        )
        assertEquals("abc123", with.sha256)
    }
}
