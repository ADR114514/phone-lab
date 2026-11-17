package com.mobilelab.signalmap.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SOP requires hashing BSSIDs before a survey leaves the lab for a public
 * repository. We keep a stable, short fingerprint (SHA-256, first 16 hex chars)
 * so that repeated measurements of the same AP still group together in the
 * Python analysis while the real MAC cannot be recovered.
 */
object BssidHash {

    const val PREFIX = "h"
    const val HASH_CHARS = 16
    const val ALGORITHM_NAME = "sha256-hex-16"

    fun hash(raw: String): String {
        val normalised = raw.trim().lowercase()
        if (normalised.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalised.toByteArray(StandardCharsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xff
                append(HEX[v ushr 4])
                append(HEX[v and 0x0f])
            }
        }
        return PREFIX + hex.take(HASH_CHARS)
    }

    /** True when [value] already is a hashed BSSID (guards double hashing). */
    fun isHashed(value: String): Boolean =
        value.length == HASH_CHARS + 1 && value.startsWith(PREFIX) &&
            value.drop(1).all { it in '0'..'9' || it in 'a'..'f' }

    private const val HEX = "0123456789abcdef"
}
