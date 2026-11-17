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
    val strippedDirectives: List<String>,
    val failures: List<String> = emptyList()
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


    private fun parseOutbound(
        outbound: JSONObject,
        fallbackRemark: String,
        subscriptionId: String?,
        groupName: String,
        endpoint: JSONObject? = null,
        credential: JSONObject? = null
    ): ServerProfileEntity? {
        val protoRaw = outbound.optString("protocol", "").trim().lowercase()
        if (protoRaw.isEmpty() || SKIP_OUTBOUND_PROTOCOLS.contains(protoRaw)) return null

        val tag = outbound.optString("tag", "").trim()
        val lowerTag = tag.lowercase()
        if (outbound.has("proxySettings") || outbound.has("dialerProxy")) return null

        val settings = outbound.optJSONObject("settings") ?: return null
        var address = ""
        var port = 443
        var secret = ""
        var encryption = "none"
        var flow = ""
        val protocol: ProxyProtocol

        when (protoRaw) {
            "vless" -> {
                val vnext = endpoint ?: settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 0)
                val user = credential ?: vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("encryption", "none").trim().ifEmpty { "none" }
                flow = user.optString("flow", "")
                protocol = ProxyProtocol.VLESS
            }
            "vmess" -> {
                val vnext = endpoint ?: settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                address = vnext.optString("address", "").trim()
                port = vnext.optInt("port", 0)
                val user = credential ?: vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
                if (user.optInt("alterId", 0) != 0) return null
                secret = user.optString("id", "").trim()
                encryption = user.optString("security", "auto").trim().ifEmpty { "auto" }
                protocol = ProxyProtocol.VMESS
            }
            "trojan" -> {
                val server = endpoint ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 0)
                secret = server.optString("password", "")
                protocol = ProxyProtocol.TROJAN
            }
            "shadowsocks" -> {
                val server = endpoint ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 0)
                secret = server.optString("password", "")
                encryption = server.optString("method", "aes-256-gcm").trim().ifEmpty { "aes-256-gcm" }
                protocol = ProxyProtocol.SHADOWSOCKS
            }
            "socks" -> {
                val server = endpoint ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = server.optString("address", "").trim()
                port = server.optInt("port", 0)
                val user = credential ?: server.optJSONArray("users")?.optJSONObject(0)
                if (user != null) {
                    val u = user.optString("user", "")
                    if (u.contains(':')) return null
                    val p = user.optString("pass", "")
                    secret = if (u.isNotBlank() || p.isNotBlank()) "$u:$p" else ""
                }
                protocol = ProxyProtocol.SOCKS
            }
            else -> return null
        }

        if (address.isEmpty() || port !in 1..65535) return null
        if (protocol != ProxyProtocol.SOCKS && secret.isEmpty()) return null

        val stream = outbound.optJSONObject("streamSettings")
        val transport = TransportType.parse(stream?.optString("network", "tcp"))
        if (stream?.optString("network", "tcp")?.lowercase() !in
            setOf(null, "tcp", "raw", "ws", "websocket", "grpc", "httpupgrade", "xhttp", "splithttp")) return null
        if (stream?.optString("security", "none")?.lowercase() !in setOf(null, "none", "tls", "reality")) return null
        if (stream?.has("finalmask") == true || stream?.has("finalMask") == true ||
            stream?.optJSONObject("sockopt")?.has("dialerProxy") == true) return null
        val security = SecurityType.parse(stream?.optString("security", "none"))
        val tlsSettings = stream?.optJSONObject("tlsSettings")
        val realitySettings = stream?.optJSONObject("realitySettings")
        val wsSettings = stream?.optJSONObject("wsSettings")
        val grpcSettings = stream?.optJSONObject("grpcSettings")
        val xhttpSettings = stream?.optJSONObject("xhttpSettings")
        val httpUpgradeSettings = stream?.optJSONObject("httpupgradeSettings")
        if (stream?.optJSONObject("tcpSettings")?.optJSONObject("header")?.optString("type", "none")
                ?.let { it != "none" } == true) return null
        if (grpcSettings?.optBoolean("multiMode", false) == true || xhttpSettings?.has("extra") == true) return null
        if (xhttpSettings?.optString("mode", "auto")?.let { it != "auto" } == true) return null
        if (tlsSettings?.has("certificates") == true || tlsSettings?.has("pinnedPeerCertificateChainSha256") == true) return null
        if (tlsSettings?.optBoolean("disableSystemRoot", false) == true) return null

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
            .ifEmpty { realitySettings?.optString("password", "").orEmpty() }
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
            TransportType.GRPC -> grpcSettings?.optString("authority", "").orEmpty()
            else -> ""
        }
        val serviceName = grpcSettings?.optString("serviceName", "").orEmpty()
        val finalMask = ""
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
        ).takeIf(ClientPolicy::isSupported)
    }

    private fun extractOutboundNodes(
        outbound: JSONObject,
        remark: String,
        subscriptionId: String?,
        groupName: String,
        failures: MutableList<String>
    ): List<ServerProfileEntity> {
        val protocol = outbound.optString("protocol", "").lowercase()
        if (protocol in SKIP_OUTBOUND_PROTOCOLS) return emptyList()
        val settings = outbound.optJSONObject("settings")
        val endpoints = settings?.optJSONArray(if (protocol in setOf("vless", "vmess")) "vnext" else "servers")
        val nodes = mutableListOf<ServerProfileEntity>()
        fun add(endpoint: JSONObject?, credential: JSONObject?) {
            val node = parseOutbound(outbound, remark, subscriptionId, groupName, endpoint, credential)
            if (node == null) failures += "Malformed or unsupported stable Xray outbound"
            else nodes += node
        }
        if (endpoints == null || endpoints.length() == 0) {
            add(null, null)
        } else for (i in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(i)
            if (endpoint == null) {
                failures += "Malformed Xray server entry"
                continue
            }
            val credentials = if (protocol in setOf("vless", "vmess", "socks")) endpoint.optJSONArray("users") else null
            if (credentials == null || credentials.length() == 0) add(endpoint, null)
            else for (j in 0 until credentials.length()) {
                val credential = credentials.optJSONObject(j)
                if (credential == null) failures += "Malformed Xray user entry"
                else add(endpoint, credential)
            }
        }
        return nodes
    }

    private fun extractNodesFromConfig(
        config: JSONObject,
        subscriptionId: String?,
        groupName: String,
        failures: MutableList<String>
    ): List<ServerProfileEntity> {
        val configRemarks = config.optString("remarks", "").trim()
        val outbounds = config.optJSONArray("outbounds")
        val nodes = mutableListOf<ServerProfileEntity>()
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i)
                if (ob == null) {
                    failures += "Outbound ${i + 1}: expected an Xray outbound object"
                    continue
                }
                nodes += extractOutboundNodes(ob, configRemarks, subscriptionId, groupName, failures)
            }
        } else if (config.has("protocol") && config.has("settings")) {
            nodes += extractOutboundNodes(config, configRemarks, subscriptionId, groupName, failures)
        }
        else {
            failures += "Expected an Xray configuration with outbounds; Clash and sing-box are not supported"
        }
        return nodes
    }

    fun sanitizeRawXrayJson(
        rawJson: String,
        subscriptionId: String? = null,
        groupName: String = "Imported"
    ): SanitizedImportResult {
        if (!ImportInput.isWithinLimit(rawJson)) {
            return SanitizedImportResult("{}", emptyList(), emptyList(), listOf(ImportInput.TOO_LARGE))
        }
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return SanitizedImportResult("{}", emptyList(), emptyList())
        }
        val stripped = mutableListOf<String>()
        val extracted = mutableListOf<ServerProfileEntity>()
        val failures = mutableListOf<String>()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val cleanArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item == null) {
                        failures += "Config ${i + 1}: expected an Xray object"
                        continue
                    }
                    val cleanObj = sanitizeSingleObject(item, stripped, "[$i]")
                    cleanArr.put(cleanObj)
                    extracted += extractNodesFromConfig(cleanObj, subscriptionId, groupName, failures)
                }
                SanitizedImportResult(
                    sanitizedJson = cleanArr.toString(2),
                    extractedNodes = extracted,
                    strippedDirectives = stripped.distinct(),
                    failures = failures
                )
            } else {
                val cleanObj = sanitizeSingleObject(JSONObject(trimmed), stripped, "")
                extracted += extractNodesFromConfig(cleanObj, subscriptionId, groupName, failures)
                SanitizedImportResult(
                    sanitizedJson = cleanObj.toString(2),
                    extractedNodes = extracted,
                    strippedDirectives = stripped.distinct(),
                    failures = failures
                )
            }
        } catch (_: Exception) {
            SanitizedImportResult("{}", emptyList(), emptyList(), listOf("Invalid Xray JSON"))
        }
    }
}
