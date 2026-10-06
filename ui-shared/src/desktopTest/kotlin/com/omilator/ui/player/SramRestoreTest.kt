package com.omilator.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the battery-save restore gate's decision table, including the
 * size-mismatch corner that used to silently disable saving for the whole
 * session (every launch) with only a console print as evidence.
 */
class SramRestoreTest {

    private fun gate(saved: ByteArray?, block: ByteArray): SramRestoreDecision {
        var sram = block.copyOf()
        return evaluateSramRestore(
            saved = saved,
            readSaveRam = { sram.copyOf() },
            writeSaveRam = { bytes -> if (bytes.size == sram.size) sram = bytes.copyOf() },
        )
    }

    @Test
    fun noPriorSaveIsAuthoritative() {
        val d = gate(saved = null, block = ByteArray(8192) { 0xAB.toByte() })
        assertTrue(d.authoritative)
        assertFalse(d.migrate)
        assertNull(d.notice)
    }

    @Test
    fun emptyPriorSaveIsAuthoritative() {
        val d = gate(saved = ByteArray(0), block = ByteArray(8192))
        assertTrue(d.authoritative)
        assertFalse(d.migrate)
    }

    @Test
    fun matchingRestoreIsAuthoritativeWithoutNotice() {
        val saved = ByteArray(4096) { it.toByte() }
        val d = gate(saved = saved, block = ByteArray(4096))
        assertTrue(d.authoritative)
        assertFalse(d.migrate)
        assertNull(d.notice)
    }

    @Test
    fun sizeMismatchMigratesInsteadOfDisablingSaving() {
        val saved = ByteArray(8192) { 0x11.toByte() }       // file: 8 KB
        val block = ByteArray(32768) { 0x22.toByte() }      // core block: 32 KB
        val d = gate(saved = saved, block = block)
        // The core could not adopt the old bytes, but the old save must be
        // preserved (backup) and future saves must keep working.
        assertTrue(d.authoritative, "mismatch must not disable saving forever")
        assertTrue(d.migrate)
        assertNotNull(d.notice)
        assertTrue("8192" in d.notice && "32768" in d.notice, "notice should name sizes: ${d.notice}")
    }

    @Test
    fun absentCoreBlockNeverBecomesAuthoritative() {
        // A core exposing no SRAM block (e.g. startup failed before the
        // block was mapped): flushing is impossible and must stay off.
        val d = gate(saved = ByteArray(2048), block = ByteArray(0))
        assertFalse(d.authoritative)
        assertFalse(d.migrate)
    }

    @Test
    fun sameSizeContentMismatchRefusesToFlushAndSurfacesNotice() {
        // writeSaveRam copies in, then the core "changes" the bytes back:
        // simulate via a block that ignores the write.
        var sram = ByteArray(16) { 0x00.toByte() }
        val d = evaluateSramRestore(
            saved = ByteArray(16) { 0x55.toByte() },
            readSaveRam = { sram.copyOf() },
            writeSaveRam = { /* pretend the core rejected it */ },
        )
        assertFalse(d.authoritative, "unverifiable restore must never flush over the file")
        assertFalse(d.migrate)
        assertNotNull(d.notice)
    }

    @Test
    fun oversizeSaveMigratesRatherThanClobber() {
        // Truncated/oversized legacy file vs a smaller core block: same
        // migration decision (back up old bytes, start fresh).
        val d = gate(saved = ByteArray(65536), block = ByteArray(8192))
        assertTrue(d.authoritative)
        assertTrue(d.migrate)
    }
}
