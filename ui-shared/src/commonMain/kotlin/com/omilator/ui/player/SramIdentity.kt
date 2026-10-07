package com.omilator.ui.player

/**
 * Battery-save file identity shared by the mobile SRAM stores (Android's
 * [SramStore] implementation and iOS's). Pure naming logic lives here so
 * the scheme — sanitized ROM basename + hash of a stable ROM identity —
 * cannot drift between platforms; only the digest primitive is
 * platform-provided.
 */

/** ROM basename with the extension stripped and filesystem-hostile
 *  characters replaced — the same character policy the desktop engine
 *  applies. Handles both path separators so tests behave identically on
 *  macOS/Linux and Windows JVMs. */
fun sanitizedRomBaseName(romPath: String): String =
    romPath.substringAfterLast('/').substringAfterLast('\\')
        .substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")

/** Save file name for a ROM: `<sanitized base>-<identity hash>.srm`. The
 *  hash disambiguates identically named ROMs in different directories or
 *  with different content identities. */
fun sramSaveFileName(romPath: String, identityHash: String): String =
    "${sanitizedRomBaseName(romPath)}-$identityHash.srm"

/** First 8 bytes of the identity's SHA-256 as 16 lowercase hex chars.
 *  Platform actuals provide the digest (JCA on desktop/Android,
 *  CommonCrypto on iOS). */
expect fun sramIdentityHash(identity: String): String

/** Backup name for a save that no longer matches the core's SRAM block:
 *  `<name>.srm.bak`, numbered `.bak2`, `.bak3`, … so an existing backup
 *  is never overwritten. [exists] is queried per candidate, keeping this
 *  pure and testable. */
fun nextSramBackupName(saveFileName: String, exists: (String) -> Boolean): String {
    var candidate = "$saveFileName.bak"
    var n = 2
    while (exists(candidate)) {
        candidate = "$saveFileName.bak$n"
        n++
    }
    return candidate
}
