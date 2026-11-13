package com.example.core

import android.util.Base64
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AwgProfileParams(
    val jc: Int = 4,
    val jmin: Int = 40,
    val jmax: Int = 70,
    val s1: Int = 0,
    val s2: Int = 0,
    val h1: Long = 1L,
    val h2: Long = 2L,
    val h3: Long = 3L,
    val h4: Long = 4L
) {
    fun encodeQuery(): String =
        "jc=$jc&jmin=$jmin&jmax=$jmax&s1=$s1&s2=$s2&h1=$h1&h2=$h2&h3=$h3&h4=$h4"
}

data class BatchParseReport(
    val nodes: List<ServerProfileEntity>,
    val totalParsed: Int = nodes.size,
    val trimmedCount: Int = 0,
    val strippedDirectives: List<String> = emptyList()
)

object ProxyUriParser {

    fun parseBatch(
        rawInput: String,
        subscriptionId: String? = null,
        groupName: String = "Local",
        topN: Int = 0
    ): List<ServerProfileEntity> =
        parseBatchWithReport(
            rawInput = rawInput,
            subscriptionId = subscriptionId,
            groupName = groupName,
            topN = topN
        ).nodes

    fun parseBatchWithReport(
        rawInput: String,
        subscriptionId: String? = null,
        groupName: String = "Local",
        topN: Int = 0
    ): BatchParseReport {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return BatchParseReport(emptyList())

        val decodedText = if (!containsKnownScheme(trimmed) && !RawXrayJsonSanitizer.looksLikeJsonConfig(trimmed)) {
            decodeBase64Safe(trimmed)?.takeIf {
                containsKnownScheme(it) || RawXrayJsonSanitizer.looksLikeJsonConfig(it)
            } ?: trimmed
        } else {
            trimmed
        }

        val stripped = mutableListOf<String>()
        val allNodes = mutableListOf<ServerProfileEntity>()

        if (RawXrayJsonSanitizer.looksLikeJsonConfig(decodedText)) {
            val sanitized = RawXrayJsonSanitizer.sanitize(
                rawJson = decodedText,
                subscriptionId = subscriptionId,
                groupName = groupName
            )
            stripped.addAll(sanitized.strippedDirectives)
            allNodes.addAll(sanitized.extractedNodes)
        } else {
            decodedText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (RawXrayJsonSanitizer.looksLikeJsonConfig(line)) {
                        val sanitized = RawXrayJsonSanitizer.sanitize(line, subscriptionId, groupName)
                        stripped.addAll(sanitized.strippedDirectives)
                        allNodes.addAll(sanitized.extractedNodes)
                    } else {
                        parseSingle(line, subscriptionId, groupName)?.let { allNodes.add(it) }
                    }
                }
        }

        val kept = rankAndTrimTopN(allNodes, topN)
        val trimmedCount = (allNodes.size - kept.size).coerceAtLeast(0)
        return BatchParseReport(
            nodes = kept,
            totalParsed = allNodes.size,
            trimmedCount = trimmedCount,
            strippedDirectives = stripped.distinct()
        )
    }

    fun rankAndTrimTopN(nodes: List<ServerProfileEntity>, topN: Int): List<ServerProfileEntity> {
        if (topN <= 0 || nodes.size <= topN) return nodes
        return nodes
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ServerProfileEntity>> { scoreNodeForLazyKeep(it.value) }
                    .thenBy { it.index }
            )
            .take(topN)
            .map { it.value }
    }

    private fun scoreNodeForLazyKeep(node: ServerProfileEntity): Int {
        var score = 0
        if (node.isPinned) score += 100
        when (node.security) {
            SecurityType.REALITY.name -> score += 45
            SecurityType.TLS.name -> score += 30
        }
        when (node.transport) {
            TransportType.XHTTP.name -> score += 28
            TransportType.MASQUE.name -> score += 24
            TransportType.WS.name, TransportType.GRPC.name, TransportType.HTTPUPGRADE.name -> score += 22
            TransportType.WG.name -> score += 20
            TransportType.QUIC.name -> score += 18
            TransportType.DNSTT.name, TransportType.MASTERDNS.name -> score += 14
        }
        when (node.protocol) {
            ProxyProtocol.VLESS.name -> score += 20
            ProxyProtocol.MASQUE.name -> score += 19
            ProxyProtocol.WIREGUARD.name -> score += 18
            ProxyProtocol.TROJAN.name, ProxyProtocol.HYSTERIA2.name -> score += 16
            ProxyProtocol.VMESS.name -> score += 12
            ProxyProtocol.SHADOWSOCKS.name -> score += 8
            ProxyProtocol.DNSTT.name, ProxyProtocol.MASTERDNS.name -> score += 7
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

    fun parseSingle(
        uriString: String,
        subscriptionId: String? = null,
        groupName: String = "Local"
    ): ServerProfileEntity? {
        val clean = uriString.trim()
        return try {
            when {
                clean.startsWith("vmess://", ignoreCase = true) ->
                    parseVmess(clean, subscriptionId, groupName)
                clean.startsWith("vless://", ignoreCase = true) ->
                    parseVlessOrTrojan(clean, ProxyProtocol.VLESS, subscriptionId, groupName)
                clean.startsWith("trojan://", ignoreCase = true) ->
                    parseVlessOrTrojan(clean, ProxyProtocol.TROJAN, subscriptionId, groupName)
                clean.startsWith("ss://", ignoreCase = true) ->
                    parseShadowsocks(clean, subscriptionId, groupName)
                clean.startsWith("socks://", ignoreCase = true) || clean.startsWith("socks5://", ignoreCase = true) ->
                    parseSocks(clean, subscriptionId, groupName)
                clean.startsWith("hysteria2://", ignoreCase = true) || clean.startsWith("hy2://", ignoreCase = true) ->
                    parseHysteria2(clean, subscriptionId, groupName)
                clean.startsWith("wireguard://", ignoreCase = true) ||
                    clean.startsWith("awg://", ignoreCase = true) ||
                    clean.startsWith("warp://", ignoreCase = true) ->
                    parseWireGuardLike(clean, subscriptionId, groupName)
                clean.startsWith("masque://", ignoreCase = true) ->
                    parseMasque(clean, subscriptionId, groupName)
                clean.startsWith("dnstt://", ignoreCase = true) ->
                    parseDnsTunnel(clean, ProxyProtocol.DNSTT, subscriptionId, groupName)
                clean.startsWith("masterdns://", ignoreCase = true) ->
                    parseDnsTunnel(clean, ProxyProtocol.MASTERDNS, subscriptionId, groupName)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmess(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val payload = raw.substringAfter("://").substringBefore("#").trim()
        val jsonStr = decodeBase64Safe(payload) ?: return null
        val json = JSONObject(jsonStr)
        val address = json.optString("add").trim()
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        val uuid = json.optString("id").trim()
        if (address.isEmpty() || port !in 1..65535 || uuid.isEmpty()) return null

        val remark = json.optString("ps").ifBlank { "$address:$port" }
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")
        val security = if (tls.equals("tls", ignoreCase = true)) SecurityType.TLS else SecurityType.NONE

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.VMESS.name,
            address = address,
            port = port,
            uuidOrPassword = uuid,
            encryption = json.optString("scy", "auto").ifBlank { "auto" },
            transport = TransportType.fromString(net).name,
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
        val uri = URI(raw.replace(" ", "%20"))
        val address = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val userInfo = uri.userInfo ?: return null
        val params = parseQueryParams(uri.rawQuery)
        val remark = decodeFragment(uri.rawFragment).ifBlank { "$address:$port" }

        val defaultSecurity = if (protocol == ProxyProtocol.TROJAN) SecurityType.TLS else SecurityType.NONE
        val secType = params["security"]?.let { SecurityType.fromString(it) } ?: defaultSecurity

        return ServerProfileEntity(
            name = remark,
            protocol = protocol.name,
            address = address,
            port = port,
            uuidOrPassword = urlDecode(userInfo),
            encryption = params["encryption"] ?: "none",
            transport = TransportType.fromString(params["type"]).name,
            security = secType.name,
            sni = params["sni"] ?: "",
            alpn = params["alpn"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            path = params["path"] ?: "",
            hostHeader = params["host"] ?: "",
            serviceName = params["serviceName"] ?: "",
            flow = params["flow"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" ||
                params["allowInsecure"].equals("true", ignoreCase = true) ||
                params["insecure"] == "1",
            cipherSuites = params["cs"] ?: params["cipherSuites"] ?: "",
            finalMask = params["fm"] ?: params["finalMask"] ?: params["finalmask"] ?: "",
            echConfigList = params["ech"] ?: params["echConfigList"] ?: "",
            echForceQuery = params["echForceQuery"] ?: if (!params["ech"].isNullOrBlank()) "half" else "none",
            mldsa65Verify = params["pqv"] ?: params["mldsa65Verify"] ?: "",
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseShadowsocks(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val withoutScheme = raw.substringAfter("://")
        val remark = decodeFragment(withoutScheme.substringAfter("#", "")).ifBlank { "" }
        val mainPart = withoutScheme.substringBefore("#").substringBefore("?")

        val (methodAndPass, hostAndPort) = if (mainPart.contains("@")) {
            val userPart = mainPart.substringBefore("@")
            val hostPart = mainPart.substringAfter("@")
            val decodedUser = decodeBase64Safe(userPart) ?: urlDecode(userPart)
            decodedUser to hostPart
        } else {
            val decodedAll = decodeBase64Safe(mainPart) ?: return null
            if (!decodedAll.contains("@")) return null
            decodedAll.substringBeforeLast("@") to decodedAll.substringAfterLast("@")
        }

        val method = methodAndPass.substringBefore(":", "aes-256-gcm")
        val password = methodAndPass.substringAfter(":", "")
        val (host, port) = splitHostPort(hostAndPort) ?: return null

        return ServerProfileEntity(
            name = remark.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.SHADOWSOCKS.name,
            address = host,
            port = port,
            uuidOrPassword = password,
            encryption = method,
            transport = TransportType.TCP.name,
            security = SecurityType.NONE.name,
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseSocks(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val normalized = if (raw.startsWith("socks5://", ignoreCase = true)) {
            "socks://" + raw.substringAfter("://")
        } else raw
        val uri = URI(normalized.replace(" ", "%20"))
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 1080
        val rawUser = uri.userInfo ?: ""
        val decodedUser = decodeBase64Safe(rawUser) ?: urlDecode(rawUser)
        val remark = decodeFragment(uri.rawFragment).ifBlank { "$host:$port" }

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.SOCKS.name,
            address = host,
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

    private fun parseHysteria2(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val normalized = "https://" + raw.substringAfter("://")
        val uri = URI(normalized.replace(" ", "%20"))
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val auth = urlDecode(uri.userInfo ?: "")
        val params = parseQueryParams(uri.rawQuery)
        val remark = decodeFragment(uri.rawFragment).ifBlank { "$host:$port" }

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.HYSTERIA2.name,
            address = host,
            port = port,
            uuidOrPassword = auth,
            encryption = params["obfs"] ?: "none",
            transport = TransportType.QUIC.name,
            security = SecurityType.TLS.name,
            sni = params["sni"] ?: "",
            allowInsecure = params["insecure"] == "1" || params["allowInsecure"] == "1",
            finalMask = params["fm"] ?: params["finalMask"] ?: "",
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    fun toShareUri(profile: ServerProfileEntity): String {
        if (profile.rawUri.isNotBlank()) return profile.rawUri
        val protocol = ProxyProtocol.fromString(profile.protocol)
        val remark = URLEncoder.encode(profile.name, "UTF-8").replace("+", "%20")
        return when (protocol) {
            ProxyProtocol.VMESS -> {
                val json = JSONObject().apply {
                    put("v", "2")
                    put("ps", profile.name)
                    put("add", profile.address)
                    put("port", profile.port.toString())
                    put("id", profile.uuidOrPassword)
                    put("aid", "0")
                    put("scy", profile.encryption.ifBlank { "auto" })
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
                val b64 = Base64.encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                "vmess://$b64"
            }
            ProxyProtocol.VLESS, ProxyProtocol.TROJAN -> {
                val scheme = protocol.scheme
                val query = buildList {
                    if (protocol == ProxyProtocol.VLESS) add("encryption=${profile.encryption.ifBlank { "none" }}")
                    add("security=${profile.security.lowercase()}")
                    add("type=${profile.transport.lowercase()}")
                    if (profile.sni.isNotBlank()) add("sni=${urlEncode(profile.sni)}")
                    if (profile.alpn.isNotBlank()) add("alpn=${urlEncode(profile.alpn)}")
                    if (profile.fingerprint.isNotBlank()) add("fp=${urlEncode(profile.fingerprint)}")
                    if (profile.publicKey.isNotBlank()) add("pbk=${urlEncode(profile.publicKey)}")
                    if (profile.shortId.isNotBlank()) add("sid=${urlEncode(profile.shortId)}")
                    if (profile.path.isNotBlank()) add("path=${urlEncode(profile.path)}")
                    if (profile.hostHeader.isNotBlank()) add("host=${urlEncode(profile.hostHeader)}")
                    if (profile.serviceName.isNotBlank()) add("serviceName=${urlEncode(profile.serviceName)}")
                    if (profile.flow.isNotBlank()) add("flow=${urlEncode(profile.flow)}")
                    if (profile.allowInsecure) add("allowInsecure=1")
                    if (profile.cipherSuites.isNotBlank()) add("cs=${urlEncode(profile.cipherSuites)}")
                    if (profile.finalMask.isNotBlank()) add("fm=${urlEncode(profile.finalMask)}")
                    if (profile.echConfigList.isNotBlank()) add("ech=${urlEncode(profile.echConfigList)}")
                    if (profile.echForceQuery.isNotBlank() && profile.echForceQuery != "none") {
                        add("echForceQuery=${urlEncode(profile.echForceQuery)}")
                    }
                    if (profile.mldsa65Verify.isNotBlank()) add("pqv=${urlEncode(profile.mldsa65Verify)}")
                }.joinToString("&")
                "$scheme://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}?$query#$remark"
            }
            ProxyProtocol.SHADOWSOCKS -> {
                val creds = "${profile.encryption.ifBlank { "aes-256-gcm" }}:${profile.uuidOrPassword}"
                val b64 = Base64.encodeToString(creds.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
                "ss://$b64@${formatHost(profile.address)}:${profile.port}#$remark"
            }
            ProxyProtocol.HYSTERIA2 -> {
                val query = buildList {
                    if (profile.sni.isNotBlank()) add("sni=${urlEncode(profile.sni)}")
                    if (profile.allowInsecure) add("insecure=1")
                }.joinToString("&")
                val qPart = if (query.isNotEmpty()) "?$query" else ""
                "hysteria2://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.SOCKS -> {
                val userPart = if (profile.uuidOrPassword.isNotBlank()) "${urlEncode(profile.uuidOrPassword)}@" else ""
                "socks://$userPart${formatHost(profile.address)}:${profile.port}#$remark"
            }
            ProxyProtocol.WIREGUARD -> {
                val awg = parseAwgQuery(profile.serviceName)
                val scheme = if (awg != null || profile.finalMask.equals("awg", ignoreCase = true)) "awg" else "wireguard"
                val query = buildList {
                    if (profile.publicKey.isNotBlank()) add("publickey=${urlEncode(profile.publicKey)}")
                    if (profile.path.isNotBlank()) add("address=${urlEncode(profile.path)}")
                    if (profile.shortId.isNotBlank()) add("reserved=${urlEncode(profile.shortId)}")
                    if (profile.flow.isNotBlank()) add("mtu=${urlEncode(profile.flow)}")
                    if (profile.encryption.isNotBlank() && profile.encryption != "none") {
                        add("shaping=${urlEncode(profile.encryption)}")
                    }
                    if (awg != null) {
                        add(awg.encodeQuery())
                    }
                }.joinToString("&")
                val qPart = if (query.isNotEmpty()) "?$query" else ""
                "$scheme://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.MASQUE -> {
                val query = buildList {
                    if (profile.sni.isNotBlank()) add("sni=${urlEncode(profile.sni)}")
                    if (profile.publicKey.isNotBlank()) add("publickey=${urlEncode(profile.publicKey)}")
                    if (profile.path.isNotBlank()) add("address=${urlEncode(profile.path)}")
                    if (profile.flow.isNotBlank()) add("mtu=${urlEncode(profile.flow)}")
                    if (profile.encryption.isNotBlank() && profile.encryption != "none") {
                        add("shaping=${urlEncode(profile.encryption)}")
                    }
                    if (profile.finalMask.isNotBlank()) add("fm=${urlEncode(profile.finalMask)}")
                }.joinToString("&")
                val qPart = if (query.isNotEmpty()) "?$query" else ""
                "masque://${urlEncode(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
            ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS -> {
                val query = buildList {
                    if (profile.hostHeader.isNotBlank()) add("domain=${urlEncode(profile.hostHeader)}")
                    val mode = profile.encryption.ifBlank { "udp" }.lowercase()
                    add("transport=${urlEncode(mode)}")
                    val pub = profile.publicKey.ifBlank { profile.uuidOrPassword }
                    if (pub.isNotBlank()) add("pubkey=${urlEncode(pub)}")
                    if (profile.flow.isNotBlank()) add("mtu=${urlEncode(profile.flow)}")
                    if (profile.path.isNotBlank()) add("doh=${urlEncode(profile.path)}")
                    if (profile.sni.isNotBlank()) add("sni=${urlEncode(profile.sni)}")
                }.joinToString("&")
                val user = profile.uuidOrPassword.ifBlank { profile.publicKey }
                val userPart = if (user.isNotBlank()) "${urlEncode(user)}@" else ""
                val qPart = if (query.isNotEmpty()) "?$query" else ""
                "${protocol.scheme}://$userPart${formatHost(profile.address)}:${profile.port}$qPart#$remark"
            }
        }
    }

    private fun parseWireGuardLike(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val schemeLower = raw.substringBefore("://").lowercase()
        val normalized = "https://" + raw.substringAfter("://")
        val uri = URI(normalized.replace(" ", "%20"))
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 2408
        val params = parseQueryParamsCaseInsensitive(uri.rawQuery)
        val userKey = uri.userInfo?.let { urlDecode(it) }.orEmpty()
        val privateKey = userKey.ifBlank {
            params["privatekey"] ?: params["privkey"] ?: params["secretkey"] ?: ""
        }
        val publicKey = params["publickey"] ?: params["pubkey"] ?: params["pbk"] ?: ""
        if (privateKey.isBlank() && publicKey.isBlank()) return null

        val localAddr = (params["address"] ?: params["localaddress"] ?: params["ip"]).orEmpty()
            .ifBlank { "172.16.0.2/32" }
        val reserved = (params["reserved"] ?: params["clientid"]).orEmpty().ifBlank { "0,0,0" }
        val mtu = params["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 }?.toString() ?: ""
        val shaping = (params["shaping"] ?: params["preset"] ?: params["noisepreset"] ?: "quic").lowercase()
        val remark = decodeFragment(uri.rawFragment).ifBlank {
            if (schemeLower == "awg") "AmneziaWG $host" else if (schemeLower == "warp") "WARP $host" else "$host:$port"
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
                h1 = params["h1"]?.toLongOrNull() ?: 1L,
                h2 = params["h2"]?.toLongOrNull() ?: 2L,
                h3 = params["h3"]?.toLongOrNull() ?: 3L,
                h4 = params["h4"]?.toLongOrNull() ?: 4L
            ).encodeQuery()
        } else ""

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.WIREGUARD.name,
            address = host,
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

    private fun parseMasque(
        raw: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val normalized = "https://" + raw.substringAfter("://")
        val uri = URI(normalized.replace(" ", "%20"))
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val params = parseQueryParamsCaseInsensitive(uri.rawQuery)
        val userKey = uri.userInfo?.let { urlDecode(it) }.orEmpty()
        val privateKey = userKey.ifBlank {
            params["privatekey"] ?: params["privkey"] ?: params["token"] ?: params["auth"] ?: "masque-auth"
        }
        val publicKey = params["publickey"] ?: params["pbk"] ?: ""
        val sni = params["sni"] ?: "consumer-masque.cloudflareclient.com"
        val localAddr = (params["address"] ?: params["localaddress"] ?: "172.16.0.2/32")
        val mtu = params["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 }?.toString() ?: ""
        val shaping = (params["shaping"] ?: params["preset"] ?: "quic").lowercase()
        val remark = decodeFragment(uri.rawFragment).ifBlank { "MASQUE $host:$port" }

        return ServerProfileEntity(
            name = remark,
            protocol = ProxyProtocol.MASQUE.name,
            address = host,
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
            finalMask = params["fm"] ?: params["finalmask"] ?: "",
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    private fun parseDnsTunnel(
        raw: String,
        protocol: ProxyProtocol,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val normalized = "https://" + raw.substringAfter("://")
        val uri = URI(normalized.replace(" ", "%20"))
        val params = parseQueryParamsCaseInsensitive(uri.rawQuery)
        val resolverParam = params["resolver"].orEmpty()
        val resolverPair = if (resolverParam.isNotBlank()) splitHostPort(resolverParam) else null

        val mode = (params["transport"] ?: params["mode"] ?: params["type"] ?: when {
            !params["doh"].isNullOrBlank() -> "doh"
            !params["dot"].isNullOrBlank() -> "dot"
            else -> "udp"
        }).lowercase().let {
            if (it in setOf("udp", "tcp", "dot", "doh")) it else "udp"
        }

        val defaultPort = when (mode) {
            "doh" -> 443
            "dot" -> 853
            else -> 53
        }
        val host = uri.host ?: resolverPair?.first ?: return null
        val port = if (uri.port in 1..65535) uri.port else (resolverPair?.second ?: defaultPort)
        val userKey = uri.userInfo?.let { urlDecode(it) }.orEmpty()
        val publicKey = (params["pubkey"] ?: params["publickey"] ?: params["pbk"] ?: userKey).trim()
        val nsDomain = (params["domain"] ?: params["ns"] ?: params["nameserver"] ?: params["host"]).orEmpty().trim()
        if (nsDomain.isBlank() && publicKey.isBlank()) return null

        val mtu = params["mtu"]?.toIntOrNull()?.takeIf { it in 256..1500 }?.toString() ?: ""
        val dohUrl = (params["doh"] ?: params["dohurl"]).orEmpty().trim()
        val sni = (params["sni"] ?: params["dot"]).orEmpty().trim()
        val secret = userKey.ifBlank { publicKey }
        val security = if (mode == "dot" || mode == "doh") SecurityType.TLS else SecurityType.NONE
        val transport = if (protocol == ProxyProtocol.DNSTT) TransportType.DNSTT else TransportType.MASTERDNS
        val remark = decodeFragment(uri.rawFragment).ifBlank {
            "${protocol.label} ${nsDomain.ifBlank { host }}"
        }

        return ServerProfileEntity(
            name = remark,
            protocol = protocol.name,
            address = host,
            port = port,
            uuidOrPassword = secret,
            encryption = mode,
            transport = transport.name,
            security = security.name,
            sni = sni,
            publicKey = publicKey,
            path = dohUrl,
            hostHeader = nsDomain,
            serviceName = mode,
            flow = mtu,
            subscriptionId = subscriptionId,
            groupName = groupName,
            rawUri = raw
        )
    }

    fun parseAwgQuery(encoded: String): AwgProfileParams? {
        if (encoded.isBlank()) return null
        val map = parseQueryParamsCaseInsensitive(encoded)
        if (map.isEmpty()) return null
        val hasAny = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4").any { map.containsKey(it) }
        if (!hasAny) return null
        return AwgProfileParams(
            jc = map["jc"]?.toIntOrNull() ?: 4,
            jmin = map["jmin"]?.toIntOrNull() ?: 40,
            jmax = map["jmax"]?.toIntOrNull() ?: 70,
            s1 = map["s1"]?.toIntOrNull() ?: 0,
            s2 = map["s2"]?.toIntOrNull() ?: 0,
            h1 = map["h1"]?.toLongOrNull() ?: 1L,
            h2 = map["h2"]?.toLongOrNull() ?: 2L,
            h3 = map["h3"]?.toLongOrNull() ?: 3L,
            h4 = map["h4"]?.toLongOrNull() ?: 4L
        )
    }

    private fun parseQueryParamsCaseInsensitive(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else urlDecode(pair.substring(0, idx)).lowercase() to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun containsKnownScheme(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("vmess://") ||
            lower.contains("vless://") ||
            lower.contains("trojan://") ||
            lower.contains("ss://") ||
            lower.contains("socks://") ||
            lower.contains("socks5://") ||
            lower.contains("hysteria2://") ||
            lower.contains("hy2://") ||
            lower.contains("wireguard://") ||
            lower.contains("awg://") ||
            lower.contains("warp://") ||
            lower.contains("masque://") ||
            lower.contains("dnstt://") ||
            lower.contains("masterdns://")
    }

    private fun decodeBase64Safe(input: String): String? {
        val cleaned = input.trim().replace("\n", "").replace("\r", "")
            .replace('-', '+').replace('_', '/')
        if (cleaned.isEmpty()) return null
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return try {
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            val str = String(bytes, StandardCharsets.UTF_8)
            if (str.any { it.code < 9 }) null else str
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun splitHostPort(input: String): Pair<String, Int>? {
        return if (input.startsWith("[")) {
            val closeIdx = input.indexOf(']')
            if (closeIdx <= 1) return null
            val host = input.substring(1, closeIdx)
            val port = input.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: return null
            host to port
        } else {
            val colonIdx = input.lastIndexOf(':')
            if (colonIdx <= 0) return null
            val host = input.substring(0, colonIdx)
            val port = input.substring(colonIdx + 1).toIntOrNull() ?: return null
            host to port
        }
    }

    private fun formatHost(host: String): String =
        if (host.contains(":") && !host.startsWith("[")) "[$host]" else host

    private fun decodeFragment(rawFragment: String?): String =
        if (rawFragment.isNullOrBlank()) "" else urlDecode(rawFragment)

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }
}
