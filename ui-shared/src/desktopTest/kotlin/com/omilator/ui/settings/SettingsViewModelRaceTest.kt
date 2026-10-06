package com.omilator.ui.settings

import com.omilator.data.settings.AppTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lost-update regression for settings state: core downloads and first-run
 * setup stream status writes from IO workers while the UI thread edits
 * settings. The setters used plain read-copy-write assignments
 * (`_state.value = _state.value.copy(...)`), so whichever thread wrote
 * last erased the other's field — a theme toggle reverted mid-download
 * status text, or a status write dropped a just-typed server URL. All
 * transitions now go through atomic compare-and-swap updates (the same
 * conversion pass 9 made in ServerLibraryViewModel).
 *
 * The interleaving is statistical: each iteration is read-then-write, so a
 * losing overlap shows up as the joining thread's final value being
 * clobbered. Thousands of iterations make the buggy form fail reliably.
 */
class SettingsViewModelRaceTest {

    @Test
    fun ioStatusStreamDoesNotEraseUiEdits() {
        val vm = SettingsViewModel()
        val stop = AtomicBoolean(false)
        val ioWrites = AtomicInteger(0)

        val ioThread = thread(name = "settings-status-writer") {
            while (!stop.get()) {
                vm.setCoresDownloading(true, "downloading ${ioWrites.incrementAndGet()}")
            }
        }
        try {
            repeat(5_000) { i ->
                vm.setTheme(if (i % 2 == 0) AppTheme.DARK else AppTheme.LIGHT)
                vm.setLibtecaServer("https://server-$i.example", "token-$i")
            }
        } finally {
            stop.set(true)
        }
        ioThread.join(10_000)
        check(!ioThread.isAlive) { "status writer never stopped" }

        // After both writers are done, the last value of EACH field must be
        // present. A read-copy-write writer clobbers the other thread's last
        // field whenever its read preceded that write, so at least one of
        // these assertions fails within a few hundred iterations on the old
        // form; the CAS form can never lose either.
        val s = vm.state.value
        assertEquals("https://server-4999.example", s.libtecaServerUrl, "server URL erased by status stream")
        assertEquals("token-4999", s.libtecaServerToken, "token erased by status stream")
        assertEquals(AppTheme.LIGHT, s.theme, "theme erased by status stream")
        assertEquals("downloading ${ioWrites.get()}", s.coresStatus, "status write erased by UI edits")
    }

    @Test
    fun uiEditsDoNotEraseIoStatusStream() {
        val vm = SettingsViewModel()
        val stop = AtomicBoolean(false)
        val uiWrites = AtomicInteger(0)

        // UI thread edits stream while one final status write lands after
        // they finish — the mirror overlap direction.
        val uiThread = thread(name = "settings-ui-writer") {
            while (!stop.get()) {
                val i = uiWrites.incrementAndGet()
                vm.setTheme(if (i % 2 == 0) AppTheme.DARK else AppTheme.LIGHT)
            }
        }
        try {
            repeat(5_000) { vm.setCoresDownloading(true, "status $it") }
        } finally {
            stop.set(true)
        }
        uiThread.join(10_000)
        check(!uiThread.isAlive) { "UI writer never stopped" }

        val s = vm.state.value
        assertEquals("status 4999", s.coresStatus, "status erased by theme edits")
        val expectedTheme = if (uiWrites.get() % 2 == 0) AppTheme.DARK else AppTheme.LIGHT
        assertEquals(expectedTheme, s.theme, "theme erased by status writes")
    }
}
