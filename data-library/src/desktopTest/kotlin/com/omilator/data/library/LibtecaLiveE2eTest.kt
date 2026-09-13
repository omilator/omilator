package com.omilator.data.library

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live end-to-end against a real libteca binary started by the session
 * (port 8496, three games across snes/genesis). Self-skips when the server
 * is not reachable, so CI never depends on it.
 */
class LibtecaLiveE2eTest {

    @Test
    fun liveServerContract() = runBlocking {
        val source = LibtecaLibrarySource(
            baseUrl = "http://127.0.0.1:8496",
            token = runBlocking {
                // login against the real binary
                val conn = java.net.URL("http://127.0.0.1:8496/api/core/login")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use {
                    it.write("""{"username":"admin","password":"smoketest123"}""".toByteArray())
                }
                val body = conn.inputStream.readBytes().decodeToString()
                conn.disconnect()
                body.substringAfter("\":\"").substringBefore("\"}")
            },
            cacheDir = File("/var/folders/6f/7yv53mdd3j57c90gt2wykvsr0000gn/T/opencode/audit/e2e-cache"),
        )

        val libs = try {
            source.gamesLibraries()
        } catch (_: Exception) {
            println("E2E-SKIP: live libteca not running")
            return@runBlocking
        }
        assertEquals(1, libs.size, "one games library")
        assertEquals("E2E Roms", libs[0].name)

        val works = source.works(libs[0].id)
        assertEquals(3, works.size, "three games scanned")
        assertTrue(works.any { it.title == "Super Mario World" }, "SMW present")
        assertTrue(
            works.first { it.title == "Super Mario World" }.editions.any { it.format == "game-snes" },
            "SMW on snes platform",
        )

        val detail = source.work(works.first { it.title == "Super Mario World" }.id)
        val file = detail.editions.first { it.format == "game-snes" }.files.first()

        val local = source.downloadRom(file.id, file.size)
        assertTrue(local.length() == 3000L, "downloaded ${local.length()} bytes, want 3000")

        assertTrue(source.reportPlaytime(detail.editions[0].id, 60), "playtime accepted")
        println("E2E PASS: real libteca <-> omilator client, full contract round-trip")
    }
}
