package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject

data class SanitizedImportResult(
    val sanitizedJson: String,
    val extractedNodes: List<ServerProfileEntity>,
    val strippedDirectives: List<String>
)

/**
 * Hardened importer for raw Xray-core JSON profiles and JSON subscription arrays.
 * Strips host filesystem log paths, script/command execution hooks, TLS cert/key file pointers,
 * API/stats exposure, and rewrites wildcard 0.0.0.0/:: inbound listeners to 127.0.0.1
 * before extracting safe proxy outbounds.
 */
object RawXrayJsonSanitizer {

    private val DANGEROUS_KEYS = setOf(
        "exec",
        "command",
        "cmd",
        "shell",
        "script",
        "env",
        "environment",
        "external",
        "hook",
        "prestart",
        "poststop"
    )

    private val CERT_FILE_KEYS = setOf(
        "certificatefile",
        "keyfile"
    )

    private val LOG_PATH_KEYS = setOf(
        "access",
        "error",
        "dnslog"
    )

    private val SKIP_OUTBOUND_PROTOCOLS = setOf(
        "freedom",
        "blackhole",
        "dns",
        "loopback"
    )

    fun looksLikeJsonConfig(rawInput: String): Boolean {
        val t = rawInput.trim()
        return (t.startsWith("{") && t.endsWith("}")) ||
            (t.startsWith("[") && t.endsWith("]"))
    }

    fun sanitize(
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
                    val obj = arr.optJSONObject(i) ?: continue
                    val cleanObj = sanitizeSingleObject(obj, stripped, pathPrefix = "[$i]")
                    cleanArr.put(cleanObj)
                    extracted.addAll(extractNodesFromConfig(cleanObj, subscriptionId, groupName))
                }
                SanitizedImportResult(
                    sanitizedJson = cleanArr.toString(2),
                    extractedNodes = extracted,
                    strippedDirectives = stripped.distinct()
                )
            } else {
                val obj = JSONObject(trimmed)
                val cleanObj = sanitizeSingleObject(obj, stripped, pathPrefix = "")
                extracted.addAll(extractNodesFromConfig(cleanObj, subscriptionId, groupName))
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

    private fun sanitizeSingleObject(
        source: JSONObject,
        stripped: MutableList<String>,
        pathPrefix: String
    ): JSONObject {
        val clean = sanitizeObjectRecursive(source, stripped, parentKey = "", pathPrefix = pathPrefix)

        // Remove top-level api & stats blocks so imported configs cannot open control ports
        if (clean.has("api")) {
            clean.remove("api")
            stripped.add(qualify(pathPrefix, "api"))
        }
        if (clean.has("stats")) {
            clean.remove("stats")
            stripped.add(qualify(pathPrefix, "stats"))
        }

        // Harden inbound listeners
        val inbounds = clean.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                val listen = inbound.optString("listen", "").trim()
                if (listen.isEmpty() || listen == "0.0.0.0" || listen == "::" || listen == "[::]") {
                    inbound.put("listen", "127.0.0.1")
                    stripped.add(qualify(pathPrefix, "inbounds[$i].listen=$listen->127.0.0.1"))
                }
            }
        }

        return clean
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

            if (lowerKey in DANGEROUS_KEYS || lowerKey in CERT_FILE_KEYS) {
                stripped.add(currentPath)
                continue
            }
            if (parentKey.equals("log", ignoreCase = true) && lowerKey in LOG_PATH_KEYS) {
                val value = source.optString(key, "").trim()
                if (value.isNotEmpty() && !value.equals("none", ignoreCase = true)) {
                    stripped.add(currentPath)
                }
                continue
            }

            when (val value = source.opt(key)) {
                is JSONObject -> {
                    dest.put(key, sanitizeObjectRecursive(value, stripped, parentKey = key, pathPrefix = currentPath))
                }
                is JSONArray -> {
                    dest.put(key, sanitizeArrayRecursive(value, stripped, parentKey = key, pathPrefix = currentPath))
                }
                else -> dest.put(key, value)
            }
        }
        return dest
    }

    private fun sanitizeArrayRecursive(
        source: JSONArray,
        stripped: MutableList<String>,
        parentKey: String,
        pathPrefix: String
    ): JSONArray {
        val dest = JSONArray()
        for (i in 0 until source.length()) {
            val itemPath = "$pathPrefix[$i]"
            when (val value = source.opt(i)) {
                is JSONObject -> dest.put(
                    sanitizeObjectRecursive(value, stripped, parentKey = parentKey, pathPrefix = itemPath)
                )
                is JSONArray -> dest.put(
                    sanitizeArrayRecursive(value, stripped, parentKey = parentKey, pathPrefix = itemPath)
                )
                else -> dest.put(value)
            }
        }
        return dest
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
                val outbound = outbounds.optJSONObject(i) ?: continue
                parseOutbound(outbound, configRemarks, subscriptionId, groupName)?.let { nodes.add(it) }
            }
        } else if (config.has("protocol") && config.has("settings")) {
            parseOutbound(config, configRemarks, subscriptionId, groupName)?.let { nodes.add(it) }
        }

        return nodes
    }

    private fun parseOutbound(
        outbound: JSONObject,
        fallbackRemark: String,
        subscriptionId: String?,
        groupName: String
    ): ServerProfileEntity? {
        val protoRaw = outbound.optString("protocol", "").trim().lowercase()
        if (protoRaw.isEmpty() || protoRaw in SKIP_OUTBOUND_PROTOCOLS) return null

        val tag = outbound.optString("tag", "").trim()
        if (tag.equals("direct", ignoreCase = true) ||
            tag.equals("block", ignoreCase = true) ||
            tag.equals("fragment-out", ignoreCase = true) ||
            tag.equals("noise-out", ignoreCase = true)
        ) {
            return null
        }

        val settings = outbound.optJSONObject("settings") ?: return null
        var address = ""
        var port = 443
        var secret = ""
        var encryption = "none"
        var flow = ""

        val protocol = when (protoRaw) {
            "vless" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 443)
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("encryption", "none").ifBlank { "none" }
                flow = user.optString("flow", "")
                ProxyProtocol.VLESS
            }
            "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 443)
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("security", "auto").ifBlank { "auto" }
                ProxyProtocol.VMESS
            }
            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 443)
                secret = server.optString("password", "").trim()
                ProxyProtocol.TROJAN
            }
            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 443)
                secret = server.optString("password", "").trim()
                encryption = server.optString("method", "aes-256-gcm").ifBlank { "aes-256-gcm" }
                ProxyProtocol.SHADOWSOCKS
            }
            "socks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 1080)
                val user = server.optJSONArray("users")?.optJSONObject(0)
                secret = if (user != null) {
                    val u = user.optString("user", "")
                    val p = user.optString("pass", "")
                    if (u.isNotBlank() || p.isNotBlank()) "$u:$p" else ""
                } else ""
                ProxyProtocol.SOCKS
            }
            "hysteria", "hysteria2" -> {
                address = settings.optString("address", "").trim()
                port = settings.optInt("port", 443)
                secret = settings.optString("auth", "").trim()
                ProxyProtocol.HYSTERIA2
            }
            "wireguard", "awg" -> {
                val peer = settings.optJSONArray("peers")?.optJSONObject(0)
                val endpoint = peer?.optString("endpoint", "").orEmpty().trim()
                val epHostPort = splitHostPort(endpoint)
                address = epHostPort?.first.orEmpty()
                port = epHostPort?.second ?: 2408
                secret = settings.optString("secretKey", "").trim()
                if (address.isBlank() || secret.isBlank()) return null
                val pubKey = peer?.optString("publicKey", "").orEmpty().trim()
                val reservedArr = settings.optJSONArray("reserved")
                val reserved = if (reservedArr != null && reservedArr.length() > 0) {
                    (0 until reservedArr.length()).map { reservedArr.optInt(it, 0) }.joinToString(",")
                } else "0,0,0"
                val addrArr = settings.optJSONArray("address")
                val localAddr = addrArr?.optString(0)?.takeIf { it.isNotBlank() } ?: "172.16.0.2/32"
                val mtu = settings.optInt("mtu", 0).takeIf { it > 0 }?.toString() ?: ""
                val hasAwg = settings.has("jc") || settings.has("jmin") || settings.has("s1") || settings.has("h1")
                val awgQuery = if (hasAwg) {
                    AwgProfileParams(
                        jc = settings.optInt("jc", 4),
                        jmin = settings.optInt("jmin", 40),
                        jmax = settings.optInt("jmax", 70),
                        s1 = settings.optInt("s1", 0),
                        s2 = settings.optInt("s2", 0),
                        h1 = settings.optLong("h1", 1L),
                        h2 = settings.optLong("h2", 2L),
                        h3 = settings.optLong("h3", 3L),
                        h4 = settings.optLong("h4", 4L)
                    ).encodeQuery()
                } else ""
                val nodeName = when {
                    fallbackRemark.isNotBlank() -> fallbackRemark
                    tag.isNotBlank() && !tag.equals("proxy", ignoreCase = true) -> tag
                    else -> "$address:$port"
                }
                return ServerProfileEntity(
                    name = nodeName,
                    protocol = ProxyProtocol.WIREGUARD.name,
                    address = address,
                    port = port,
                    uuidOrPassword = secret,
                    encryption = settings.optString("shaping", "quic").ifBlank { "quic" },
                    transport = TransportType.WG.name,
                    security = SecurityType.NONE.name,
                    publicKey = pubKey,
                    shortId = reserved,
                    path = localAddr,
                    serviceName = awgQuery,
                    flow = mtu,
                    finalMask = if (hasAwg) "awg" else "",
                    subscriptionId = subscriptionId,
                    groupName = groupName,
                    rawUri = ""
                )
            }
            "masque" -> {
                val endpoint = settings.optString("endpoint", "").trim()
                val epHostPort = splitHostPort(endpoint)
                address = epHostPort?.first ?: settings.optString("address", "").trim()
                port = epHostPort?.second ?: settings.optInt("port", 443)
                secret = settings.optString("privateKey", settings.optString("auth", "")).trim()
                if (address.isBlank() || secret.isBlank()) return null
                val stream = outbound.optJSONObject("streamSettings")
                val tls = stream?.optJSONObject("tlsSettings")
                val nodeName = when {
                    fallbackRemark.isNotBlank() -> fallbackRemark
                    tag.isNotBlank() && !tag.equals("proxy", ignoreCase = true) -> tag
                    else -> "$address:$port"
                }
                return ServerProfileEntity(
                    name = nodeName,
                    protocol = ProxyProtocol.MASQUE.name,
                    address = address,
                    port = port,
                    uuidOrPassword = secret,
                    encryption = settings.optString("shaping", "quic").ifBlank { "quic" },
                    transport = TransportType.MASQUE.name,
                    security = SecurityType.TLS.name,
                    sni = tls?.optString("serverName", "").orEmpty(),
                    publicKey = settings.optString("publicKey", "").trim(),
                    path = settings.optJSONArray("address")?.optString(0).orEmpty().ifBlank { "172.16.0.2/32" },
                    flow = settings.optInt("mtu", 0).takeIf { it > 0 }?.toString() ?: "",
                    subscriptionId = subscriptionId,
                    groupName = groupName,
                    rawUri = ""
                )
            }
            "dnstt", "masterdns" -> {
                val resolver = settings.optString("resolver", "").trim()
                val resPair = splitHostPort(resolver)
                address = resPair?.first ?: settings.optString("address", "").trim()
                val dnsMode = settings.optString("transport", "udp").lowercase().ifBlank { "udp" }
                val defPort = when (dnsMode) {
                    "doh" -> 443
                    "dot" -> 853
                    else -> 53
                }
                port = resPair?.second ?: settings.optInt("port", defPort)
                val pubKey = settings.optString("publicKey", settings.optString("pubkey", "")).trim()
                val auth = settings.optString("auth", pubKey).trim()
                val domain = settings.optString("domain", settings.optString("nameserver", "")).trim()
                if (address.isBlank() || (pubKey.isBlank() && domain.isBlank())) return null
                val stream = outbound.optJSONObject("streamSettings")
                val tls = stream?.optJSONObject("tlsSettings")
                val mappedProto = if (protoRaw == "dnstt") ProxyProtocol.DNSTT else ProxyProtocol.MASTERDNS
                val mappedTransport = if (protoRaw == "dnstt") TransportType.DNSTT else TransportType.MASTERDNS
                val nodeName = when {
                    fallbackRemark.isNotBlank() -> fallbackRemark
                    tag.isNotBlank() && !tag.equals("proxy", ignoreCase = true) -> tag
                    else -> "${mappedProto.label} ${domain.ifBlank { address }}"
                }
                return ServerProfileEntity(
                    name = nodeName,
                    protocol = mappedProto.name,
                    address = address,
                    port = port,
                    uuidOrPassword = auth.ifBlank { pubKey },
                    encryption = dnsMode,
                    transport = mappedTransport.name,
                    security = if (dnsMode == "dot" || dnsMode == "doh") SecurityType.TLS.name else SecurityType.NONE.name,
                    sni = tls?.optString("serverName", "").orEmpty(),
                    publicKey = pubKey,
                    path = settings.optString("dohUrl", "").trim(),
                    hostHeader = domain,
                    serviceName = dnsMode,
                    flow = settings.optInt("mtu", 0).takeIf { it > 0 }?.toString() ?: "",
                    subscriptionId = subscriptionId,
                    groupName = groupName,
                    rawUri = ""
                )
            }
            else -> return null
        }

        if (address.isBlank() || port !in 1..65535) return null
        if (protocol != ProxyProtocol.SOCKS && secret.isBlank()) return null

        val stream = outbound.optJSONObject("streamSettings")
        val transport = TransportType.fromString(stream?.optString("network", "tcp"))
        val security = SecurityType.fromString(stream?.optString("security", if (protocol == ProxyProtocol.HYSTERIA2) "tls" else "none"))

        val tlsSettings = stream?.optJSONObject("tlsSettings")
        val realitySettings = stream?.optJSONObject("realitySettings")
        val wsSettings = stream?.optJSONObject("wsSettings")
        val grpcSettings = stream?.optJSONObject("grpcSettings")
        val xhttpSettings = stream?.optJSONObject("xhttpSettings")
        val httpUpgradeSettings = stream?.optJSONObject("httpupgradeSettings")

        val sni = tlsSettings?.optString("serverName", "").orEmpty()
            .ifBlank { realitySettings?.optString("serverName", "").orEmpty() }
        val alpn = tlsSettings?.optJSONArray("alpn")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }.joinToString(",")
        }.orEmpty()
        val fp = tlsSettings?.optString("fingerprint", "").orEmpty()
            .ifBlank { realitySettings?.optString("fingerprint", "chrome").orEmpty() }
            .ifBlank { "chrome" }
        val allowInsecure = tlsSettings?.optBoolean("allowInsecure", false) ?: false
        val cipherSuites = tlsSettings?.optString("cipherSuites", "").orEmpty()
        val echConfigList = tlsSettings?.optString("echConfigList", "").orEmpty()
        val echForceQuery = tlsSettings?.optString("echForceQuery", "").orEmpty()
            .ifBlank { if (echConfigList.isNotBlank()) "half" else "none" }
        val pbk = realitySettings?.optString("publicKey", "").orEmpty()
        val sid = realitySettings?.optString("shortId", "").orEmpty()
        val pqv = realitySettings?.optString("mldsa65Verify", "").orEmpty()

        val path = when (transport) {
            TransportType.WS -> wsSettings?.optString("path", "").orEmpty()
            TransportType.XHTTP -> xhttpSettings?.optString("path", "").orEmpty()
            TransportType.HTTPUPGRADE -> httpUpgradeSettings?.optString("path", "").orEmpty()
            else -> ""
        }
        val hostHeader = when (transport) {
            TransportType.WS -> wsSettings?.optString("host", "").orEmpty()
                .ifBlank { wsSettings?.optJSONObject("headers")?.optString("Host", "").orEmpty() }
            TransportType.XHTTP -> xhttpSettings?.optString("host", "").orEmpty()
            TransportType.HTTPUPGRADE -> httpUpgradeSettings?.optString("host", "").orEmpty()
            else -> ""
        }
        val serviceName = grpcSettings?.optString("serviceName", "").orEmpty()

        val finalMask = extractFinalMaskType(stream?.optJSONObject("finalmask"))

        val nodeName = when {
            fallbackRemark.isNotBlank() -> fallbackRemark
            tag.isNotBlank() && !tag.equals("proxy", ignoreCase = true) -> tag
            else -> "$address:$port"
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
            groupName = groupName,
            rawUri = ""
        )
    }

    private fun splitHostPort(input: String): Pair<String, Int>? {
        if (input.isBlank()) return null
        return if (input.startsWith("[")) {
            val closeIdx = input.indexOf(']')
            if (closeIdx <= 1) null
            else {
                val host = input.substring(1, closeIdx)
                val port = input.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: 2408
                host to port
            }
        } else {
            val colonIdx = input.lastIndexOf(':')
            if (colonIdx <= 0) null
            else {
                val host = input.substring(0, colonIdx)
                val port = input.substring(colonIdx + 1).toIntOrNull() ?: 2408
                host to port
            }
        }
    }

    private fun extractFinalMaskType(finalmaskObj: JSONObject?): String {
        if (finalmaskObj == null) return ""
        val tcpMask = finalmaskObj.optJSONArray("tcp")?.optJSONObject(0)?.optString("type", "").orEmpty()
        if (tcpMask.isNotBlank()) return tcpMask
        val udpMask = finalmaskObj.optJSONArray("udp")?.optJSONObject(0)?.optString("type", "").orEmpty()
        if (udpMask.isNotBlank()) return udpMask
        return finalmaskObj.optString("type", "")
    }

    private fun qualify(prefix: String, key: String): String =
        if (prefix.isEmpty()) key else "$prefix.$key"
}
