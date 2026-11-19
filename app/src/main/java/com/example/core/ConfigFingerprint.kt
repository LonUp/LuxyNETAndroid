package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.SourceType
import com.example.data.model.TransportType
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Computes a canonical SHA-256 fingerprint over connection-relevant parameters only.
 * Display names, group names, raw URIs, latency, country metadata, and enablement flags
 * are excluded so genuine duplicates are detected accurately without merging configs that
 * differ in TLS, transport, authentication, or routing-relevant parameters.
 */
object ConfigFingerprint {

    fun compute(profile: ServerProfileEntity): String {
        val protocol = ProxyProtocol.parse(profile.protocol)
        val transport = TransportType.parse(profile.transport)
        val security = SecurityType.parse(profile.security)
        val normalizedAddress = normalizeHost(profile.address)
        val normalizedEncryption = normalizeEncryption(protocol, profile.encryption)
        val normalizedAlpn = profile.alpn
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        val normalizedFp = profile.fingerprint.trim().lowercase(Locale.ROOT).ifEmpty { "chrome" }
        val normalizedEchForce = profile.echForceQuery.trim().lowercase(Locale.ROOT).ifEmpty { "none" }
        val decodedFeatures = NodeFeatureOverrides.decode(profile.featureOverrides)
        val canonicalFeatures = decodedFeatures.copy(
            packetEncoding = decodedFeatures.packetEncoding.trim().lowercase(Locale.ROOT),
            xhttpMode = decodedFeatures.xhttpMode.trim().lowercase(Locale.ROOT).let {
                if (it == "auto") "" else it
            }
        ).encode()

        val canonical = JSONObject().apply {
            put("protocol", protocol.name)
            put("address", normalizedAddress)
            put("port", profile.port)
            put("uuidOrPassword", profile.uuidOrPassword)
            put("encryption", normalizedEncryption)
            put("transport", transport.name)
            put("security", security.name)
            put("sni", profile.sni.trim().lowercase(Locale.ROOT))
            put("alpn", normalizedAlpn)
            put("fingerprint", normalizedFp)
            put("publicKey", profile.publicKey.trim())
            put("shortId", profile.shortId.trim().lowercase(Locale.ROOT))
            put("path", profile.path.trim())
            put("hostHeader", profile.hostHeader.trim().lowercase(Locale.ROOT))
            put("serviceName", profile.serviceName.trim())
            put("flow", profile.flow.trim().lowercase(Locale.ROOT))
            put("allowInsecure", profile.allowInsecure)
            put("cipherSuites", profile.cipherSuites.trim())
            put("finalMask", profile.finalMask.trim())
            put("echConfigList", profile.echConfigList.trim())
            put("echForceQuery", normalizedEchForce)
            put("mldsa65Verify", profile.mldsa65Verify.trim())
            put("featureOverrides", canonicalFeatures)
            put("isLocked", profile.isLocked)
            put("vaultKeyScheme", profile.vaultKeyScheme.trim())
        }.toString()

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun stableId(sourceType: SourceType, subscriptionId: String?, fingerprint: String): String {
        val key = "${sourceType.name}:${subscriptionId.orEmpty()}:$fingerprint"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }

    fun normalizeHost(raw: String): String {
        val trimmed = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (trimmed.isEmpty()) return ""
        if (isNumericIpLiteral(trimmed)) {
            return runCatching {
                InetAddress.getByName(trimmed).hostAddress?.lowercase(Locale.ROOT) ?: trimmed.lowercase(Locale.ROOT)
            }.getOrDefault(trimmed.lowercase(Locale.ROOT))
        }
        return trimmed.lowercase(Locale.ROOT).trimEnd('.')
    }

    private fun isNumericIpLiteral(host: String): Boolean {
        if (host.all { it in '0'..'9' || it == '.' }) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
        if (host.contains(':') && host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return true
        }
        return false
    }

    private fun normalizeEncryption(protocol: ProxyProtocol, raw: String): String {
        val trimmed = raw.trim()
        return when (protocol) {
            ProxyProtocol.VLESS -> {
                if (trimmed.startsWith("mlkem768x25519plus")) trimmed
                else trimmed.lowercase(Locale.ROOT).ifEmpty { "none" }
            }
            ProxyProtocol.VMESS -> trimmed.lowercase(Locale.ROOT).ifEmpty { "auto" }
            ProxyProtocol.SHADOWSOCKS -> trimmed.lowercase(Locale.ROOT)
            else -> trimmed.lowercase(Locale.ROOT).ifEmpty { "none" }
        }
    }
}
