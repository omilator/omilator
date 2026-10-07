package com.omilator.ui.player

/**
 * Result of the mobile battery-save restore step: whether the core's
 * SRAM may be flushed back to the store at teardown, and the user-facing
 * notice for anything unusual.
 */
internal data class MobileSramRestore(
    val authoritative: Boolean,
    val notice: String?,
)

/**
 * Mobile half of the SRAM restore gate, extracted from
 * MobilePlayerScreen's effect so its fail-safe contract is pinnable
 * without a Compose UI (mirrors the desktop engine's restoreSram seam).
 *
 * Contract: [SramStore.read] throwing — a save that exists but cannot be
 * read (damaged file, permission hiccup, iCloud-evicted placeholder) —
 * propagates to the caller's outer catch, which surfaces the error and
 * leaves the flush gate closed. It must NEVER be mapped to "no save":
 * fresh core RAM would then become authoritative and the teardown flush
 * would overwrite the only durable copy. Android gets this for free
 * (File.readBytes throws past the gate); IosSramStore now fails safe the
 * same way (pass C finding 5).
 */
internal fun restoreMobileSram(
    sramStore: SramStore?,
    readSaveRam: () -> ByteArray,
    writeSaveRam: (ByteArray) -> Unit,
): MobileSramRestore {
    // Absent (null) or empty save: fresh core RAM is authoritative — new
    // progress must be flushable. Throws propagate (see KDoc).
    val saved = sramStore?.read()
    val decision = runCatching {
        evaluateSramRestore(
            saved = saved,
            readSaveRam = readSaveRam,
            writeSaveRam = writeSaveRam,
        )
    }.getOrNull()
    return when {
        decision == null ->
            MobileSramRestore(
                authoritative = false,
                notice = "Battery save could not be read; progress will not be saved this session.",
            )
        decision.migrate ->
            if (sramStore != null && sramStore.backupExisting()) {
                // Size mismatch: old bytes preserved under a backup name,
                // core RAM adopted as the start of a new save.
                MobileSramRestore(authoritative = true, notice = decision.notice)
            } else {
                // Could not back the old save up (or no store): never
                // flush fresh core RAM over it.
                MobileSramRestore(
                    authoritative = false,
                    notice = "Battery save could not be backed up; progress will not be saved this session.",
                )
            }
        else -> MobileSramRestore(authoritative = decision.authoritative, notice = decision.notice)
    }
}
