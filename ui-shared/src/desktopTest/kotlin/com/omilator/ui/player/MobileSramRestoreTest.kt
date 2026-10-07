package com.omilator.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pass C finding 5: a battery save that exists but cannot be READ
 * (damaged file, permission hiccup, iCloud-evicted placeholder) must not
 * be treated as "no save" — fresh core RAM would become authoritative
 * and the teardown flush would overwrite the only durable copy.
 * IosSramStore.read() now throws for that case (like Android's
 * readBytes / desktop's engine read); this pins the seam's contract.
 */
class MobileSramRestoreTest {

    private class FakeStore(
        val saved: ByteArray?,
        val failRead: Boolean = false,
    ) : SramStore {
        var writes = 0
        var backups = 0
        override fun read(): ByteArray? {
            if (failRead) throw RuntimeException("iCloud-evicted placeholder")
            return saved
        }
        override fun write(data: ByteArray) { writes++ }
        override fun backupExisting(): Boolean {
            backups++
            return true
        }
    }

    @Test
    fun unreadableSavePropagatesAndNeverFlushesOverIt() {
        val store = FakeStore(saved = ByteArray(32), failRead = true)
        var coreWrites = 0
        val error = assertFailsWith<RuntimeException> {
            restoreMobileSram(store, readSaveRam = { fail("no core round-trip on a failed read") }, writeSaveRam = { coreWrites++ })
        }
        assertEquals("iCloud-evicted placeholder", error.message)
        assertEquals(0, store.writes, "the store must not be touched")
        assertEquals(0, coreWrites, "the core block must not be consulted")
    }

    @Test
    fun absentSaveMeansFreshRamIsAuthoritative() {
        val store = FakeStore(saved = null)
        val restore = restoreMobileSram(store, readSaveRam = { ByteArray(0) }, writeSaveRam = {})
        assertTrue(restore.authoritative, "new games must be able to create their first save")
        assertNull(restore.notice)
    }

    @Test
    fun restoredSaveIsAuthoritative() {
        val save = ByteArray(64) { it.toByte() }
        val store = FakeStore(saved = save)
        val restore = restoreMobileSram(
            store,
            readSaveRam = { save },
            writeSaveRam = {},
        )
        assertTrue(restore.authoritative)
        assertNull(restore.notice)
    }

    @Test
    fun sizeMismatchMigratesWithABackup() {
        val store = FakeStore(saved = ByteArray(32))
        val restore = restoreMobileSram(
            store,
            readSaveRam = { ByteArray(64) }, // core grew the block
            writeSaveRam = {},
        )
        assertTrue(restore.authoritative)
        assertEquals(1, store.backups, "the old bytes must be preserved under a backup name")
        assertTrue(restore.notice!!.contains("backup", ignoreCase = true))
    }

    @Test
    fun restoreThatDidNotTakeClosesTheGate() {
        val save = ByteArray(32) { 1 }
        val store = FakeStore(saved = save)
        val restore = restoreMobileSram(
            store,
            readSaveRam = { ByteArray(32) }, // same size, different bytes
            writeSaveRam = {},
        )
        assertEquals(false, restore.authoritative, "never flush fresh RAM over an unrestored save")
        assertTrue(restore.notice!!.contains("will not be saved", ignoreCase = true))
    }

    @Test
    fun coreWithoutAnSramBlockClosesTheGateQuietly() {
        val store = FakeStore(saved = ByteArray(32))
        val restore = restoreMobileSram(
            store,
            readSaveRam = { ByteArray(0) },
            writeSaveRam = {},
        )
        assertEquals(false, restore.authoritative)
        assertNull(restore.notice, "nothing user-actionable: there is nothing to flush")
    }

    @Test
    fun noStoreMeansFreshAuthoritative() {
        val restore = restoreMobileSram(null, readSaveRam = { ByteArray(0) }, writeSaveRam = {})
        assertTrue(restore.authoritative)
    }
}
