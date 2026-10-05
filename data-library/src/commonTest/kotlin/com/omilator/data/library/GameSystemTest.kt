package com.omilator.data.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameSystemTest {

    @Test
    fun coreResolutionFollowsScannerDetection() {
        // The Android launcher must resolve cores through the same
        // GameSystem detection the scanner uses (preferredCore), not an
        // extension table that disagrees with it.
        assertEquals("genesis_plus_gx_libretro", coreFor("md"))
        assertEquals("flycast_libretro", coreFor("gdi"))
        // chd is shared by PS1/Saturn/DC — the documented winner is PS1.
        assertEquals("beetle_psx_hw_libretro", coreFor("chd"))
        assertEquals("beetle_psx_hw_libretro", coreFor("cue"))
        assertEquals("melonds_libretro", coreFor("nds"))
        assertEquals("mgba_libretro", coreFor("gba"))
    }

    @Test
    fun ambiguousExtensionsHaveDocumentedWinners() {
        assertEquals(GameSystem.PLAYSTATION, GameSystem.detectByExtension("iso"))
        assertEquals(GameSystem.PLAYSTATION, GameSystem.detectByExtension("bin"))
        assertEquals(GameSystem.GAMECUBE, GameSystem.detectByExtension("ciso"))
    }

    @Test
    fun unknownExtensionHasNoSystem() {
        assertNull(GameSystem.detectByExtension("xyz"))
        assertNull(GameSystem.detectByExtension(""))
    }

    private fun coreFor(ext: String): String? =
        GameSystem.detectByExtension(ext)?.let { "${it.preferredCore}_libretro" }
}

class LibraryRepositoryTest {

    private fun game(id: String, system: GameSystem = GameSystem.NES) =
        Game(id = id, title = id, system = system, filePath = id, fileSizeBytes = 0)

    private class FakeScanner(vararg val results: Pair<String, List<Game>>) : LibraryScanner {
        override suspend fun scan(directory: String): List<Game> =
            results.firstOrNull { it.first == directory }?.second ?: emptyList()
    }

    @Test
    fun multiDirectoryScanCachesAllDirectories() = runBlocking {
        val repo = LibraryRepository(
            FakeScanner("/a" to listOf(game("a1"), game("a2")), "/b" to listOf(game("b1"))),
        )
        val scanned = repo.rescan(listOf("/a", "/b"))
        assertEquals(listOf("a1", "a2", "b1"), scanned.map { it.id })
        // Repository-level queries must see every directory, not just the
        // last one the old per-directory rescan left behind.
        assertEquals(3, repo.games().size)
        assertEquals(3, repo.gamesBySystem(GameSystem.NES).size)
        assertEquals(1, repo.search("b1").size)
    }

    @Test
    fun nestedDirectoriesDeduplicateByStableId() = runBlocking {
        val shared = listOf(game("same-path"))
        val repo = LibraryRepository(
            FakeScanner("/parent" to shared, "/parent/nested" to shared),
        )
        val scanned = repo.rescan(listOf("/parent", "/parent/nested"))
        assertEquals(1, scanned.size)
        assertEquals(1, repo.games().size)
    }
}
