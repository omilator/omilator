package com.omilator.ui.player

import com.omilator.data.library.GameSystem

/**
 * Core file name (without platform suffix/extension) for a ROM path,
 * resolved through the same shared [GameSystem] table the scanner and the
 * desktop router use.
 *
 * Every mobile launch path (library play, quick-play, deep link) must go
 * through this single function: hand-rolled extension tables on iOS once
 * disagreed with each other AND with the shared table — a library `.iso`
 * launched mGBA, the deep-link poller sent it to PPSSPP, and unknown
 * extensions silently launched mGBA.
 *
 * Null means "no mapping": callers must surface that to the user instead
 * of falling back to a default core.
 */
fun coreNameForRom(path: String): String? {
    val ext = path.substringAfterLast('.', "")
    val system = GameSystem.detectByExtension(ext) ?: return null
    return "${system.preferredCore}_libretro"
}
