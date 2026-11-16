package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class BatchParseReport(
    val nodes: List<ServerProfileEntity>,
    val totalParsed: Int,
    val trimmedCount: Int,
    val strippedDirectives: List<String>
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
            if (rest.startsWith(":")) {
                port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 }
            }
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx)
                port = hostPort.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            } else {
                host = hostPort
            }
        }
        if (host.isEmpty()) return null
        return UrlParts(host, port, userInfo, rawQuery, rawFragment)
    }

    private fun containsKnownScheme(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "vmess://", "vless://", "trojan://", "ss://", "socks://", "socks5://", "socks4://",
            "hysteria2://", "hy2://", "wireguard://", "wg://", "awg://", "amneziawg://", "warp://",
            "masque://"
        ).any { lower.contains(it) }
    }

    fun parseAwgQuery(encoded: String): AwgProfileParams? {
        if (encoded.isBlank()) return null
        val map = parseQueryParams(encoded, caseInsensitive = true)
        val awgKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
        if (awgKeys.none { map.containsKey(it) }) return null
        return AwgProfileParams(
            jc = map["jc"]?.toIntOrNull() ?: 4,
            jmin = map["jmin"]?.toIntOrNull() ?: 40,
            jmax = map["jmax"]?.toIntOrNull() ?: 70,
            s1 = map["s1"]?.toIntOrNull() ?: 0,
            s2 = map["s2"]?.toIntOrNull() ?: 0,
            h1 = map["h1"]?.toIntOrNull() ?: 1,
            h2 = map["h2"]?.toIntOrNull() ?: 2,
            h3 = map["h3"]?.toIntOrNull() ?: 3,
            h4 = map["h4"]?.toIntOrNull() ?: 4
        )
    }

    private fun scoreNodeForLazyKeep(node: ServerProfileEntity): Int {
        var score = 0
        if (node.isPinned) score += 100
        when (SecurityType.parse(node.security)) {
            SecurityType.REALITY -> score += 45
            SecurityType.TLS -> score += 30
            SecurityType.NONE -> Unit
        }
        when (TransportType.parse(node.transport)) {
            TransportType.XHTTP -> score += 28
            TransportType.MASQUE -> score += 24
            TransportType.WS, TransportType.GRPC, TransportType.HTTPUPGRADE -> score += 22
            TransportType.WG -> score += 20
            TransportType.QUIC -> score += 18
            TransportType.DNSTT, TransportType.MASTERDNS -> score += 14
            TransportType.TCP -> Unit
        }
        when (ProxyProtocol.parse(node.protocol)) {
            ProxyProtocol.VLESS -> score += 20
            ProxyProtocol.MASQUE -> score += 19
            ProxyProtocol.WIREGUARD -> score += 18
            ProxyProtocol.TROJAN, ProxyProtocol.HYSTERIA2 -> score += 16
            ProxyProtocol.HYSTERIA, ProxyProtocol.TUIC, ProxyProtocol.ANYTLS -> score += 15
            ProxyProtocol.VMESS -> score += 12
            ProxyProtocol.SHADOWSOCKS -> score += 8
            ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS -> score += 7
            ProxyProtocol.SOCKS -> Unit
            ProxyProtocol.SSH, ProxyProtocol.OPENCONNECT, ProxyProtocol.OPENVPN,
            ProxyProtocol.MIERU, ProxyProtocol.HTTP -> score += 6
        }
        if (node.echConfigList.isNotBlank()) score += 12
        if (node.finalMask.isNotBlank()) score += 10
        if (node.mldsa65Verify.isNotBlank()) score += 10
        val lat = node.latencyMs
        if (lat != null && lat > 0) {
            score += (30 - (lat / 40)).coerceIn(0, 30)
        }
        return score
    }

    fun rankAndTrimTopN(nodes: List<ServerProfileEntity>, topN: Int): List<ServerProfileEntity> {
        if (topN <= 0 || nodes.size <= topN) return nodes
        return nodes.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ServerProfileEntity>> { scoreNodeForLazyKeep(it.value) }
                    .thenBy { it.index }
            )
            .take(topN)
            .map { it.value }
    }

    private fun parseVmess(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val payload = raw.substring(raw.indexOf("://") + 3).substringBefore('#').trim()
        val jsonStr = decodeBase64Safe(payload) ?: return null
        val json = JSONObject(jsonStr)
        val address = json.optString("add", "").trim()
        val port = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
        val uuid = json.optString("id", "").trim()
        if (address.isEmpty() || port !in 1..65535 || uuid.isEmpty()) return null

        val remark = json.optString("ps", "").trim().ifEmpty { "$address:$port" }
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")
        val security = if (tls.equals("tls", ignoreCase = true)) SecurityType.TLS else SecurityType.NONE

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
            hostHeader = json.optString("host", ""),
            cipherSuites = json.optString("cs", json.optString("cipherSuites", "")),
            finalMask = json.optString("fm", json.optString("finalMask", "")),
            echConfigList = json.optString("ech", json.optString("echConfigList", "")),
            echForceQuery = json.optString("echForceQuery", "none"),
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
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
        val port = parts.port ?: 443
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty { "${parts.host}:$port" }
        val defaultSec = if (protocol == ProxyProtocol.TROJAN) SecurityType.TLS else SecurityType.NONE
        val secType = params["security"]?.let { SecurityType.parse(it) } ?: defaultSec
        val ech = params["ech"] ?: params["echConfigList"].orEmpty()

        return ServerProfileEntity(
            name = remark,
            protocol = protocol.name,
            address = parts.host,
            port = port,
            uuidOrPassword = urlDecode(parts.userInfo),
            encryption = params["encryption"] ?: "none",
            transport = TransportType.parse(params["type"]).name,
            security = secType.name,
            sni = params["sni"].orEmpty(),
            alpn = params["alpn"].orEmpty(),
            fingerprint = params["fp"] ?: "chrome",
            publicKey = params["pbk"].orEmpty(),
            shortId = params["sid"].orEmpty(),
            path = params["path"].orEmpty(),
            hostHeader = params["host"].orEmpty(),
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
            rawUri = raw
        )
    }

    private fun parseShadowsocks(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val withoutScheme = raw.substring(raw.indexOf("://") + 3)
        val hashIdx = withoutScheme.indexOf('#')
        val remark = if (hashIdx >= 0) urlDecode(withoutScheme.substring(hashIdx + 1)).trim() else ""
        val mainPart = (if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme).substringBefore('?')

        val methodAndPass: String
        val hostAndPort: String
        if (mainPart.contains('@')) {
            val atIdx = mainPart.indexOf('@')
            val userPart = mainPart.substring(0, atIdx)
            hostAndPort = mainPart.substring(atIdx + 1)
            methodAndPass = decodeBase64Safe(userPart) ?: urlDecode(userPart)
        } else {
            val decodedAll = decodeBase64Safe(mainPart) ?: return null
            val atIdx = decodedAll.lastIndexOf('@')
            if (atIdx < 0) return null
            methodAndPass = decodedAll.substring(0, atIdx)
            hostAndPort = decodedAll.substring(atIdx + 1)
        }

        val colonIdx = methodAndPass.indexOf(':')
        val method = if (colonIdx >= 0) methodAndPass.substring(0, colonIdx) else "aes-256-gcm"
        val password = if (colonIdx >= 0) methodAndPass.substring(colonIdx + 1) else ""
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
            rawUri = raw
        )
    }

    private fun parseSocks(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val parts = parseUrlParts(raw) ?: return null
        val decodedUser = if (parts.userInfo.isNotEmpty()) {
            decodeBase64Safe(parts.userInfo) ?: urlDecode(parts.userInfo)
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
            security = SecurityType.NONE.name,
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseHysteria2(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val parts = parseUrlParts(raw) ?: return null
        val params = parseQueryParams(parts.rawQuery)
        val port = parts.port ?: 443
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty { "${parts.host}:$port" }
        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.HYSTERIA2.name,
            address = parts.host,
            port = port,
            uuidOrPassword = urlDecode(parts.userInfo),
            encryption = params["obfs"] ?: "none",
            transport = TransportType.QUIC.name,
            security = SecurityType.TLS.name,
            sni = params["sni"].orEmpty(),
            allowInsecure = params["insecure"] == "1" || params["allowInsecure"] == "1",
            finalMask = params["fm"] ?: params["finalMask"].orEmpty(),
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseWireGuardLike(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val schemeLower = raw.substring(0, raw.indexOf("://")).lowercase()
        val parts = parseUrlParts(raw) ?: return null
        val params = parseQueryParams(parts.rawQuery, caseInsensitive = true)
        val privateKey = urlDecode(parts.userInfo).ifEmpty {
            params["privatekey"] ?: params["privkey"] ?: params["secretkey"].orEmpty()
        }
        val publicKey = params["publickey"] ?: params["pubkey"] ?: params["pbk"].orEmpty()
        if (privateKey.isEmpty() && publicKey.isEmpty()) return null

        val port = parts.port ?: 2408
        val localAddr = params["address"] ?: params["localaddress"] ?: params["ip"] ?: "172.16.0.2/32"
        val reserved = params["reserved"] ?: params["clientid"] ?: "0,0,0"
        val mtuInt = params["mtu"]?.toIntOrNull()
        val mtu = if (mtuInt != null && mtuInt in 576..1500) mtuInt.toString() else ""
        val shaping = (params["shaping"] ?: params["preset"] ?: params["noisepreset"] ?: "quic").lowercase()
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty {
            when (schemeLower) {
                "awg" -> "AmneziaWG ${parts.host}"
                "warp" -> "WARP ${parts.host}"
                else -> "${parts.host}:$port"
            }
        }
        val hasAwgKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
            .any { params.containsKey(it) } || schemeLower == "awg"
        val awgEncoded = if (hasAwgKeys) {
            AwgProfileParams(
                jc = params["jc"]?.toIntOrNull() ?: 4,
                jmin = params["jmin"]?.toIntOrNull() ?: 40,
                jmax = params["jmax"]?.toIntOrNull() ?: 70,
                s1 = params["s1"]?.toIntOrNull() ?: 0,
                s2 = params["s2"]?.toIntOrNull() ?: 0,
                h1 = params["h1"]?.toIntOrNull() ?: 1,
                h2 = params["h2"]?.toIntOrNull() ?: 2,
                h3 = params["h3"]?.toIntOrNull() ?: 3,
                h4 = params["h4"]?.toIntOrNull() ?: 4
            ).toQuery()
        } else {
            ""
        }

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.WIREGUARD.name,
            address = parts.host,
            port = port,
            uuidOrPassword = privateKey,
            encryption = shaping,
            transport = TransportType.WG.name,
            security = SecurityType.NONE.name,
            publicKey = publicKey,
            shortId = reserved,
            path = localAddr,
            serviceName = awgEncoded,
            flow = mtu,
            finalMask = params["fm"] ?: params["finalmask"] ?: if (hasAwgKeys) "awg" else "",
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseMasque(raw: String, subscriptionId: String?, groupName: String): ServerProfileEntity? {
        val parts = parseUrlParts(raw) ?: return null
        val params = parseQueryParams(parts.rawQuery, caseInsensitive = true)
        val port = parts.port ?: 443
        val privateKey = urlDecode(parts.userInfo).ifEmpty {
            params["privatekey"] ?: params["privkey"] ?: params["token"] ?: params["auth"] ?: "masque-auth"
        }
        val publicKey = params["publickey"] ?: params["pbk"].orEmpty()
        val sni = params["sni"] ?: "consumer-masque.cloudflareclient.com"
        val localAddr = params["address"] ?: params["localaddress"] ?: "172.16.0.2/32"
        val mtuInt = params["mtu"]?.toIntOrNull()
        val mtu = if (mtuInt != null && mtuInt in 576..1500) mtuInt.toString() else ""
        val shaping = (params["shaping"] ?: params["preset"] ?: "quic").lowercase()
        val remark = urlDecode(parts.rawFragment).trim().ifEmpty { "MASQUE ${parts.host}:$port" }

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.MASQUE.name,
            address = parts.host,
            port = port,
            uuidOrPassword = privateKey,
            encryption = shaping,
            transport = TransportType.MASQUE.name,
            security = SecurityType.TLS.name,
            sni = sni,
            publicKey = publicKey,
            path = localAddr,
            flow = mtu,
            allowInsecure = params["allowinsecure"] == "1" || params["insecure"] == "1",
            finalMask = params["fm"] ?: params["finalmask"].orEmpty(),
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
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
                lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> parseHysteria2(clean, subscriptionId, groupName)
                lower.startsWith("wireguard://") || lower.startsWith("awg://") || lower.startsWith("warp://") ->
                    parseWireGuardLike(clean, subscriptionId, groupName)
                lower.startsWith("masque://") -> parseMasque(clean, subscriptionId, groupName)
                lower.startsWith("socks4://") -> parseSocks(clean, subscriptionId, groupName)
                lower.startsWith("amneziawg://") || lower.startsWith("wg://") ->
                    parseWireGuardLike(clean, subscriptionId, groupName)
                else -> null
            }
            parsed?.takeIf { ProtocolGuardrails.isCompiledByCore(ProxyProtocol.parse(it.protocol)) }
        } catch (_: Exception) {
            null
        }
    }

    fun parseBatchWithReport(
        rawInput: String,
        subscriptionId: String? = null,
        groupName: String = "Local",
        topN: Int = 0
    ): BatchParseReport {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return BatchParseReport(emptyList(), 0, 0, emptyList())
        }
        var decodedText = trimmed
        if (!containsKnownScheme(trimmed) && !RawXrayJsonSanitizer.looksLikeJsonConfig(trimmed)) {
            val decoded = decodeBase64Safe(trimmed)
            if (decoded != null && (containsKnownScheme(decoded) || RawXrayJsonSanitizer.looksLikeJsonConfig(decoded))) {
                decodedText = decoded
            }
        }

        val stripped = mutableListOf<String>()
        val allNodes = mutableListOf<ServerProfileEntity>()

        if (RawXrayJsonSanitizer.looksLikeJsonConfig(decodedText)) {
            val sanitized = RawXrayJsonSanitizer.sanitizeRawXrayJson(decodedText, subscriptionId, groupName)
            stripped += sanitized.strippedDirectives
            allNodes += sanitized.extractedNodes.filter {
                ProtocolGuardrails.isCompiledByCore(ProxyProtocol.parse(it.protocol))
            }
        } else {
            for (rawLine in decodedText.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (RawXrayJsonSanitizer.looksLikeJsonConfig(line)) {
                    val sanitized = RawXrayJsonSanitizer.sanitizeRawXrayJson(line, subscriptionId, groupName)
                    stripped += sanitized.strippedDirectives
                    allNodes += sanitized.extractedNodes.filter {
                        ProtocolGuardrails.isCompiledByCore(ProxyProtocol.parse(it.protocol))
                    }
                } else {
                    parseSingleProxyUri(line, subscriptionId, groupName)?.let { allNodes += it }
                }
            }
        }

        val kept = rankAndTrimTopN(allNodes, topN)
        return BatchParseReport(
            nodes = kept,
            totalParsed = allNodes.size,
            trimmedCount = (allNodes.size - kept.size).coerceAtLeast(0),
            strippedDirectives = stripped.distinct()
        )
    }

    fun toShareUri(profile: ServerProfileEntity): String {
        val protocol = ProxyProtocol.parse(profile.protocol)
        require(ProtocolGuardrails.isCompiledByCore(protocol)) {
            ProtocolGuardrails.uncompiledProtocolMessage(protocol)
        }
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
            ProxyProtocol.HYSTERIA2 -> {
                val q = mutableListOf<String>()
                if (profile.sni.isNotBlank()) q += "sni=${urlEncode(profile.sni)}"
                if (profile.allowInsecure) q += "insecure=1"
                val qPart = if (q.isNotEmpty()) "?${q.joinToString("&")}" else ""
                "hysteria2://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.SOCKS -> {
                val userPart = if (profile.uuidOrPassword.isNotBlank()) "${urlEncode(profile.uuidOrPassword)}@" else ""
                "socks://$userPart${formatHost(profile.address)}:${profile.port}#$remark"
            }
            ProxyProtocol.WIREGUARD -> {
                val awg = parseAwgQuery(profile.serviceName)
                val scheme = if (awg != null || profile.finalMask.equals("awg", ignoreCase = true)) "awg" else "wireguard"
                val q = mutableListOf<String>()
                if (profile.publicKey.isNotBlank()) q += "publickey=${urlEncode(profile.publicKey)}"
                if (profile.path.isNotBlank()) q += "address=${urlEncode(profile.path)}"
                if (profile.shortId.isNotBlank()) q += "reserved=${urlEncode(profile.shortId)}"
                if (profile.flow.isNotBlank()) q += "mtu=${urlEncode(profile.flow)}"
                if (profile.encryption.isNotBlank() && profile.encryption != "none") {
                    q += "shaping=${urlEncode(profile.encryption)}"
                }
                if (awg != null) q += awg.toQuery()
                val qPart = if (q.isNotEmpty()) "?${q.joinToString("&")}" else ""
                "$scheme://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.MASQUE -> {
                val q = mutableListOf<String>()
                if (profile.sni.isNotBlank()) q += "sni=${urlEncode(profile.sni)}"
                if (profile.publicKey.isNotBlank()) q += "publickey=${urlEncode(profile.publicKey)}"
                if (profile.path.isNotBlank()) q += "address=${urlEncode(profile.path)}"
                if (profile.flow.isNotBlank()) q += "mtu=${urlEncode(profile.flow)}"
                if (profile.encryption.isNotBlank() && profile.encryption != "none") {
                    q += "shaping=${urlEncode(profile.encryption)}"
                }
                if (profile.finalMask.isNotBlank()) q += "fm=${urlEncode(profile.finalMask)}"
                val qPart = if (q.isNotEmpty()) "?${q.joinToString("&")}" else ""
                "masque://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.HTTP -> error("HTTP proxy URI sharing is unsupported")
            ProxyProtocol.HYSTERIA, ProxyProtocol.TUIC, ProxyProtocol.ANYTLS, ProxyProtocol.SSH,
            ProxyProtocol.OPENCONNECT, ProxyProtocol.OPENVPN, ProxyProtocol.MIERU,
            ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS ->
                error(ProtocolGuardrails.uncompiledProtocolMessage(protocol))
        }
    }
}
