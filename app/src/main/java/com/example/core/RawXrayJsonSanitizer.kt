package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject

data class AwgProfileParams(
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val s1: Int,
    val s2: Int,
    val h1: Int,
    val h2: Int,
    val h3: Int,
    val h4: Int
) {
    fun toQuery(): String =
        "jc=$jc&jmin=$jmin&jmax=$jmax&s1=$s1&s2=$s2&h1=$h1&h2=$h2&h3=$h3&h4=$h4"
}

data class SanitizedImportResult(
    val sanitizedJson: String,
    val extractedNodes: List<ServerProfileEntity>,
    val strippedDirectives: List<String>
)

object RawXrayJsonSanitizer {
    private val DANGEROUS_KEYS = setOf(
        "exec", "command", "cmd", "shell", "script",
        "env", "environment", "external", "hook", "prestart", "poststop"
    )
    private val CERT_FILE_KEYS = setOf("certificatefile", "keyfile")
    private val LOG_PATH_KEYS = setOf("access", "error", "dnslog")
    private val SKIP_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    fun looksLikeJsonConfig(rawInput: String): Boolean {
        val t = rawInput.trim()
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }

    private fun qualify(prefix: String, key: String): String =
        if (prefix.isEmpty()) key else "$prefix.$key"

    private fun sanitizeArrayRecursive(
        source: JSONArray,
        stripped: MutableList<String>,
        parentKey: String,
        pathPrefix: String
    ): JSONArray {
        val out = JSONArray()
        for (i in 0 until source.length()) {
            val itemPath = "$pathPrefix[$i]"
            val v = source.opt(i)
            when (v) {
                is JSONObject -> out.put(sanitizeObjectRecursive(v, stripped, parentKey, itemPath))
                is JSONArray -> out.put(sanitizeArrayRecursive(v, stripped, parentKey, itemPath))
                else -> out.put(v)
            }
        }
        return out
    }

    private fun sanitizeObjectRecursive(
        source: JSONObject,
        stripped: MutableList<String>,
        parentKey: String,
        pathPrefix: String
    ): JSONObject {
        val dest = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lowerKey = key.lowercase()
            val currentPath = qualify(pathPrefix, key)
            val value = source.opt(key)

            if (DANGEROUS_KEYS.contains(lowerKey) || CERT_FILE_KEYS.contains(lowerKey)) {
                stripped += currentPath
                continue
            }
            if (parentKey.equals("log", ignoreCase = true) && LOG_PATH_KEYS.contains(lowerKey)) {
                val strVal = (value as? String)?.trim().orEmpty()
                if (strVal.isNotEmpty() && !strVal.equals("none", ignoreCase = true)) {
                    stripped += currentPath
                }
                continue
            }

            when (value) {
                is JSONObject -> dest.put(key, sanitizeObjectRecursive(value, stripped, key, currentPath))
                is JSONArray -> dest.put(key, sanitizeArrayRecursive(value, stripped, key, currentPath))
                else -> dest.put(key, value)
            }
        }
        return dest
    }

    private fun sanitizeSingleObject(
        source: JSONObject,
        stripped: MutableList<String>,
        pathPrefix: String
    ): JSONObject {
        val clean = sanitizeObjectRecursive(source, stripped, "", pathPrefix)
        if (clean.has("api")) {
            clean.remove("api")
            stripped += qualify(pathPrefix, "api")
        }
        if (clean.has("stats")) {
            clean.remove("stats")
            stripped += qualify(pathPrefix, "stats")
        }

        val inbounds = clean.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                val listen = inbound.optString("listen", "").trim()
                if (listen.isEmpty() || listen == "0.0.0.0" || listen == "::" || listen == "[::]") {
                    inbound.put("listen", "127.0.0.1")
                    stripped += qualify(pathPrefix, "inbounds[$i].listen=$listen->127.0.0.1")
                }
            }
        }
        return clean
    }

    private fun splitHostPortFallback(input: String, defaultPort: Int = 2408): Pair<String, Int>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx <= 1) return null
            val host = trimmed.substring(1, closeIdx)
            val portStr = trimmed.substring(closeIdx + 1).removePrefix(":")
            val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
            return host to port
        }
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx <= 0) return null
        val host = trimmed.substring(0, colonIdx)
        val port = trimmed.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        return host to port
    }

    private fun extractFinalMaskType(finalMaskObj: JSONObject?): String {
        if (finalMaskObj == null) return ""
        val tcpArr = finalMaskObj.optJSONArray("tcp")
        val firstTcp = tcpArr?.optJSONObject(0)
        if (firstTcp != null) {
            val t = firstTcp.optString("type", "")
            if (t.isNotBlank()) return t
        }
        val udpArr = finalMaskObj.optJSONArray("udp")
        val firstUdp = udpArr?.optJSONObject(0)
        if (firstUdp != null) {
            val t = firstUdp.optString("type", "")
            if (t.isNotBlank()) return t
        }
        return finalMaskObj.optString("type", "")
    }

    private fun parseOutbound(
        outbound: JSONObject,
        fallbackRemark: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val protoRaw = outbound.optString("protocol", "").trim().lowercase()
        if (protoRaw.isEmpty() || SKIP_OUTBOUND_PROTOCOLS.contains(protoRaw)) return null

        val tag = outbound.optString("tag", "").trim()
        val lowerTag = tag.lowercase()
        if (lowerTag in setOf("direct", "block", "fragment-out", "noise-out")) return null

        val settings = outbound.optJSONObject("settings") ?: return null
        var address = ""
        var port = 443
        var secret = ""
        var encryption = "none"
        var flow = ""
        val protocol: ProxyProtocol

        when (protoRaw) {
            "vless" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 443)
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("encryption", "none").trim().ifEmpty { "none" }
                flow = user.optString("flow", "")
                protocol = ProxyProtocol.VLESS
            }
            "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 443)
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("security", "auto").trim().ifEmpty { "auto" }
                protocol = ProxyProtocol.VMESS
            }
            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 443)
                secret = server.optString("password", "").trim()
                protocol = ProxyProtocol.TROJAN
            }
            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 443)
                secret = server.optString("password", "").trim()
                encryption = server.optString("method", "aes-256-gcm").trim().ifEmpty { "aes-256-gcm" }
                protocol = ProxyProtocol.SHADOWSOCKS
            }
            "socks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 1080)
                val user = server.optJSONArray("users")?.optJSONObject(0)
                if (user != null) {
                    val u = user.optString("user", "")
                    val p = user.optString("pass", "")
                    secret = if (u.isNotBlank() || p.isNotBlank()) "$u:$p" else ""
                }
                protocol = ProxyProtocol.SOCKS
            }
            "hysteria", "hysteria2" -> {
                address = settings.optString("address", "").trim()
                port = settings.optInt("port", 443)
                secret = settings.optString("auth", "").trim()
                protocol = ProxyProtocol.HYSTERIA2
            }
            "wireguard", "awg" -> {
                val peerObj = settings.optJSONArray("peers")?.optJSONObject(0)
                val endpoint = peerObj?.optString("endpoint", "")?.trim().orEmpty()
                val epPair = splitHostPortFallback(endpoint, 2408)
                address = epPair?.first.orEmpty()
                port = epPair?.second ?: 2408
                secret = settings.optString("secretKey", "").trim()
                if (address.isEmpty() || secret.isEmpty()) return null
                val pubKey = peerObj?.optString("publicKey", "")?.trim().orEmpty()
                val reservedArr = settings.optJSONArray("reserved")
                val reserved = if (reservedArr != null && reservedArr.length() > 0) {
                    (0 until reservedArr.length()).joinToString(",") {
                        reservedArr.optInt(it, 0).toString()
                    }
                } else {
                    "0,0,0"
                }
                val addrArr = settings.optJSONArray("address")
                val firstAddr = addrArr?.optString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "172.16.0.2/32"
                val mtuVal = settings.optInt("mtu", 0)
                val mtu = if (mtuVal > 0) mtuVal.toString() else ""
                val hasAwg = settings.has("jc") || settings.has("jmin") || settings.has("s1") || settings.has("h1")
                val awgQuery = if (hasAwg) {
                    AwgProfileParams(
                        jc = settings.optInt("jc", 4),
                        jmin = settings.optInt("jmin", 40),
                        jmax = settings.optInt("jmax", 70),
                        s1 = settings.optInt("s1", 0),
                        s2 = settings.optInt("s2", 0),
                        h1 = settings.optInt("h1", 1),
                        h2 = settings.optInt("h2", 2),
                        h3 = settings.optInt("h3", 3),
                        h4 = settings.optInt("h4", 4)
                    ).toQuery()
                } else {
                    ""
                }
                val nodeName = fallbackRemark.ifEmpty {
                    if (tag.isNotEmpty() && lowerTag != "proxy") tag else "$address:$port"
                }
                return ServerProfileEntity(
                    name = nodeName,
                    protocol = ProxyProtocol.WIREGUARD.name,
                    address = address,
                    port = port,
                    uuidOrPassword = secret,
                    encryption = settings.optString("shaping", "quic").trim().ifEmpty { "quic" },
                    transport = TransportType.WG.name,
                    security = SecurityType.NONE.name,
                    publicKey = pubKey,
                    shortId = reserved,
                    path = firstAddr,
                    serviceName = awgQuery,
                    flow = mtu,
                    finalMask = if (hasAwg) "awg" else "",
                    subscriptionId = subscriptionId,
                    groupName = groupName
                )
            }
            "masque" -> {
                val endpoint = settings.optString("endpoint", "").trim()
                val epPair = splitHostPortFallback(endpoint, 443)
                address = epPair?.first ?: settings.optString("address", "").trim()
                port = epPair?.second ?: settings.optInt("port", 443)
                secret = settings.optString("privateKey", settings.optString("auth", "")).trim()
                if (address.isEmpty() || secret.isEmpty()) return null
                val stream = outbound.optJSONObject("streamSettings")
                val tls = stream?.optJSONObject("tlsSettings")
                val addrArr = settings.optJSONArray("address")
                val localAddr = addrArr?.optString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "172.16.0.2/32"
                val mtuVal = settings.optInt("mtu", 0)
                val nodeName = fallbackRemark.ifEmpty {
                    if (tag.isNotEmpty() && lowerTag != "proxy") tag else "$address:$port"
                }
                return ServerProfileEntity(
                    name = nodeName,
                    protocol = ProxyProtocol.MASQUE.name,
                    address = address,
                    port = port,
                    uuidOrPassword = secret,
                    encryption = settings.optString("shaping", "quic").trim().ifEmpty { "quic" },
                    transport = TransportType.MASQUE.name,
                    security = SecurityType.TLS.name,
                    sni = tls?.optString("serverName", "").orEmpty(),
                    publicKey = settings.optString("publicKey", "").trim(),
                    path = localAddr,
                    flow = if (mtuVal > 0) mtuVal.toString() else "",
                    subscriptionId = subscriptionId,
                    groupName = groupName
                )
            }
            else -> return null
        }

        if (address.isEmpty() || port !in 1..65535) return null
        if (protocol != ProxyProtocol.SOCKS && secret.isEmpty()) return null

        val stream = outbound.optJSONObject("streamSettings")
        val transport = TransportType.parse(stream?.optString("network", "tcp"))
        val security = SecurityType.parse(
            stream?.optString("security", if (protocol == ProxyProtocol.HYSTERIA2) "tls" else "none")
        )
        val tlsSettings = stream?.optJSONObject("tlsSettings")
        val realitySettings = stream?.optJSONObject("realitySettings")
        val wsSettings = stream?.optJSONObject("wsSettings")
        val grpcSettings = stream?.optJSONObject("grpcSettings")
        val xhttpSettings = stream?.optJSONObject("xhttpSettings")
        val httpUpgradeSettings = stream?.optJSONObject("httpupgradeSettings")

        val sni = tlsSettings?.optString("serverName", "").orEmpty()
            .ifEmpty { realitySettings?.optString("serverName", "").orEmpty() }
        val alpnArr = tlsSettings?.optJSONArray("alpn")
        val alpn = if (alpnArr != null) {
            (0 until alpnArr.length())
                .mapNotNull { alpnArr.optString(it).takeIf { s -> s.isNotBlank() } }
                .joinToString(",")
        } else {
            ""
        }
        val fp = tlsSettings?.optString("fingerprint", "").orEmpty()
            .ifEmpty { realitySettings?.optString("fingerprint", "").orEmpty() }
            .ifEmpty { "chrome" }
        val allowInsecure = tlsSettings?.optBoolean("allowInsecure", false) ?: false
        val cipherSuites = tlsSettings?.optString("cipherSuites", "").orEmpty()
        val echConfigList = tlsSettings?.optString("echConfigList", "").orEmpty()
        val echForceQuery = tlsSettings?.optString("echForceQuery", "").orEmpty()
            .ifEmpty { if (echConfigList.isNotBlank()) "half" else "none" }
        val pbk = realitySettings?.optString("publicKey", "").orEmpty()
        val sid = realitySettings?.optString("shortId", "").orEmpty()
        val pqv = realitySettings?.optString("mldsa65Verify", "").orEmpty()

        val path = when (transport) {
            TransportType.WS -> wsSettings?.optString("path", "").orEmpty()
            TransportType.XHTTP -> xhttpSettings?.optString("path", "").orEmpty()
            TransportType.HTTPUPGRADE -> httpUpgradeSettings?.optString("path", "").orEmpty()
            else -> ""
        }
        val wsHeaders = wsSettings?.optJSONObject("headers")
        val hostHeader = when (transport) {
            TransportType.WS -> wsSettings?.optString("host", "").orEmpty()
                .ifEmpty { wsHeaders?.optString("Host", "").orEmpty() }
            TransportType.XHTTP -> xhttpSettings?.optString("host", "").orEmpty()
            TransportType.HTTPUPGRADE -> httpUpgradeSettings?.optString("host", "").orEmpty()
            else -> ""
        }
        val serviceName = grpcSettings?.optString("serviceName", "").orEmpty()
        val finalMask = extractFinalMaskType(stream?.optJSONObject("finalmask"))
        val nodeName = fallbackRemark.ifEmpty {
            if (tag.isNotEmpty() && lowerTag != "proxy") tag else "$address:$port"
        }

        return ServerProfileEntity(
            name = nodeName,
            protocol = protocol.name,
            address = address,
            port = port,
            uuidOrPassword = secret,
            encryption = encryption,
            transport = transport.name,
            security = security.name,
            sni = sni,
            alpn = alpn,
            fingerprint = fp,
            publicKey = pbk,
            shortId = sid,
            path = path,
            hostHeader = hostHeader,
            serviceName = serviceName,
            flow = flow,
            allowInsecure = allowInsecure,
            cipherSuites = cipherSuites,
            finalMask = finalMask,
            echConfigList = echConfigList,
            echForceQuery = echForceQuery,
            mldsa65Verify = pqv,
            subscriptionId = subscriptionId,
            groupName = groupName
        )
    }

    private fun extractNodesFromConfig(
        config: JSONObject,
        subscriptionId: String?,
        groupName: String
    ): List<ServerProfileEntity> {
        val configRemarks = config.optString("remarks", "").trim()
        val outbounds = config.optJSONArray("outbounds")
        val nodes = mutableListOf<ServerProfileEntity>()
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                parseOutbound(ob, configRemarks, subscriptionId, groupName)?.let { nodes += it }
            }
        } else if (config.has("protocol") && config.has("settings")) {
            parseOutbound(config, configRemarks, subscriptionId, groupName)?.let { nodes += it }
        }
        return nodes
    }

    fun sanitizeRawXrayJson(
        rawJson: String,
        subscriptionId: String? = null,
        groupName: String = "Imported"
    ): SanitizedImportResult {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return SanitizedImportResult("{}", emptyList(), emptyList())
        }
        val stripped = mutableListOf<String>()
        val extracted = mutableListOf<ServerProfileEntity>()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val cleanArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val cleanObj = sanitizeSingleObject(item, stripped, "[$i]")
                    cleanArr.put(cleanObj)
                    extracted += extractNodesFromConfig(cleanObj, subscriptionId, groupName)
                }
                SanitizedImportResult(
                    sanitizedJson = cleanArr.toString(2),
                    extractedNodes = extracted,
                    strippedDirectives = stripped.distinct()
                )
            } else {
                val cleanObj = sanitizeSingleObject(JSONObject(trimmed), stripped, "")
                extracted += extractNodesFromConfig(cleanObj, subscriptionId, groupName)
                SanitizedImportResult(
                    sanitizedJson = cleanObj.toString(2),
                    extractedNodes = extracted,
                    strippedDirectives = stripped.distinct()
                )
            }
        } catch (_: Exception) {
            SanitizedImportResult("{}", emptyList(), emptyList())
        }
    }
}
