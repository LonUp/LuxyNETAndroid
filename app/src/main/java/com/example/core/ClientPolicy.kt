package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.RoutingMode
import com.example.data.model.StabilitySettings
import com.example.data.model.TriState
import java.util.Locale

/** The minimal client's allowlist, independent of any previously persisted experimental opt-in. */
object ClientPolicy {
    private val transports = setOf("TCP", "WS", "GRPC", "HTTPUPGRADE", "XHTTP")
    private val xhttpModes = setOf("auto", "packet-up", "stream-up", "stream-one")
    private val securities = setOf("NONE", "TLS", "REALITY")
    private val defaultFeatures = NodeFeatureOverrides()
    private val vmessCiphers = setOf("auto", "aes-128-gcm", "chacha20-poly1305")
    private val shadowsocksCiphers = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"
    )

    // Official Xray v26.11.9 accepts protocol encryption independently of TLS.
    // Never lowercase case-sensitive public-key material.
    private fun hasVlessEncryption(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size < 4 || parts[0] != "mlkem768x25519plus" ||
            parts[1] !in setOf("native", "xorpub", "random") ||
            parts[2] !in setOf("0rtt", "1rtt")) return false
        var keys = 0
        for (part in parts.drop(3)) {
            // Upstream short segments are padding; native initialization validates them.
            if (part.length < 20) continue
            if (part.length % 4 == 1 || part.any {
                    it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_'
                }) return false
            if (Base64Compat.decode(part).size !in setOf(32, 1184)) return false
            keys++
        }
        return keys > 0 && parts.last().length >= 20
    }

    fun isSupported(profile: ServerProfileEntity): Boolean {
        val protocol = profile.protocol.trim().uppercase(Locale.ROOT)
        val transport = profile.transport.trim().uppercase(Locale.ROOT)
        val security = profile.security.trim().uppercase(Locale.ROOT)
        if (profile.address.isBlank() || profile.address.any { it.isWhitespace() || it == '/' } ||
            profile.port !in 1..65535) return false
        if (protocol != "SOCKS" && profile.uuidOrPassword.isBlank()) return false
        if (profile.isLocked && profile.vaultExpiresAtMs?.let { it <= System.currentTimeMillis() } == true) return false
        val encryption = profile.encryption.trim().lowercase(Locale.ROOT)
        if (transport !in transports || security !in securities) return false
        if (profile.allowInsecure || profile.fingerprint.equals("unsafe", ignoreCase = true)) return false
        if (profile.finalMask.isNotBlank() && !profile.finalMask.equals("none", ignoreCase = true)) return false
        if (profile.echConfigList.isNotBlank() || profile.mldsa65Verify.isNotBlank()) return false
        if (profile.echForceQuery.isNotBlank() && !profile.echForceQuery.equals("none", ignoreCase = true)) return false
        val features = if (profile.featureOverrides.isBlank()) defaultFeatures
            else NodeFeatureOverrides.decode(profile.featureOverrides)
        if (features.fragment == TriState.ON || features.realityFragment == TriState.ON ||
            features.ech == TriState.ON || features.failClosedEch == TriState.ON ||
            features.noise == TriState.ON || features.tlsPadding == TriState.ON ||
            features.allowUnencryptedVless == TriState.ON || features.mixedCaseSni == TriState.ON) return false
        if (features.packetEncoding.isNotBlank() && features.packetEncoding != "xudp") return false
        if (features.xhttpMode.isNotBlank() && (transport != "XHTTP" || features.xhttpMode !in xhttpModes)) return false
        if (security == "REALITY" && (protocol != "VLESS" || transport != "TCP" || profile.publicKey.isBlank())) return false
        if (profile.flow.isNotBlank() &&
            (protocol != "VLESS" || transport != "TCP" ||
                (profile.flow != "xtls-rprx-vision" && profile.flow != "xtls-rprx-vision-udp443"))) return false
        return when (protocol) {
            "VLESS" -> if (encryption.isEmpty() || encryption == "none") {
                security == "TLS" || security == "REALITY"
            } else hasVlessEncryption(profile.encryption.trim())
            "VMESS" -> (encryption.ifBlank { "auto" } in vmessCiphers) && security != "REALITY"
            "TROJAN" -> security == "TLS"
            "SHADOWSOCKS" -> encryption in shadowsocksCiphers && security != "REALITY"
            // SOCKS follows the imported proxy semantics; it does not claim to encrypt payloads.
            "SOCKS" -> security != "REALITY" && transport == "TCP"
            else -> false
        }
    }

    /** A runtime projection only: stored profiles, subscriptions and former settings remain intact. */
    fun settings(current: StabilitySettings): StabilitySettings = StabilitySettings(
        activeProfileId = current.activeProfileId,
        theme = current.theme,
        language = current.language,
        subscriptionUserAgent = current.subscriptionUserAgent,
        subscriptionTopNLimit = current.subscriptionTopNLimit,
        geoDbVersion = current.geoDbVersion,
        geoDbLastUpdatedAt = current.geoDbLastUpdatedAt,
        routingMode = RoutingMode.GLOBAL,
        autoConnectOnLaunch = false,
        autoReconnect = false,
        smartFailover = false,
        probeIntervalSec = 0,
        muxEnabled = false,
        tcpFastOpen = false,
        antiCensorshipPreset = AntiCensorshipPreset.DIRECT_LOW_LATENCY,
        utlsFingerprint = "chrome",
        fakeDnsEnabled = false,
        sniffingEnabled = false,
        blockQuicUdp = false,
        domainStrategy = "AsIs",
        happyEyeballsEnabled = false,
        echForceQuery = "none",
        awgJc = 0,
        awgJmin = 0,
        awgJmax = 0,
        warpNoisePreset = "off",
        chainingMode = ChainingMode.NONE,
        dnsPoolStrategy = DnsPoolStrategy.SEQUENTIAL_FALLBACK,
        dnsIntegrityFilterEnabled = false,
        dnsTcpEnabled = false,
        dnsDotEnabled = false,
        dnsUdpEnabled = false,
        appSplitTunnelMode = AppSplitTunnelMode.DISABLED,
        strictUidIsolationEnabled = false,
        adaptiveFallbackEnabled = false,
        socialPolicies = emptyMap(),
        experimentalModeEnabled = false
    )
}
