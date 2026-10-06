package com.omilator.ui.library

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playtime reporting semantics: a play session's seconds must add onto the
 * stored server position, a failed/unreadable position must not be
 * overwritten with a bare session total, and reporting must not depend on
 * the game being in the page's download-state map (the session may outlive
 * the ViewModel that downloaded it).
 */
class ServerLibraryPlaytimeTest {

    private class RecordingConnection(
        private val storedPosition: Int?,
    ) : ServerLibraryViewModel.ServerConnection {
        val reported = ConcurrentLinkedQueue<Int>()

        override suspend fun listGames(): List<ServerGame> = emptyList()
        override suspend fun downloadRom(game: ServerGame, onProgress: (Float) -> Unit): File? = null
        override suspend fun playtime(editionId: Long, seconds: Int) {
            reported.add(seconds)
        }

        override suspend fun playtimePosition(editionId: Long): Int? = storedPosition
    }

    private val game = ServerGame(
        workId = 1,
        editionId = 100,
        fileId = 500,
        title = "Super Mario World",
        platformTag = "snes",
        platformName = "Super Nintendo",
        fileSizeBytes = 1,
    )

    private val connections = mutableListOf<RecordingConnection>()

    private fun viewModel(storedPosition: Int?): ServerLibraryViewModel {
        val conn = RecordingConnection(storedPosition)
        connections.add(conn)
        return ServerLibraryViewModel(connect = { conn })
    }

    private fun awaitReported(conn: RecordingConnection, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (conn.reported.size < count) {
            check(System.currentTimeMillis() < deadline) {
                "timed out; reported so far: ${conn.reported.toList()}"
            }
            Thread.sleep(20)
        }
    }

    @AfterTest
    fun tearDown() {
        connections.clear()
    }

    @Test
    fun sessionSecondsAddOntoStoredPosition() {
        val vm = viewModel(storedPosition = 3600)
        vm.reportPlaytime(game, 42)
        val conn = connections.last()
        awaitReported(conn, 1)
        assertEquals(listOf(3642), conn.reported.toList())
        vm.close()
    }

    @Test
    fun unreadablePositionIsNotOverwrittenBySessionTotal() {
        val vm = viewModel(storedPosition = null)
        vm.reportPlaytime(game, 42)
        val conn = connections.last()
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(conn.reported.isEmpty(), "failed GET must not POST a bare session total")
        vm.close()
    }

    @Test
    fun reportingWorksWithoutDownloadState() {
        val vm = viewModel(storedPosition = 100)
        vm.reportPlaytime(game, 20)
        val conn = connections.last()
        awaitReported(conn, 1)
        assertEquals(listOf(120), conn.reported.toList())
        vm.close()
    }

    @Test
    fun reportedTotalIsCappedJustUnderThirtyDays() {
        val vm = viewModel(storedPosition = Int.MAX_VALUE)
        vm.reportPlaytime(game, 60)
        val conn = connections.last()
        awaitReported(conn, 1)
        assertEquals(listOf(30 * 24 * 60 * 60 - 1), conn.reported.toList())
        vm.close()
    }
}
