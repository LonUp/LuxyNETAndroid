package com.example.core

import java.util.Locale

/**
 * Quota and expiry a subscription endpoint reports through the `subscription-userinfo` header,
 * the de-facto panel standard:
 *
 * ```
 * subscription-userinfo: upload=123456; download=987654; total=107374182400; expire=1767225600
 * ```
 *
 * Bytes are bytes, `expire` is unix seconds. Panels disagree on which keys they emit and some
 * emit `expire` as an ISO date; anything non-numeric is ignored rather than guessed.
 */
data class SubscriptionUserInfo(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireAtEpochSec: Long = 0L
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes

    val remainingBytes: Long
        get() = if (totalBytes <= 0L) 0L else (totalBytes - usedBytes).coerceAtLeast(0L)

    /** 0…1, 0 when the panel reports no quota. */
    val usageFraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val hasQuota: Boolean get() = totalBytes > 0L

    val hasExpiry: Boolean get() = expireAtEpochSec > 0L

    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean =
        hasExpiry && expireAtEpochSec <= nowEpochSec

    /** Whole days until expiry, 0 when unknown or already expired. */
    fun daysLeft(nowEpochSec: Long = System.currentTimeMillis() / 1000L): Int =
        if (!hasExpiry || isExpired(nowEpochSec)) 0 else ((expireAtEpochSec - nowEpochSec) / 86_400L).toInt()

    companion object {
        /** Returns null when the header is absent or carries no usable number. */
        fun parse(header: String?): SubscriptionUserInfo? {
            val raw = header?.trim().orEmpty()
            if (raw.isEmpty()) return null
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            raw.split(';').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val key = part.substring(0, eq).trim().lowercase(Locale.ROOT)
                val value = part.substring(eq + 1).trim().toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
            if (upload == 0L && download == 0L && total == 0L && expire == 0L) return null
            return SubscriptionUserInfo(upload, download, total, expire)
        }
    }
}
