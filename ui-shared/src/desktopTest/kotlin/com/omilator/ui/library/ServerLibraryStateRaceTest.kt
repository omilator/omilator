package com.omilator.ui.library

import com.omilator.data.library.GameSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lost-update regression for the server page state: user-driven state
 * writes (search, system filter, refresh transitions) used plain
 * read-copy-write assignments while download completions updated the same
 * StateFlow from IO coroutines — whichever wrote second erased the other's
 * field (a completed download disappeared, or a typed filter reset).
 * All transitions now go through atomic compare-and-swap updates.
 */
class ServerLibraryStateRaceTest {

    private class GatedConnection : ServerLibraryViewModel.ServerConnection {
        val downloadStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun finishDownload() = release.countDown()

        override suspend fun listGames(): List<ServerGame> = emptyList()

        override suspend fun downloadRom(game: ServerGame, onProgress: (Float) -> Unit): File? {
            downloadStarted.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "download never released" }
            onProgress(1f)
            return File("build/test-rom-${game.fileId}.rom").apply { writeText("rom") }
        }

        override suspend fun playtime(editionId: Long, seconds: Int) {}
        override suspend fun playtimePosition(editionId: Long): Int? = null
    }

    private val game = ServerGame(
        workId = 1,
        editionId = 10,
        fileId = 77,
        title = "Race Test",
        platformTag = "snes",
        platformName = "Super Nintendo",
        fileSizeBytes = 4,
    )

    private val vms = mutableListOf<ServerLibraryViewModel>()
    private val conns = mutableListOf<GatedConnection>()

    @AfterTest
    fun tearDown() {
        vms.forEach(ServerLibraryViewModel::close)
        File("build/test-rom-77.rom").delete()
    }

    private fun newVm(): Pair<ServerLibraryViewModel, GatedConnection> {
        val conn = GatedConnection()
        val vm = ServerLibraryViewModel(connect = { conn })
        vms.add(vm)
        conns.add(conn)
        return vm to conn
    }

    @Test
    fun downloadCompletionDoesNotEraseUserSearch() {
        val (vm, conn) = newVm()
        vm.download(game)
        assertTrue(conn.downloadStarted.await(10, TimeUnit.SECONDS), "download never started")

        // User types while the download is in flight.
        vm.setSearch("race")

        conn.finishDownload()
        awaitState(10_000) { s ->
            s.downloads[game.fileId]?.localFile != null
        }
        // Both transitions must survive: the completion did not reset the
        // query, and the query did not erase the completed download.
        assertEquals("race", vm.state.value.searchQuery, "search query was erased by download completion")
        assertNotNull(vm.state.value.downloads[game.fileId]?.localFile, "download completion was erased")
    }

    @Test
    fun userFilterDoesNotEraseDownloadCompletion() {
        val (vm, conn) = newVm()
        vm.download(game)
        assertTrue(conn.downloadStarted.await(10, TimeUnit.SECONDS), "download never started")

        conn.finishDownload()
        awaitState(10_000) { s -> s.downloads[game.fileId]?.localFile != null }

        // Filter chosen after completion: must not drop the download.
        vm.selectSystem(GameSystem.SNES)
        assertNotNull(vm.state.value.downloads[game.fileId]?.localFile)
        assertEquals(GameSystem.SNES, vm.state.value.selectedSystem)
    }

    private fun awaitState(timeoutMs: Long, predicate: (ServerLibraryViewModel.State) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(vmState())) {
            check(System.currentTimeMillis() < deadline) { "timed out; state: ${vmState()}" }
            Thread.sleep(20)
        }
    }

    private fun vmState(): ServerLibraryViewModel.State = vms.last().state.value
}
