package com.example.core

import com.example.data.local.ServerProfileEntity

/** Removes credentials and subscription material before diagnostic text is retained or displayed. */
object SensitiveDataRedactor {
    private const val REDACTED = "<redacted>"

    private val uuidPattern = Regex(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
    )
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val basicAuthPattern = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/]+=*")
    private val keyValueSecretPattern = Regex(
        """(?i)(["']?(?:uuid|password|passwd|pass|user|username|user[_-]?id|client[_-]?id|auth|token|access[_-]?token|refresh[_-]?token|subscription[_-]?(?:token|url)|public[_-]?key|short[_-]?id|secret|credential|authorization)["']?\s*[:=]\s*)(["']?)([^"'&,\s}\]]+)(["']?)"""
    )
    private val uriPattern = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)([^\s/?#]+)([/?#][^\s]*)?""")
    private val bareSubscriptionPathPattern = Regex(
        "(?i)(/(?:sub|subscription|subscribe|token)(?:/|=)[^\\s?#&]*)"
    )

    fun redact(
        text: String,
        activeProfile: ServerProfileEntity? = null,
        extraSecrets: Collection<String> = emptyList()
    ): String {
        if (text.isEmpty()) return text
        var result = text
            .replace(bearerPattern, "Bearer $REDACTED")
            .replace(basicAuthPattern, "Basic $REDACTED")
            .replace(uuidPattern, REDACTED)
            .replace(keyValueSecretPattern) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED${match.groupValues[4]}"
            }
            .replace(uriPattern) { match -> redactUri(match.groupValues[1], match.groupValues[2], match.groupValues[3]) }
            .replace(bareSubscriptionPathPattern, REDACTED)

        val profileSecrets = activeProfile?.let { profile ->
            listOf(
                profile.uuidOrPassword,
                profile.publicKey,
                profile.shortId,
                profile.address,
                profile.sni,
                profile.hostHeader,
                profile.path,
                profile.serviceName,
                profile.rawUri,
                profile.echConfigList,
                profile.mldsa65Verify,
                profile.finalMask
            )
        }.orEmpty()
        (profileSecrets + extraSecrets)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret -> result = result.replace(secret, REDACTED, ignoreCase = true) }

        return if (activeProfile != null) {
            EncryptedConfigVault.sanitizeLogText(result, activeProfile)
        } else {
            result
        }
    }

    private fun redactUri(scheme: String, authority: String, suffix: String): String {
        val normalizedScheme = scheme.removeSuffix("://")
        val hasCredentials = authority.contains('@')
        val host = authority.substringAfterLast('@')
        val isWebUrl = normalizedScheme.equals("http", ignoreCase = true) ||
            normalizedScheme.equals("https", ignoreCase = true)
        val safeAuthority = when {
            isWebUrl -> host
            hasCredentials -> host
            else -> REDACTED
        }
        val hasSensitiveSuffix = suffix.isNotEmpty()
        return buildString {
            append(scheme)
            if (hasCredentials && isWebUrl) append(REDACTED).append('@')
            append(safeAuthority)
            if (hasSensitiveSuffix) append("/$REDACTED")
        }
    }
}
