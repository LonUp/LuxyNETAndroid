package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import java.util.Locale

/**
 * Builds the short, non-secret identity caption shown next to a configuration's name.
 *
 * Two configurations that share a display name such as "United States" are still told apart by
 * protocol, port, and a short fingerprint prefix. The caption never carries the address, host,
 * SNI, path, UUID/password, or the full fingerprint, so it is safe on screen and in screenshots.
 */
object ConfigIdentityLabel {

    private const val FINGERPRINT_PREFIX_LENGTH = 4
    private const val SEPARATOR = " · "

    /** Returns `PROTOCOL · PORT · FFFF`; a blank protocol and a non-positive port are omitted. */
    fun shortLabel(profile: ServerProfileEntity): String {
        val protocolTag = if (profile.protocol.isBlank()) {
            null
        } else {
            ProxyProtocol.parse(profile.protocol).shortTag.uppercase(Locale.ROOT)
        }
        val parts = listOfNotNull(
            protocolTag,
            profile.port.takeIf { it > 0 }?.toString(),
            fingerprintPrefix(profile)
        )
        return parts.joinToString(SEPARATOR)
    }

    /** First [FINGERPRINT_PREFIX_LENGTH] hex characters of the canonical fingerprint, lowercase. */
    fun fingerprintPrefix(profile: ServerProfileEntity): String =
        ConfigFingerprint.compute(profile)
            .take(FINGERPRINT_PREFIX_LENGTH)
            .lowercase(Locale.ROOT)
}
