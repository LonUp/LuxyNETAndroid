package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.SourceType
import com.example.data.model.TransportType
import java.util.Locale
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class BatchParseReport(
    val nodes: List<ServerProfileEntity>,
    val totalParsed: Int,
    val trimmedCount: Int,
    val strippedDirectives: List<String>,
    val failures: List<String> = emptyList()
)

object ProxyUriParser {

    private data class UrlParts(
        val host: String,
        val port: Int?,
        val userInfo: String,
        val rawQuery: String,
        val rawFragment: String
    )

    private fun decodeBase64Safe(input: String): String? {
        val cleaned = input.trim().replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty()) return null
        return try {
            val bytes = Base64Compat.decode(cleaned)
            val str = String(bytes, Charsets.UTF_8)
            if (str.any { it.code < 9 }) null else str
        } catch (_: Exception) {
            null
        }
    }

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }

    private fun formatHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun splitHostPort(input: String): Pair<String, Int>? {
        if (input.startsWith("[")) {
            val closeIdx = input.indexOf(']')
            if (closeIdx <= 1) return null
            val host = input.substring(1, closeIdx)
            val portStr = input.substring(closeIdx + 1).removePrefix(":")
            val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }
        val colonIdx = input.lastIndexOf(':')
        if (colonIdx <= 0) return null
        val host = input.substring(0, colonIdx)
        val port = input.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun parseQueryParams(rawQuery: String?, caseInsensitive: Boolean = false): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = urlDecode(pair.substring(0, idx))
            val v = urlDecode(pair.substring(idx + 1))
            out[if (caseInsensitive) k.lowercase() else k] = v
        }
        return out
    }

    private fun parseUrlParts(raw: String): UrlParts? {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return null
        val withoutScheme = raw.substring(schemeEnd + 3)
        val hashIdx = withoutScheme.indexOf('#')
        val rawFragment = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else ""
        val beforeHash = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val qIdx = beforeHash.indexOf('?')
        val rawQuery = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else ""
        val authorityAndPath = if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash
        val slashIdx = authorityAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath

        val atIdx = authority.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isEmpty()) return null

        var host = ""
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx <= 1) return null
            host = hostPort.substring(1, closeIdx)
            val rest = hostPort.substring(closeIdx + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx)
                port = hostPort.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = hostPort
            }
        }
        if (host.isEmpty() || host.any { it.isWhitespace() } || host.contains('@')) return null
        return UrlParts(host, port, userInfo, rawQuery, rawFragment)
    }


    private fun parseVmess(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val payload = raw.substring(raw.indexOf("://") + 3).substringBefore('#').trim()
        val jsonStr = decodeBase64Safe(payload) ?: return null
        val json = JSONObject(jsonStr)
        val address = json.optString("add", "").trim()
        val port = json.optString("port", "").toIntOrNull() ?: return null
        val uuid = json.optString("id", "").trim()
        if (address.isEmpty() || port !in 1..65535 || uuid.isEmpty()) return null

        val remark = json.optString("ps", "").trim().ifEmpty { "$address:$port" }
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")
        val security = if (tls.equals("tls", ignoreCase = true)) SecurityType.TLS else SecurityType.NONE
        if (net.lowercase() !in supportedTransports || tls.lowercase() !in setOf("", "none", "tls")) return null
        val headerType = json.optString("type", "none")
        if (json.optInt("aid", 0) != 0 || (headerType !in setOf("", "none") &&
                !(TransportType.parse(net) == TransportType.GRPC && headerType == "gun"))) return null

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.VMESS.name,
            address = address,
            port = port,
            uuidOrPassword = uuid,
            encryption = json.optString("scy", "auto").trim().ifEmpty { "auto" },
            transport = TransportType.parse(net).name,
            security = security.name,
            sni = json.optString("sni", ""),
            alpn = json.optString("alpn", ""),
            fingerprint = json.optString("fp", "chrome"),
            path = json.optString("path", ""),
            serviceName = if (TransportType.parse(net) == TransportType.GRPC) json.optString("path", "") else "",
            hostHeader = json.optString("host", ""),
            cipherSuites = json.optString("cs", json.optString("cipherSuites", "")),
            finalMask = json.optString("fm", json.optString("finalMask", "")),
            echConfigList = json.optString("ech", json.optString("echConfigList", "")),
            echForceQuery = json.optString("echForceQuery", "none"),
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw,
            sourceType = if (subscriptionId != null) SourceType.SUBSCRIPTION.name else SourceType.MANUAL.name
        )
    }

    private fun parseVlessOrTrojan(
        raw: String,
        protocol: ProxyProtocol,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val parts = parseUrlParts(raw) ?: return null
        if (parts.userInfo.isEmpty()) return null
        val params = parseQueryParams(parts.rawQuery)
        val transportType = TransportType.parse(params["type"])
        if (params["type"]?.lowercase()?.let { it !in supportedTransports } == true) return null
        if (params["security"]?.lowercase()?.let { it !in setOf("none", "tls", "reality") } == true) return null
        if (params["headerType"]?.let { it != "none" } == true) return null
        val xhttpMode = params["mode"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (xhttpMode.isNotEmpty() &&
            (transportType != TransportType.XHTTP || xhttpMode !in setOf("auto", "packet-up", "stream-up", "stream-one"))) {
            return null
        }
        val packetEncoding = params["packetEncoding"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (packetEncoding.isNotEmpty() && (protocol != ProxyProtocol.VLESS || packetEncoding != "xudp")) return null
        val supportedParams = setOf("encryption", "type", "security", "sni", "alpn", "fp", "pbk", "sid",
            "path", "host", "serviceName", "flow", "allowInsecure", "insecure", "cs", "cipherSuites",
            "fm", "finalMask", "finalmask", "ech", "echConfigList", "echForceQuery", "pqv", "mldsa65Verify",
            "headerType", "mode", "authority", "packetEncoding")
        if (params.keys.any { it !in supportedParams }) return null
        val port = parts.port ?: 443
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty { "${parts.host}:$port" }
        val defaultSec = if (protocol == ProxyProtocol.TROJAN) SecurityType.TLS else SecurityType.NONE
        val secType = params["security"]?.let { SecurityType.parse(it) } ?: defaultSec
        val ech = params["ech"] ?: params["echConfigList"].orEmpty()
        val featureOverrides = NodeFeatureOverrides(
            packetEncoding = packetEncoding,
            xhttpMode = if (xhttpMode == "auto") "" else xhttpMode
        ).encode()
        return ServerProfileEntity(
            name = remark,
            protocol = protocol.name,
            address = parts.host,
            port = port,
            uuidOrPassword = urlDecode(parts.userInfo),
            encryption = params["encryption"] ?: "none",
            transport = transportType.name,
            security = secType.name,
            sni = params["sni"].orEmpty(),
            alpn = params["alpn"].orEmpty(),
            fingerprint = params["fp"] ?: "chrome",
            publicKey = params["pbk"].orEmpty(),
            shortId = params["sid"].orEmpty(),
            path = params["path"].orEmpty(),
            hostHeader = if (transportType == TransportType.GRPC) {
                params["authority"] ?: params["host"].orEmpty()
            } else params["host"].orEmpty(),
            serviceName = params["serviceName"].orEmpty(),
            flow = params["flow"].orEmpty(),
            allowInsecure = params["allowInsecure"] == "1" ||
                params["allowInsecure"]?.equals("true", ignoreCase = true) == true ||
                params["insecure"] == "1",
            cipherSuites = params["cs"] ?: params["cipherSuites"].orEmpty(),
            finalMask = params["fm"] ?: params["finalMask"] ?: params["finalmask"].orEmpty(),
            echConfigList = ech,
            echForceQuery = params["echForceQuery"] ?: if (ech.isNotBlank()) "half" else "none",
            mldsa65Verify = params["pqv"] ?: params["mldsa65Verify"].orEmpty(),
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw,
            featureOverrides = featureOverrides,
            sourceType = if (subscriptionId != null) SourceType.SUBSCRIPTION.name else SourceType.MANUAL.name
        )
    }

    private fun parseShadowsocks(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val withoutScheme = raw.substring(raw.indexOf("://") + 3)
        val params = parseQueryParams(raw.substringAfter('?', "").substringBefore('#'))
        if (params.isNotEmpty()) return null
        val hashIdx = withoutScheme.indexOf('#')
        val remark = if (hashIdx >= 0) urlDecode(withoutScheme.substring(hashIdx + 1)).trim() else ""
        val mainPart = (if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme).substringBefore('?')

        val methodAndPass: String
        val hostAndPort: String
        if (mainPart.contains('@')) {
            val atIdx = mainPart.lastIndexOf('@')
            val userPart = mainPart.substring(0, atIdx)
            hostAndPort = mainPart.substring(atIdx + 1).removeSuffix("/")
            methodAndPass = if (userPart.contains(':')) urlDecode(userPart)
                else decodeBase64Safe(userPart) ?: urlDecode(userPart)
        } else {
            val decodedAll = decodeBase64Safe(mainPart) ?: return null
            val atIdx = decodedAll.lastIndexOf('@')
            if (atIdx < 0) return null
            methodAndPass = decodedAll.substring(0, atIdx)
            hostAndPort = decodedAll.substring(atIdx + 1)
        }

        val colonIdx = methodAndPass.indexOf(':')
        if (colonIdx <= 0) return null
        val method = methodAndPass.substring(0, colonIdx)
        val password = methodAndPass.substring(colonIdx + 1)
        val hp = splitHostPort(hostAndPort) ?: return null

        return ServerProfileEntity(
            name = remark.ifEmpty { "${hp.first}:${hp.second}" },
            protocol = ProxyProtocol.SHADOWSOCKS.name,
            address = hp.first,
            port = hp.second,
            uuidOrPassword = password,
            encryption = method,
            transport = TransportType.TCP.name,
            security = SecurityType.NONE.name,
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw,
            sourceType = if (subscriptionId != null) SourceType.SUBSCRIPTION.name else SourceType.MANUAL.name
        )
    }

    private fun parseSocks(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val parts = parseUrlParts(raw) ?: return null
        val params = parseQueryParams(parts.rawQuery)
        if (params.keys.any { it !in setOf("security", "sni", "peer", "allowInsecure", "insecure", "fp") }) return null
        if (params["security"]?.lowercase()?.let { it !in setOf("none", "tls") } == true) return null
        val decodedUser = if (parts.userInfo.isNotEmpty()) {
            if (parts.userInfo.contains(':')) urlDecode(parts.userInfo)
            else decodeBase64Safe(parts.userInfo)?.takeIf { it.contains(':') } ?: urlDecode(parts.userInfo)
        } else {
            ""
        }
        val port = parts.port ?: 1080
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty { "${parts.host}:$port" }
        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.SOCKS.name,
            address = parts.host,
            port = port,
            uuidOrPassword = decodedUser,
            encryption = "none",
            transport = TransportType.TCP.name,
            security = if (params["security"].equals("tls", true)) SecurityType.TLS.name else SecurityType.NONE.name,
            sni = params["sni"] ?: params["peer"].orEmpty(),
            fingerprint = params["fp"] ?: "chrome",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true) ||
                params["insecure"] == "1",
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw,
            sourceType = if (subscriptionId != null) SourceType.SUBSCRIPTION.name else SourceType.MANUAL.name
        )
    }



    fun parseSingleProxyUri(
        uriString: String,
        subscriptionId: String? = null,
        groupName: String = "Local"
    ): ServerProfileEntity? {
        val clean = uriString.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()
        return try {
            val parsed = when {
                lower.startsWith("vmess://") -> parseVmess(clean, subscriptionId, groupName)
                lower.startsWith("vless://") -> parseVlessOrTrojan(clean, ProxyProtocol.VLESS, subscriptionId, groupName)
                lower.startsWith("trojan://") -> parseVlessOrTrojan(clean, ProxyProtocol.TROJAN, subscriptionId, groupName)
                lower.startsWith("ss://") -> parseShadowsocks(clean, subscriptionId, groupName)
                lower.startsWith("socks://") || lower.startsWith("socks5://") -> parseSocks(clean, subscriptionId, groupName)
                else -> null
            }
            parsed?.takeIf(ClientPolicy::isSupported)
        } catch (_: Exception) {
            null
        }
    }

    private val supportedTransports = setOf("tcp", "raw", "ws", "websocket", "grpc", "httpupgrade", "xhttp", "splithttp")

    fun parseBatchWithReport(
        rawInput: String,
        subscriptionId: String? = null,
        groupName: String = "Local",
        topN: Int = 0
    ): BatchParseReport {
        if (!ImportInput.isWithinLimit(rawInput)) {
            return BatchParseReport(emptyList(), 0, 0, emptyList(), listOf(ImportInput.TOO_LARGE))
        }
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return BatchParseReport(emptyList(), 0, 0, emptyList())
        val decoded = if (!trimmed.contains("://") && !RawXrayJsonSanitizer.looksLikeJsonConfig(trimmed)) {
            decodeBase64Safe(trimmed) ?: trimmed
        } else trimmed
        if (RawXrayJsonSanitizer.looksLikeJsonConfig(decoded)) {
            val result = RawXrayJsonSanitizer.sanitizeRawXrayJson(decoded, subscriptionId, groupName)
            val kept = if (topN > 0) result.extractedNodes.take(topN) else result.extractedNodes
            return BatchParseReport(kept, result.extractedNodes.size, result.extractedNodes.size - kept.size,
                result.strippedDirectives, result.failures)
        }
        val nodes = mutableListOf<ServerProfileEntity>()
        val failures = mutableListOf<String>()
        decoded.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEachIndexed { index, line ->
                val node = parseSingleProxyUri(line, subscriptionId, groupName)
                if (node == null) failures += "Line ${index + 1}: malformed or unsupported stable Xray link"
                else nodes += node
            }
        val kept = if (topN > 0) nodes.take(topN) else nodes
        return BatchParseReport(kept, nodes.size, nodes.size - kept.size, emptyList(), failures)
    }

    fun toShareUri(profile: ServerProfileEntity): String {
        val protocol = ProxyProtocol.parse(profile.protocol)
        require(ClientPolicy.isSupported(profile)) { "This profile is not supported by the stable Xray client" }
        if (profile.rawUri.isNotBlank()) return profile.rawUri
        val remark = urlEncode(profile.name)

        return when (protocol) {
            ProxyProtocol.VMESS -> {
                val jsonObj = JSONObject().apply {
                    put("v", "2")
                    put("ps", profile.name)
                    put("add", profile.address)
                    put("port", profile.port.toString())
                    put("id", profile.uuidOrPassword)
                    put("aid", "0")
                    put("scy", profile.encryption.ifEmpty { "auto" })
                    put("net", profile.transport.lowercase())
                    put("type", "none")
                    put("host", profile.hostHeader)
                    put("path", profile.path)
                    put("tls", if (profile.security == SecurityType.TLS.name) "tls" else "")
                    put("sni", profile.sni)
                    put("alpn", profile.alpn)
                    put("fp", profile.fingerprint)
                    if (profile.cipherSuites.isNotBlank()) put("cs", profile.cipherSuites)
                    if (profile.finalMask.isNotBlank()) put("fm", profile.finalMask)
                    if (profile.echConfigList.isNotBlank()) put("ech", profile.echConfigList)
                }
                "vmess://" + Base64Compat.encodeToString(jsonObj.toString().toByteArray(Charsets.UTF_8))
            }
            ProxyProtocol.VLESS, ProxyProtocol.TROJAN -> {
                val features = if (profile.featureOverrides.isBlank()) null else NodeFeatureOverrides.decode(profile.featureOverrides)
                val q = mutableListOf<String>()
                if (protocol == ProxyProtocol.VLESS) q += "encryption=${profile.encryption.ifEmpty { "none" }}"
                q += "security=${profile.security.lowercase()}"
                q += "type=${profile.transport.lowercase()}"
                if (profile.sni.isNotBlank()) q += "sni=${urlEncode(profile.sni)}"
                if (profile.alpn.isNotBlank()) q += "alpn=${urlEncode(profile.alpn)}"
                if (profile.fingerprint.isNotBlank()) q += "fp=${urlEncode(profile.fingerprint)}"
                if (profile.publicKey.isNotBlank()) q += "pbk=${urlEncode(profile.publicKey)}"
                if (profile.shortId.isNotBlank()) q += "sid=${urlEncode(profile.shortId)}"
                if (profile.path.isNotBlank()) q += "path=${urlEncode(profile.path)}"
                if (profile.hostHeader.isNotBlank()) q += "host=${urlEncode(profile.hostHeader)}"
                if (profile.serviceName.isNotBlank()) q += "serviceName=${urlEncode(profile.serviceName)}"
                if (profile.flow.isNotBlank()) q += "flow=${urlEncode(profile.flow)}"
                if (protocol == ProxyProtocol.VLESS && features?.packetEncoding?.isNotBlank() == true) {
                    q += "packetEncoding=${urlEncode(features.packetEncoding)}"
                }
                if (TransportType.parse(profile.transport) == TransportType.XHTTP &&
                    features?.xhttpMode?.isNotBlank() == true && features.xhttpMode != "auto") {
                    q += "mode=${urlEncode(features.xhttpMode)}"
                }
                if (profile.allowInsecure) q += "allowInsecure=1"
                if (profile.cipherSuites.isNotBlank()) q += "cs=${urlEncode(profile.cipherSuites)}"
                if (profile.finalMask.isNotBlank()) q += "fm=${urlEncode(profile.finalMask)}"
                if (profile.echConfigList.isNotBlank()) q += "ech=${urlEncode(profile.echConfigList)}"
                if (profile.echForceQuery.isNotBlank() && profile.echForceQuery != "none") {
                    q += "echForceQuery=${urlEncode(profile.echForceQuery)}"
                }
                if (profile.mldsa65Verify.isNotBlank()) q += "pqv=${urlEncode(profile.mldsa65Verify)}"
                "${protocol.scheme}://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}?${q.joinToString("&")}#$remark"
            }
            ProxyProtocol.SHADOWSOCKS -> {
                val creds = "${profile.encryption.ifEmpty { "aes-256-gcm" }}:${profile.uuidOrPassword}"
                val b64 = Base64Compat.encodeToString(creds.toByteArray(Charsets.UTF_8), urlSafe = true)
                "ss://$b64@${formatHost(profile.address)}:${profile.port}#$remark"
            }
            ProxyProtocol.SOCKS -> {
                val userPart = if (profile.uuidOrPassword.isNotBlank()) "${urlEncode(profile.uuidOrPassword)}@" else ""
                "socks://$userPart${formatHost(profile.address)}:${profile.port}#$remark"
            }
            else -> error("This protocol is not supported by the stable Xray client")
        }
    }
}
