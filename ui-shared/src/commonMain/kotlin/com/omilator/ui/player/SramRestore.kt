package com.omilator.ui.player

/**
 * Pure decision for the battery-save (SRAM) restore gate, shared by the
 * desktop engine and the mobile player. Kept free of platform I/O so the
 * mismatch corner — the on-disk save's size differs from the core's SRAM
 * block size — is pinnable by tests.
 *
 * The gate exists because writeSaveRam silently no-ops when the sizes
 * disagree: verifying the restore via read-back is the only way to know
 * the core actually holds the persisted save. Before the migration path,
 * a size mismatch silently disabled flushing for the whole session, every
 * launch — core updates that grew the save block, or a save written by a
 * different core, meant progress stopped persisting with only a console
 * print as evidence.
 */
internal data class SramRestoreDecision(
    /** True only when the core's SRAM block may be flushed back to the
     *  store at teardown: it verifiably holds this game's save, or it is
     *  fresh RAM with no prior save to protect. */
    val authoritative: Boolean,
    /** The persisted save's size no longer matches the core's SRAM block:
     *  the caller must back the old file up (never delete it) and adopt
     *  the core's fresh block as the start of a new save. */
    val migrate: Boolean,
    /** User-facing explanation of anything unusual; null when the restore
     *  was clean. Surfaced by the UI instead of a console-only print. */
    val notice: String?,
)

internal fun evaluateSramRestore(
    saved: ByteArray?,
    readSaveRam: () -> ByteArray,
    writeSaveRam: (ByteArray) -> Unit,
): SramRestoreDecision {
    if (saved == null || saved.isEmpty()) {
        // No prior battery save: fresh core RAM is authoritative and new
        // progress must be flushable.
        return SramRestoreDecision(authoritative = true, migrate = false, notice = null)
    }
    writeSaveRam(saved)
    // writeSaveRam silently no-ops when the core's SRAM block size
    // disagrees with the file; verify the restore actually took before
    // teardown is allowed to flush over that file.
    val block = readSaveRam()
    return when {
        block.contentEquals(saved) ->
            SramRestoreDecision(authoritative = true, migrate = false, notice = null)

        block.isEmpty() ->
            // The core exposes no SRAM block at all (or startup failed before
            // it was mapped): there is nothing to flush and nothing to save.
            SramRestoreDecision(authoritative = false, migrate = false, notice = null)

        block.size != saved.size ->
            // Size disagreement (core update grew the save block, save from a
            // different core, truncated write). Keep the old bytes safe under
            // a backup name and let the core's fresh block start a new save —
            // the alternative (gate off forever) silently discarded every
            // future in-game save.
            SramRestoreDecision(
                authoritative = true,
                migrate = true,
                notice = "Battery save size changed (${saved.size} → ${block.size} bytes). " +
                    "The previous save was kept as a backup; a new one starts now.",
            )

        else ->
            // Same size but different bytes: the restore did not take for a
            // reason the frontend cannot fix. Never flush fresh RAM over the
            // existing save, and say so instead of failing silently.
            SramRestoreDecision(
                authoritative = false,
                migrate = false,
                notice = "Battery save could not be restored; progress will not " +
                    "be saved this session (the existing save is untouched).",
            )
    }
}
