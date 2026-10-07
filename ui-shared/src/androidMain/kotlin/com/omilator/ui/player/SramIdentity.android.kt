package com.omilator.ui.player

import java.security.MessageDigest

/** JCA SHA-256 — the same primitive the desktop engine's SRAM identity
 *  uses, so a save written by either stays addressable by the other. */
actual fun sramIdentityHash(identity: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(identity.encodeToByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
