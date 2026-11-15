package com.example.data.import

import com.example.core.ProxyUriParser
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import java.net.URLDecoder

/**
 * Deep-link layer in front of [ProxyUriParser], covering the reference's 37 scheme aliases.
 *
 * Profile parsing stays in one place (`ProxyUriParser`); this object only decides *what a URI is*:
 * a profile, a subscription URL wrapped by another client, a config document, or garbage.
 */
object LinkImporter {

    /** Exactly the 37 alias schemes the reference registers as deep links. */
    val supportedSchemes: List<String> = listOf(
        "vless", "vmess", "trojan", "ss", "hy2", "hysteria2", "tuic", "wireguard", "wg",
        "hysteria", "anytls", "socks", "socks5", "socks4", "ssh", "openconnect", "anyconnect",
        "dnstt", "mdv", "mieru", "awg", "amneziawg", "sing-box", "v2fall", "v2rayng", "hiddify",
        "happ", "streisand", "clash", "clashmeta", "v2box", "karing", "sn", "v2raytun", "foxray",
        "content", "file"
    )

    private val protocolSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hy2", "hysteria2", "tuic", "wireguard", "wg",
        "hysteria", "anytls", "socks", "socks5", "socks4", "ssh", "openconnect", "anyconnect",
        "dnstt", "mdv", "mieru", "awg", "amneziawg"
    )

    private val subscriptionWrappers = setOf(
        "v2fall", "v2rayng", "hiddify", "happ", "streisand", "clash", "clashmeta", "v2box",
        "karing", "sn", "v2raytun", "foxray", "sing-box"
    )

    /** Protocols the bundled core can actually compile; the rest import but cannot connect. */
    val compilableProtocols: Set<ProxyProtocol> = setOf(
        ProxyProtocol.VLESS,
        ProxyProtocol.VMESS,
        ProxyProtocol.TROJAN,
        ProxyProtocol.SHADOWSOCKS,
        ProxyProtocol.SOCKS,
        ProxyProtocol.HYSTERIA2,
        ProxyProtocol.WIREGUARD,
        ProxyProtocol.MASQUE,
        ProxyProtocol.DNSTT,
        ProxyProtocol.MASTERDNS,
        ProxyProtocol.HTTP
    )

    enum class DocumentKind { SING_BOX_JSON, CLASH_YAML, PLAIN_LIST, URI }

    sealed interface Result {
        data class Nodes(val nodes: List<ServerProfileEntity>) : Result
        data class Subscription(val url: String, val name: String) : Result
        data class Document(val kind: DocumentKind, val content: String) : Result
        data class Unsupported(val raw: String, val reason: String) : Result
    }

    fun isCompilable(protocol: ProxyProtocol): Boolean = protocol in compilableProtocols

    fun handle(rawInput: String?, groupName: String = "Imported"): Result {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isEmpty()) return Result.Unsupported("", "empty input")

        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return Result.Subscription(url = raw, name = "")
        }

        val scheme = raw.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) {
            val decoded = decodeBase64(raw)
            return if (decoded != null && decoded.contains("://")) {
                Result.Document(DocumentKind.PLAIN_LIST, decoded)
            } else {
                Result.Unsupported(raw.take(80), "not a share link")
            }
        }

        return when {
            scheme == "content" || scheme == "file" -> Result.Document(DocumentKind.URI, raw)
            scheme in subscriptionWrappers -> handleWrapper(raw, scheme)
            scheme in protocolSchemes -> {
                val normalized = when (scheme) {
                    "mdv" -> "masterdns://" + raw.substringAfter("://")
                    else -> raw
                }
                val node = ProxyUriParser.parseSingleProxyUri(normalized, groupName = groupName)
                if (node == null) {
                    Result.Unsupported(raw.take(80), "$scheme link is malformed")
                } else {
                    Result.Nodes(listOf(node))
                }
            }
            else -> Result.Unsupported(raw.take(80), "unsupported scheme $scheme")
        }
    }

    /** Splits a pasted blob (one link per line), expanding a base64 list when present. */
    fun handleMany(text: String?, groupName: String = "Imported"): List<Result> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val expanded = if (!raw.contains("://")) decodeBase64(raw) ?: raw else raw
        return expanded.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .map { handle(it, groupName) }
    }

    private fun handleWrapper(raw: String, scheme: String): Result {
        val query = raw.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null
            else part.substring(0, at).lowercase() to decode(part.substring(at + 1))
        }.toMap()
        val url = params["url"] ?: params["sub"] ?: params["subscription"] ?: params["config"]
        if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            val name = decode(raw.substringAfter('#', "")).ifBlank { scheme }
            return Result.Subscription(url = url, name = name)
        }
        val payload = raw.substringAfter("://", "").substringBefore('#')
        return when (scheme) {
            "sing-box" -> if (payload.trimStart().startsWith("{")) {
                Result.Document(DocumentKind.SING_BOX_JSON, payload)
            } else {
                Result.Unsupported(raw.take(80), "sing-box link carries no subscription URL or JSON config")
            }
            "clash", "clashmeta" -> if (payload.contains("proxies:")) {
                Result.Document(DocumentKind.CLASH_YAML, payload)
            } else {
                Result.Unsupported(raw.take(80), "clash link carries no subscription URL or YAML")
            }
            else -> Result.Unsupported(raw.take(80), "$scheme link carries no subscription URL")
        }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = value.replace("\n", "").replace("\r", "").trim()
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
            .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - it.length % 4) }
        return runCatching {
            String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
        }.getOrNull()
    }
}

/** Outcome of one import run, shown by the import sheet. */
data class ImportSummary(
    val profiles: Int = 0,
    val subscriptions: Int = 0,
    val documents: Int = 0,
    val failures: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {
    val total: Int get() = profiles + subscriptions + documents + failures.size
}
