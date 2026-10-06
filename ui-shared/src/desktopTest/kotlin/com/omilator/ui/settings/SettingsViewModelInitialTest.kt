package com.omilator.ui.settings

import com.omilator.data.settings.AppSettings
import com.omilator.data.settings.AppTheme
import com.omilator.data.settings.SettingsStore
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constructor hydration regression: loading persisted settings must not be
 * expressed as user edits. The old default-valued ViewModel hydrated via
 * the persisting setters, whose read-modify-write copied libteca
 * URL/token from still-empty UI state — cold startup erased the persisted
 * server credentials without any user interaction.
 */
class SettingsViewModelInitialTest {

    private val writes = ConcurrentLinkedQueue<String>()
    private lateinit var store: SettingsStore

    private fun newStore(persisted: AppSettings? = null): SettingsStore {
        var saved = persisted?.let {
            """{"theme":"${it.theme.name}","libraryDirectories":${it.libraryDirectories.map { d -> "\"$d\"" }},""" +
                """"theGamesDbApiKey":"${it.theGamesDbApiKey}","libtecaServerUrl":"${it.libtecaServerUrl}",""" +
                """"libtecaServerToken":"${it.libtecaServerToken}"}"""
        }
        return SettingsStore(
            readText = { saved },
            writeText = { _, content ->
                writes.add(content)
                saved = content
            },
        )
    }

    private val snapshot = AppSettings(
        theme = AppTheme.DARK,
        libraryDirectories = listOf("/roms", "/more-roms"),
        theGamesDbApiKey = "tgdb-key",
        libtecaServerUrl = "https://libteca.example",
        libtecaServerToken = "secret-token",
    )

    @Test
    fun constructionSeedsEveryOwnedFieldWithoutPersisting() {
        store = newStore(persisted = snapshot)
        val vm = SettingsViewModel(store, "/test/settings.json", initial = snapshot)
        val s = vm.state.value
        assertEquals(AppTheme.DARK, s.theme)
        assertEquals(listOf("/roms", "/more-roms"), s.libraryDirectories)
        assertEquals("tgdb-key", s.theGamesDbApiKey)
        assertEquals("https://libteca.example", s.libtecaServerUrl)
        assertEquals("secret-token", s.libtecaServerToken)
        assertTrue(writes.isEmpty(), "construction must not persist; saw: ${writes.toList()}")
    }

    @Test
    fun laterEditPersistsCredentialsLoadedAtConstruction() {
        store = newStore(persisted = snapshot)
        val vm = SettingsViewModel(store, "/test/settings.json", initial = snapshot)

        awaitWrites(1) { vm.setTheme(AppTheme.LIGHT) }

        val written = writes.first()
        assertTrue("https://libteca.example" in written, "URL erased by edit: $written")
        assertTrue("secret-token" in written, "token erased by edit: $written")
        assertTrue("tgdb-key" in written, "API key erased by edit: $written")
        assertTrue("LIGHT" in written, "new theme not persisted: $written")
    }

    @Test
    fun defaultConstructionKeepsLegacyBehaviour() {
        store = newStore(persisted = null)
        val vm = SettingsViewModel(store, "/test/settings.json")
        assertEquals(AppTheme.SYSTEM, vm.state.value.theme)
        assertEquals("", vm.state.value.libtecaServerUrl)
        assertEquals("", vm.state.value.libtecaServerToken)
    }

    private fun awaitWrites(count: Int, action: () -> Unit) {
        action()
        val deadline = System.currentTimeMillis() + 10_000
        while (writes.size < count) {
            check(System.currentTimeMillis() < deadline) { "timed out; writes: ${writes.toList()}" }
            Thread.sleep(20)
        }
    }
}
