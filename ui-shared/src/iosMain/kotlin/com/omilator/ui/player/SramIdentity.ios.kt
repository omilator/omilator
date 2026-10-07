@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.ui.player

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

/** CommonCrypto SHA-256 (K/N ships it as platform.CoreCrypto). Same
 *  8-byte-hex-prefix format as the JCA actuals on desktop/Android, so a
 *  save stays addressable across platforms with the same identity. */
actual fun sramIdentityHash(identity: String): String {
    val bytes = identity.encodeToByteArray()
    // Digest into a pinned UByteArray — plain Kotlin indexed reads after
    // the native call, no CArrayPointer element access.
    val digest = UByteArray(32)
    memScoped {
        bytes.usePinned { pinned ->
            digest.usePinned { out ->
                CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
            }
        }
    }
    return buildString {
        for (i in 0 until 8) {
            val v = digest[i].toInt()
            append(HEX_DIGITS[(v ushr 4) and 0xF])
            append(HEX_DIGITS[v and 0xF])
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
