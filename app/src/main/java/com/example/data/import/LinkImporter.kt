package com.example.data.import

import com.example.core.Base64Compat
import com.example.core.ClientPolicy
import com.example.core.ImportInput
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Recognizes only formats the stable Xray client actually imports. */
object LinkImporter {
    val supportedSchemes = listOf("http", "https", "vless", "vmess", "trojan", "ss", "socks", "socks5")
    val compilableProtocols = setOf(
        ProxyProtocol.VLESS, ProxyProtocol.VMESS, ProxyProtocol.TROJAN,
        ProxyProtocol.SHADOWSOCKS, ProxyProtocol.SOCKS
    )

    sealed interface Result {
        data class Nodes(
            val nodes: List<ServerProfileEntity>,
            val failures: List<String> = emptyList(),
            val notes: List<String> = emptyList()
        ) : Result
        data class Subscription(val url: String, val name: String = "") : Result
        data class Unsupported(val raw: String, val reason: String) : Result
    }

    fun isCompilable(protocol: ProxyProtocol): Boolean = protocol in compilableProtocols

    fun handle(rawInput: String?, groupName: String = "Imported"): Result {
        if (rawInput != null && !ImportInput.isWithinLimit(rawInput)) {
            return Result.Unsupported("", ImportInput.TOO_LARGE)
        }
        val raw = rawInput?.trim()?.removePrefix("\uFEFF").orEmpty()
        if (raw.isEmpty()) return Result.Unsupported("", "Nothing to import")
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            val url = raw.toHttpUrlOrNull()
                ?: return Result.Unsupported("", "Invalid HTTP(S) subscription URL")
            return Result.Subscription(url.toString())
        }
        val report = ProxyUriParser.parseBatchWithReport(raw, groupName = groupName)
        if (report.nodes.isEmpty()) {
            return Result.Unsupported("", report.failures.firstOrNull()
                ?: "No supported stable Xray servers found; Clash and sing-box are not supported")
        }
        return Result.Nodes(
            report.nodes.filter(ClientPolicy::isSupported),
            report.failures,
            report.strippedDirectives.map { "Ignored imported directive: $it" }
        )
    }

    fun handleMany(text: String?, groupName: String = "Imported"): List<Result> {
        if (text != null && !ImportInput.isWithinLimit(text)) {
            return listOf(Result.Unsupported("", ImportInput.TOO_LARGE))
        }
        val raw = text?.trim()?.removePrefix("\uFEFF").orEmpty()
        if (raw.isEmpty()) return emptyList()
        val expanded = if (!raw.contains("://") && !RawXrayJsonSanitizer.looksLikeJsonConfig(raw)) {
            runCatching { String(Base64Compat.decode(raw), Charsets.UTF_8).trim() }.getOrDefault(raw)
        } else raw
        if (RawXrayJsonSanitizer.looksLikeJsonConfig(expanded) || expanded.startsWith("{") || expanded.startsWith("[")) {
            return listOf(handle(expanded, groupName))
        }
        return expanded.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .map { handle(it, groupName) }.toList()
    }
}

/** Result reported only after imports have finished persisting. */
data class ImportSummary(
    val profiles: Int = 0,
    val subscriptions: Int = 0,
    val failures: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)
