package com.omilator.ui.library

import com.omilator.data.library.LibraryRepository
import com.omilator.data.library.LibraryScanner
import com.omilator.data.settings.SettingsStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cold-start scan regression: platform-default roots (e.g. Android's
 * private Documents directory) must always be part of the effective scan
 * set — persisted SAF directories must survive every scan, and the default
 * root must never leak into persisted settings.
 */
class LibraryViewModelDefaultScanRootsTest {

    private val scannedDirs = ConcurrentLinkedQueue<String>()
    private val savedSettings = AtomicReference<String?>(null)

    private val defaultRoot = "/android/files/Documents"
    private val safDir = "content://saf/roms"

    private lateinit var viewModel: LibraryViewModel

    @BeforeTest
    fun setUp() {
        val scanner = object : LibraryScanner {
            override suspend fun scan(directory: String): List<com.omilator.data.library.Game> {
                scannedDirs.add(directory)
                return emptyList()
            }
        }
        val settingsStore = SettingsStore(
            readText = { """{"libraryDirectories":["$safDir"]}""" },
            writeText = { _, content -> savedSettings.set(content) },
        )
        viewModel = LibraryViewModel(
            repository = LibraryRepository(scanner),
            settingsStore = settingsStore,
            settingsPath = "/test/settings.json",
            defaultScanDirectories = listOf(defaultRoot),
        )
    }

    private fun awaitScan(dirCount: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (scannedDirs.size < dirCount) {
            check(System.currentTimeMillis() < deadline) {
                "timed out; scanned so far: ${scannedDirs.toList()}"
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun coldStartScanCombinesPersistedAndDefaultDirectories() {
        awaitScan(2)
        assertEquals(setOf(defaultRoot, safDir), scannedDirs.toSet())
    }

    @Test
    fun refreshKeepsDefaultDirectoryInEffectiveSet() {
        awaitScan(2)
        scannedDirs.clear()
        viewModel.rescan()
        awaitScan(2)
        assertEquals(setOf(defaultRoot, safDir), scannedDirs.toSet())
    }

    @Test
    fun removingConfiguredDirectoryStillScansDefaultAndDoesNotPersistIt() {
        awaitScan(2)
        scannedDirs.clear()
        viewModel.removeDirectory(safDir)
        awaitScan(1)
        assertEquals(listOf(defaultRoot), scannedDirs.toList())

        val saved = awaitNotNull(savedSettings)
        assertTrue(defaultRoot !in saved, "default root must not be persisted: $saved")
        assertTrue(safDir !in saved, "removed directory must not be persisted: $saved")
    }

    private fun awaitNotNull(ref: AtomicReference<String?>): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (ref.get() == null) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for settings persist" }
            Thread.sleep(20)
        }
        return ref.get()!!
    }
}
