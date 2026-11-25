package com.example.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Rejects cleartext subscription URLs. Fetching over http:// leaks config contents
 *  (server addresses, UUIDs) to the network and inherits whatever a network attacker
 *  injects into the response. Loopback hosts never leave the device, so local panels
 *  keep working. */
object SubscriptionUrlPolicy {
    /** null when the URL is acceptable; otherwise the user-visible rejection message. */
    fun rejection(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "Subscription URL must use HTTP or HTTPS"
        return when {
            parsed.scheme == "https" -> null
            parsed.scheme == "http" && isLoopback(parsed.host) -> null
            parsed.scheme == "http" -> "Subscription URL must use HTTPS"
            else -> "Subscription URL must use HTTP or HTTPS"
        }
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host.startsWith("127.") || host == "::1"
}
