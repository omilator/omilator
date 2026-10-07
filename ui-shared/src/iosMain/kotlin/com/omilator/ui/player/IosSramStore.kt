@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui.player

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ByteVar
import platform.Foundation.NSFileManager
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rename

/**
 * Battery-save persistence for iOS — the piece the legacy IosPlayerScreen
 * never had (its teardown detached and unloaded the core without any SRAM
 * round-trip, so every in-game battery save was discarded at exit).
 *
 * Mirrors Android's MobileSramStore: same identity scheme
 * ([sramSaveFileName] over a stable ROM identity), POSIX file I/O (the
 * pattern IosSettingsPersistence established — Foundation NSData factories
 * had inconsistent Kotlin/Native exposure), atomic tmp+rename replace so a
 * crash mid-write cannot corrupt the only durable save, and backups that
 * never overwrite an existing backup.
 */
class IosSramStore(
    savesDir: String,
    romPath: String,
    romIdentity: String,
) : SramStore {
    private val dir: String
    private val file: String

    init {
        dir = savesDir
        NSFileManager.defaultManager().createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        file = "$dir/${sramSaveFileName(romPath, sramIdentityHash(romIdentity))}"
    }

    override fun read(): ByteArray? = readFile(file)

    override fun write(data: ByteArray) {
        // Write to a sibling temp file, then rename over the target — a
        // direct "wb" truncates in place, and a crash mid-write leaves a
        // corrupt save that destroys the only durable battery copy.
        val tmpPath = "$file.tmp"
        memScoped {
            val fp = fopen(tmpPath, "wb") ?: throw RuntimeException("cannot open $tmpPath for writing")
            try {
                // The pinned ByteArray IS the write buffer — no intermediate
                // native allocation to fill element by element.
                val written = data.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, data.size.toULong(), fp)
                }
                check(written == data.size.toULong()) { "short SRAM write: $written/${data.size}" }
            } finally {
                fclose(fp)
            }
        }
        check(rename(tmpPath, file) == 0) { "SRAM replace failed for $file" }
    }

    /** See [SramStore.backupExisting]: called when the persisted save's
     *  size no longer matches the core's SRAM block. Never overwrites an
     *  existing backup. */
    override fun backupExisting(): Boolean {
        val fm = NSFileManager.defaultManager()
        if (!fm.fileExistsAtPath(file)) return true
        val name = nextSramBackupName(file.substringAfterLast('/')) { candidate ->
            fm.fileExistsAtPath("$dir/$candidate")
        }
        return runCatching { rename(file, "$dir/$name") == 0 }.getOrDefault(false)
    }

    private fun readFile(path: String): ByteArray? {
        val fm = NSFileManager.defaultManager()
        if (!fm.fileExistsAtPath(path)) return null
        return memScoped {
            val fp = fopen(path, "rb") ?: return@memScoped null
            try {
                fseek(fp, 0, 2) // SEEK_END
                val size = ftell(fp).toInt()
                fseek(fp, 0, 0) // SEEK_SET
                if (size <= 0) return@memScoped ByteArray(0)
                val buf = allocArray<ByteVar>(size)
                val read = fread(buf, 1u, size.toULong(), fp)
                if (read == 0UL) null else buf.readBytes(read.toInt())
            } finally {
                fclose(fp)
            }
        }
    }
}
