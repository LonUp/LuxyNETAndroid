package com.example.xraycore

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.core.NodeFeaturePolicy
import com.example.core.ProtocolGuardrails
import com.example.core.ProxyUriParser
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class AdaptiveStageSpec(
    val stage: Int,
    val title: String,
    val summary: String,
    val presetName: AntiCensorshipPreset
)

object XrayConfigCompiler {

    /**
     * `freedom` accepts only `AsIs`, `UseIP`, `UseIPv4`, `UseIPv6`. `IPIfNonMatch` / `IPOnDemand`
     * are *routing* policies, and putting one on the outbound makes the core reject the entire
     * config with "unsupported domain strategy" — the failure that made connect fail on 3.0.0.
     * The direct outbound therefore passes the domain through untouched and lets routing decide.
     */
    internal fun freedomDomainStrategy(routingStrategy: String): String =
        when (routingStrategy.trim().uppercase(Locale.ROOT)) {
            "USEIP" -> "UseIP"
            "USEIPV4" -> "UseIPv4"
            "USEIPV6" -> "UseIPv6"
            else -> "AsIs"
        }

    /** Routing accepts exactly these three; anything unknown falls back to the default. */
    internal fun routingDomainStrategy(routingStrategy: String): String =
        when (routingStrategy.trim().uppercase(Locale.ROOT)) {
            "ASIS" -> "AsIs"
            "IPONDEMAND" -> "IPOnDemand"
            else -> "IPIfNonMatch"
        }

    const val LUXY_CORE_VERSION = "LuxyVpnCore 3.0.0-adaptive"

    val CIPHER_SUITE_PRESETS = listOf(
        "Auto" to "",
        "TLS1.3 GCM" to "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384",
        "ChaCha20" to "TLS_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "ECDHE ECDSA" to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_AES_128_GCM_SHA256"
    )

    val FINAL_MASK_PRESETS = listOf(
        "none", "header-custom", "header-utp", "header-wechat",
        "header-dtls", "header-wireguard", "mkcp-aes128gcm", "sudoku"
    )

    val FINAL_MASK_FAMILIES = listOf("tcp", "tcp4", "tcp6", "udp4", "udp6", "oob")
    val DATAGRAM_SHAPING_PRESETS = listOf("off", "raw", "quic", "dns", "sip", "random")

    val ADAPTIVE_STAGES = listOf(
        AdaptiveStageSpec(
            0,
            "Stage 0 • Baseline",
            "Direct / Standard TLS or REALITY low-latency profile",
            AntiCensorshipPreset.DIRECT_LOW_LATENCY
        ),
        AdaptiveStageSpec(
            1,
            "Stage 1 • Fragment + FinalMask",
            "TLS ClientHello Fragmentation + FinalMask + Mixed-Case SNI",
            AntiCensorshipPreset.MAX_STEALTH
        ),
        AdaptiveStageSpec(
            2,
            "Stage 2 • CDN + Fail-Closed ECH",
            "CDN Edge / XHTTP Padding + Strict Fail-Closed ECH",
            AntiCensorshipPreset.CDN_FRONTING
        ),
        AdaptiveStageSpec(
            3,
            "Stage 3 • WARP / AWG / MASQUE Hop",
            "Multi-hop WARP/AmneziaWG/MASQUE datagram transport wrap",
            AntiCensorshipPreset.STRICT_DPI_BYPASS
        ),
        AdaptiveStageSpec(
            4,
            "Stage 4 • DNS / DoH Fallback",
            "Parallel DoH resolution and Xray fragment fallback",
            AntiCensorshipPreset.STRICT_DPI_BYPASS
        )
    )

    fun stageSpec(stage: Int): AdaptiveStageSpec =
        ADAPTIVE_STAGES[stage.coerceIn(0, ADAPTIVE_STAGES.lastIndex)]

    fun randomizeCaseSni(sni: String): String {
        val trimmed = sni.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.all { it.isDigit() || it in ":.[]" || it in 'a'..'f' || it in 'A'..'F' }) {
            return trimmed
        }
        var letterIdx = 0
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                sb.append(if (letterIdx % 2 == 0) ch.uppercaseChar() else ch.lowercaseChar())
                letterIdx++
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun describeDialerChain(settings: StabilitySettings, node: ServerProfileEntity? = null): String {
        val effective = settings.asEffectiveBaseline()
        if (!effective.experimentalModeEnabled) {
            return if (ProtocolGuardrails.isExperimentalNode(node)) {
                "Standard Xray Outbound (Experimental Node Gated)"
            } else {
                "Standard Xray Outbound"
            }
        }
        val parts = mutableListOf<String>()
        if (effective.chainingMode != ChainingMode.NONE) {
            parts += effective.chainingMode.label
        }
        val proto = node?.let { ProxyProtocol.parse(it.protocol) }
        when {
            proto == ProxyProtocol.WIREGUARD ||
                settings.chainingMode == ChainingMode.WARP_TO_PROXY ||
                settings.chainingMode == ChainingMode.PROXY_TO_WARP ->
                parts += "AWG(Jc=${settings.awgJc},${settings.warpNoisePreset})"
            proto == ProxyProtocol.MASQUE -> {
                val shaping = node.encryption.takeIf { it.isNotBlank() && it != "none" } ?: settings.warpNoisePreset
                parts += "MASQUE($shaping)"
            }
            proto == ProxyProtocol.DNSTT || proto == ProxyProtocol.MASTERDNS ->
                parts += "Unsupported legacy protocol (${proto.name})"
        }

        if (settings.tlsFragmentEnabled) {
            val hostTag = if (settings.fragmentHost.isNotBlank()) "@${settings.fragmentHost}" else ""
            val realityTag = if (settings.realityFragmentEnabled) "+REALITY" else ""
            parts += "Fragment(${settings.fragmentLength}B/${settings.fragmentPackets}$hostTag$realityTag)"
        }
        if (settings.noiseEnabled) {
            parts += "Noise(${settings.noiseType}:${settings.noisePacketSize}B)"
        }
        val activeMask = node?.finalMask?.takeIf { it.isNotBlank() && it != "none" }
            ?: settings.finalMaskType.takeIf { it.isNotBlank() && it != "none" }.orEmpty()
        if (activeMask.isNotEmpty()) {
            val fam = settings.finalMaskFamily.ifBlank { "tcp" }
            parts += if (fam == "tcp") "FinalMask($activeMask)" else "FinalMask[$fam]($activeMask)"
        }
        if (settings.mixedCaseSni) parts += "MixedSNI"
        if (settings.tlsPaddingEnabled) parts += "TLSPad(7685)"
        if (settings.echEnabled || !node?.echConfigList.isNullOrBlank()) {
            parts += "ECH(${settings.echForceQuery})"
        }
        val activeCiphers = node?.cipherSuites?.takeIf { it.isNotBlank() } ?: settings.cipherSuites
        if (activeCiphers.isNotBlank()) parts += "Ciphers"
        if (settings.happyEyeballsEnabled) parts += "HappyEyeballs"
        if (settings.fakeDnsEnabled) parts += "FakeDNS"
        if (settings.blockQuicUdp) parts += "QUIC-Guard"

        return if (parts.isEmpty()) "Direct Outbound" else parts.joinToString(" → ")
    }

    fun applyPresetToSettings(base: StabilitySettings, preset: AntiCensorshipPreset): StabilitySettings {
        val next = base.copy(antiCensorshipPreset = preset)
        return when (preset) {
            AntiCensorshipPreset.BALANCED_STEALTH -> next.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "tlshello",
                fragmentLength = "10-20",
                fragmentInterval = "10-20",
                fragmentMaxSplit = "32-64",
                noiseEnabled = false,
                utlsFingerprint = "randomized",
                finalMaskType = "none",
                cipherSuites = "",
                echEnabled = false,
                happyEyeballsEnabled = true,
                fakeDnsEnabled = true,
                sniffingEnabled = true,
                blockQuicUdp = true,
                muxEnabled = true,
                muxConcurrency = 8,
                dohEnabled = true,
                tcpFastOpen = false
            )
            AntiCensorshipPreset.STRICT_DPI_BYPASS -> next.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "1-3",
                fragmentLength = "3-10",
                fragmentInterval = "10-20",
                fragmentMaxSplit = "64-128",
                noiseEnabled = true,
                noiseType = "rand",
                noisePacketSize = "20-50",
                noiseDelayMs = "10-20",
                utlsFingerprint = "random",
                finalMaskType = "header-custom",
                cipherSuites = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256",
                echEnabled = true,
                echForceQuery = "half",
                happyEyeballsEnabled = true,
                fakeDnsEnabled = true,
                sniffingEnabled = true,
                blockQuicUdp = true,
                muxEnabled = true,
                muxConcurrency = 4,
                xhttpMode = "packet-up",
                xhttpPaddingBytes = "100-1000",
                dohEnabled = true,
                tcpFastOpen = false
            )
            AntiCensorshipPreset.CDN_FRONTING -> next.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "tlshello",
                fragmentLength = "20-40",
                fragmentInterval = "5-15",
                noiseEnabled = false,
                utlsFingerprint = "chrome",
                finalMaskType = "none",
                cipherSuites = "TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                echEnabled = true,
                echForceQuery = "half",
                happyEyeballsEnabled = true,
                fakeDnsEnabled = true,
                sniffingEnabled = true,
                blockQuicUdp = true,
                muxEnabled = true,
                muxConcurrency = 8,
                xhttpMode = "packet-up",
                xhttpPaddingBytes = "200-1200",
                dohEnabled = true,
                tcpFastOpen = false
            )
            AntiCensorshipPreset.MAX_STEALTH -> next.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "1-3",
                fragmentLength = "5-12",
                fragmentInterval = "8-18",
                fragmentMaxSplit = "64-128",
                noiseEnabled = true,
                noiseType = "rand",
                noisePacketSize = "24-64",
                noiseDelayMs = "10-20",
                noiseApplyTo = "ip",
                utlsFingerprint = "unsafe",
                finalMaskType = "header-custom",
                cipherSuites = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256",
                echEnabled = true,
                echForceQuery = "half",
                happyEyeballsEnabled = true,
                happyEyeballsTryDelayMs = 200,
                fakeDnsEnabled = true,
                sniffingEnabled = true,
                blockQuicUdp = true,
                muxEnabled = true,
                muxConcurrency = 6,
                xhttpMode = "packet-up",
                xhttpPaddingBytes = "200-1200",
                dohEnabled = true,
                tcpFastOpen = false
            )
            AntiCensorshipPreset.DIRECT_LOW_LATENCY -> next.copy(
                tlsFragmentEnabled = false,
                noiseEnabled = false,
                utlsFingerprint = "chrome",
                finalMaskType = "none",
                cipherSuites = "",
                echEnabled = false,
                happyEyeballsEnabled = true,
                fakeDnsEnabled = false,
                sniffingEnabled = true,
                blockQuicUdp = false,
                muxEnabled = false,
                tcpFastOpen = true
            )
            AntiCensorshipPreset.CUSTOM -> next
        }
    }

    private fun buildSockopt(
        settings: StabilitySettings,
        includeDialerProxy: Boolean = false,
        dialerTarget: String? = null,
        forceDisableTfo: Boolean = false
    ): JSONObject = JSONObject().apply {
        put("tcpFastOpen", if (forceDisableTfo) false else settings.tcpFastOpen)
        put("tcpNoDelay", true)
        put("tcpKeepAliveInterval", settings.probeIntervalSec)
        put("tcpHandshakeTimeout", settings.tcpHandshakeTimeoutMs)
        put("tlsHandshakeTimeout", settings.tlsHandshakeTimeoutMs)
        if (settings.experimentalModeEnabled && settings.happyEyeballsEnabled) {
            put("happyEyeballs", JSONObject().apply {
                put("tryDelayMs", settings.happyEyeballsTryDelayMs)
                put("prioritizeIPv6", false)
                put("maxConcurrentTry", 4)
            })
        }
        if (settings.experimentalModeEnabled && includeDialerProxy && !dialerTarget.isNullOrBlank()) {
            put("dialerProxy", dialerTarget)
        }
    }

    private fun buildFinalMaskObject(maskType: String): JSONObject {
        val clean = maskType.trim()
        return JSONObject().apply {
            put("type", clean)
            when {
                clean.startsWith("header-") -> put("settings", JSONObject().put("domain", "www.bing.com"))
                clean == "mkcp-aes128gcm" -> put("settings", JSONObject().put("password", "luxyvpn-aes-mask"))
                clean == "sudoku" -> put("settings", JSONObject().apply {
                    put("ascii", "prefer_entropy")
                    put("paddingMin", 8)
                    put("paddingMax", 32)
                })
                clean == "oob" -> put("settings", JSONObject().put("mode", "oob-urgent"))
            }
        }
    }

    private fun buildWarpOutbound(
        tag: String,
        profile: ServerProfileEntity?,
        settings: StabilitySettings,
        dialerTarget: String?
    ): JSONObject {
        val endpoint = if (profile != null) {
            val host = if (profile.address.contains(':') && !profile.address.startsWith("[")) {
                "[${profile.address}]"
            } else {
                profile.address
            }
            "$host:${profile.port}"
        } else {
            settings.warpEndpoint.ifBlank { "162.159.192.1:2408" }
        }
        val secretKey = profile?.uuidOrPassword?.trim()?.ifEmpty { null } ?: settings.warpPrivateKey
        val publicKey = profile?.publicKey?.trim()?.ifEmpty { null } ?: settings.warpPublicKey
        val reservedStr = profile?.shortId?.trim()?.ifEmpty { null } ?: settings.warpReserved
        val parsedReserved = reservedStr.split(',', ':')
            .mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 255) }
            .ifEmpty { listOf(0, 0, 0) }
        val localAddresses = (profile?.path?.trim()?.ifEmpty { null } ?: settings.warpLocalAddress)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("172.16.0.2/32") }
        val isExperimental = settings.experimentalModeEnabled
        val awgOverride = if (isExperimental) {
            profile?.serviceName?.takeIf { it.isNotBlank() }?.let { ProxyUriParser.parseAwgQuery(it) }
        } else null
        val shaping = if (isExperimental) {
            profile?.encryption?.takeIf { it.isNotBlank() && it != "none" } ?: settings.warpNoisePreset
        } else ""
        val flowMtu = profile?.flow?.toIntOrNull()
        val mtu = if (flowMtu != null && flowMtu in 576..1500) flowMtu else settings.mtu

        val effectiveMask = if (isExperimental) {
            profile?.finalMask
                ?.takeIf { it.isNotBlank() && it != "none" && !it.equals("awg", ignoreCase = true) }
                ?: settings.finalMaskType.takeIf { it != "none" }.orEmpty()
        } else ""

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", secretKey)
                put("address", JSONArray(localAddresses))
                put("mtu", mtu)
                put("reserved", JSONArray(parsedReserved))
                if (isExperimental) {
                    put("jc", awgOverride?.jc ?: settings.awgJc)
                    put("jmin", awgOverride?.jmin ?: settings.awgJmin)
                    put("jmax", awgOverride?.jmax ?: settings.awgJmax)
                    put("s1", awgOverride?.s1 ?: settings.awgS1)
                    put("s2", awgOverride?.s2 ?: settings.awgS2)
                    put("h1", awgOverride?.h1 ?: settings.awgH1)
                    put("h2", awgOverride?.h2 ?: settings.awgH2)
                    put("h3", awgOverride?.h3 ?: settings.awgH3)
                    put("h4", awgOverride?.h4 ?: settings.awgH4)
                    put("shaping", shaping)
                }
                put("peers", JSONArray().put(JSONObject().apply {
                    put("endpoint", endpoint)
                    put("publicKey", publicKey)
                    put("allowedIPs", JSONArray(listOf("0.0.0.0/0", "::/0")))
                    put("keepAlive", 25)
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "wg")
                put("security", "none")
                if (isExperimental && effectiveMask.isNotEmpty()) {
                    put("finalmask", JSONObject().put(settings.finalMaskFamily.ifBlank { "udp4" }, JSONArray().put(buildFinalMaskObject(effectiveMask))))
                }
                put("sockopt", buildSockopt(settings, isExperimental && !dialerTarget.isNullOrBlank(), if (isExperimental) dialerTarget else null, forceDisableTfo = true))
            })
        }
    }

    private fun buildOutbound(
        tag: String,
        node: ServerProfileEntity,
        globalSettings: StabilitySettings,
        dialerOverride: String? = null
    ): JSONObject {
        val settings = NodeFeaturePolicy.apply(globalSettings, node)
        val protocol = ProxyProtocol.parse(node.protocol)
        if (protocol == ProxyProtocol.WIREGUARD) {
            return buildWarpOutbound(tag, node, settings, dialerOverride)
        }

        val outProto: String
        val outSettings = JSONObject()

        when (protocol) {
            ProxyProtocol.VLESS -> {
                outProto = "vless"
                outSettings.put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", node.uuidOrPassword)
                        put("encryption", node.encryption.ifBlank { "none" })
                        if (node.flow.isNotBlank()) put("flow", node.flow)
                        val packetEncoding = NodeFeatureOverrides.decode(node.featureOverrides).packetEncoding
                        if (packetEncoding.isNotBlank()) put("packetEncoding", packetEncoding)
                    }))
                }))
            }
            ProxyProtocol.VMESS -> {
                outProto = "vmess"
                outSettings.put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", node.uuidOrPassword)
                        put("security", node.encryption.ifBlank { "auto" })
                    }))
                }))
            }
            ProxyProtocol.TROJAN -> {
                outProto = "trojan"
                outSettings.put("servers", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("password", node.uuidOrPassword)
                }))
            }
            ProxyProtocol.SHADOWSOCKS -> {
                outProto = "shadowsocks"
                outSettings.put("servers", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    put("method", node.encryption.ifBlank { "aes-256-gcm" })
                    put("password", node.uuidOrPassword)
                }))
            }
            ProxyProtocol.SOCKS -> {
                outProto = "socks"
                outSettings.put("servers", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    if (node.uuidOrPassword.isNotBlank()) {
                        val parts = node.uuidOrPassword.split(':', limit = 2)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("user", parts[0])
                            put("pass", parts.getOrElse(1) { "" })
                        }))
                    }
                }))
            }
            ProxyProtocol.HYSTERIA2 -> {
                outProto = "hysteria"
                outSettings.put("version", 2)
                outSettings.put("address", node.address)
                outSettings.put("port", node.port)
                outSettings.put("auth", node.uuidOrPassword)
            }
            ProxyProtocol.MASQUE -> {
                outProto = "masque"
                val localAddrs = node.path.trim().ifEmpty { settings.warpLocalAddress }
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("172.16.0.2/32") }
                val hostFormatted = if (node.address.contains(':') && !node.address.startsWith("[")) "[${node.address}]" else node.address
                val flowMtu = node.flow.toIntOrNull()
                outSettings.put("endpoint", "$hostFormatted:${node.port}")
                outSettings.put("privateKey", node.uuidOrPassword)
                outSettings.put("publicKey", node.publicKey)
                outSettings.put("address", JSONArray(localAddrs))
                outSettings.put("mtu", if (flowMtu != null && flowMtu in 576..1500) flowMtu else settings.mtu)
                outSettings.put("shaping", node.encryption.takeIf { it.isNotBlank() && it != "none" } ?: settings.warpNoisePreset)
            }
            ProxyProtocol.HTTP -> {
                outProto = "http"
                outSettings.put("servers", JSONArray().put(JSONObject().apply {
                    put("address", node.address)
                    put("port", node.port)
                    if (node.uuidOrPassword.isNotBlank()) {
                        val parts = node.uuidOrPassword.split(':', limit = 2)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("user", parts[0])
                            put("pass", parts.getOrElse(1) { "" })
                        }))
                    }
                }))
            }
            ProxyProtocol.TUIC, ProxyProtocol.ANYTLS, ProxyProtocol.SSH,
            ProxyProtocol.OPENCONNECT, ProxyProtocol.OPENVPN, ProxyProtocol.MIERU,
            ProxyProtocol.HYSTERIA,
            // Absent from the bundled core (no `dnstt`/`masterdns` symbols in libgojni.so):
            // emitting them produced configs the parser rejected, so they now fail loudly here.
            ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS ->
                error(ProtocolGuardrails.uncompiledProtocolMessage(protocol))
            ProxyProtocol.WIREGUARD -> error("Handled above")
        }

        val transport = when (protocol) {
            ProxyProtocol.MASQUE -> TransportType.MASQUE
            ProxyProtocol.DNSTT -> TransportType.DNSTT
            ProxyProtocol.MASTERDNS -> TransportType.MASTERDNS
            else -> TransportType.parse(node.transport)
        }
        val requestedSecurity = SecurityType.parse(node.security)
        val hasVlessEncryption = protocol == ProxyProtocol.VLESS &&
            ProtocolGuardrails.hasVlessEncryption(node.encryption)
        if (protocol == ProxyProtocol.VLESS) {
            check(ProtocolGuardrails.isValidVlessEncryptionValue(node.encryption)) {
                "Unknown VLESS encryption value"
            }
            check(!hasVlessEncryption || settings.experimentalModeEnabled) {
                "VLESS Encryption requires Experimental Mode"
            }
        }
        val security = when {
            protocol == ProxyProtocol.MASQUE -> SecurityType.TLS
            protocol == ProxyProtocol.DNSTT || protocol == ProxyProtocol.MASTERDNS ->
                if (node.encryption.lowercase() in setOf("dot", "doh")) SecurityType.TLS else requestedSecurity
            protocol == ProxyProtocol.VLESS && requestedSecurity == SecurityType.NONE -> {
                if (!hasVlessEncryption) {
                    check(settings.allowUnencryptedVless) {
                        "Unencrypted VLESS is disabled; enable the explicit experimental setting to allow it"
                    }
                }
                SecurityType.NONE
            }
            protocol == ProxyProtocol.TROJAN && requestedSecurity == SecurityType.NONE ->
                error("Unencrypted Trojan outbounds are disabled")
            else -> requestedSecurity
        }

        val isExperimental = settings.experimentalModeEnabled
        val baseSni = node.sni.ifBlank { node.hostHeader.ifBlank { node.address } }
        val allowMixedSni = isExperimental &&
            settings.mixedCaseSni &&
            security == SecurityType.TLS &&
            protocol != ProxyProtocol.HYSTERIA2 &&
            protocol != ProxyProtocol.DNSTT &&
            protocol != ProxyProtocol.MASTERDNS
        val effectiveSni = if (allowMixedSni) randomizeCaseSni(baseSni) else baseSni
        val effectiveFp = if (!isExperimental) {
            "chrome"
        } else if (node.fingerprint.equals("unsafe", ignoreCase = true)) {
            "unsafe"
        } else {
            settings.utlsFingerprint.ifBlank { node.fingerprint.ifBlank { "chrome" } }
        }
        val effectiveCipherSuites = if (!isExperimental || security == SecurityType.REALITY) "" else node.cipherSuites.ifBlank { settings.cipherSuites }
        val effectiveEchConfig = if (!isExperimental || security == SecurityType.REALITY) "" else node.echConfigList.ifBlank { settings.echConfigList }
        val echForcedOff = NodeFeaturePolicy.echForcedOff(node)
        val effectiveEchEnabled = !echForcedOff && isExperimental && security != SecurityType.REALITY && (settings.echEnabled || effectiveEchConfig.isNotBlank())
        val effectiveEchForceQuery = when {
            !isExperimental || security == SecurityType.REALITY -> "none"
            node.echForceQuery.isNotBlank() && node.echForceQuery != "none" -> node.echForceQuery
            else -> settings.echForceQuery
        }
        val effectiveFinalMask = if (isExperimental) {
            node.finalMask.ifBlank { settings.finalMaskType.takeIf { it != "none" }.orEmpty() }
        } else ""

        val stream = JSONObject().apply {
            put("network", transport.name.lowercase())
            put("security", security.name.lowercase())
        }

        if (security == SecurityType.TLS) {
            val effectiveFailClosed = isExperimental && (settings.failClosedEch || effectiveEchForceQuery.equals("full", ignoreCase = true))
            stream.put("tlsSettings", JSONObject().apply {
                put("serverName", effectiveSni)
                put("fingerprint", effectiveFp)
                put("allowInsecure", node.allowInsecure)
                if (isExperimental && settings.tlsPaddingEnabled) {
                    put("padding", true)
                    put("tlsPadding", "rfc7685")
                }
                val alpnList = node.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) put("alpn", JSONArray(alpnList))
                if (effectiveCipherSuites.isNotBlank()) put("cipherSuites", effectiveCipherSuites)
                if (isExperimental && (effectiveEchEnabled || effectiveFailClosed)) {
                    if (effectiveEchConfig.isNotBlank()) put("echConfigList", effectiveEchConfig)
                    put("echForceQuery", if (effectiveFailClosed) "full" else effectiveEchForceQuery)
                    put("failClosedEch", effectiveFailClosed)
                }
            })
        } else if (security == SecurityType.REALITY) {
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", baseSni)
                put("fingerprint", effectiveFp)
                put("publicKey", node.publicKey)
                put("shortId", node.shortId)
                put("spiderX", NodeFeatureOverrides.decode(node.featureOverrides).spiderX.ifBlank { "/" })
                if (isExperimental) {
                    put("fragment", settings.realityFragmentEnabled && settings.tlsFragmentEnabled)
                    if (node.mldsa65Verify.isNotBlank()) put("mldsa65Verify", node.mldsa65Verify)
                }
            })
        }

        when (transport) {
            TransportType.WS -> stream.put("wsSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
            })
            TransportType.GRPC -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", node.serviceName)
                put("multiMode", true)
            })
            TransportType.XHTTP -> stream.put("xhttpSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
                put("mode", if (isExperimental) settings.xhttpMode else "auto")
                if (isExperimental) put("xPaddingBytes", settings.xhttpPaddingBytes)
            })
            TransportType.HTTPUPGRADE -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
            })
            else -> Unit
        }

        if (isExperimental && effectiveFinalMask.isNotBlank() && effectiveFinalMask != "none") {
            val maskFamily = settings.finalMaskFamily.ifBlank { "tcp" }.lowercase()
            stream.put("finalmask", JSONObject().apply {
                if (maskFamily == "oob") {
                    put("oob", JSONArray().put(buildFinalMaskObject("oob")))
                } else {
                    put(maskFamily, JSONArray().put(buildFinalMaskObject(effectiveFinalMask)))
                }
            })
        }

        stream.put(
            "sockopt",
            buildSockopt(
                settings = settings,
                includeDialerProxy = !dialerOverride.isNullOrBlank(),
                dialerTarget = dialerOverride,
                forceDisableTfo = ProtocolGuardrails.isDatagramProtocol(protocol)
            )
        )

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", outProto)
            put("settings", outSettings)
            put("streamSettings", stream)
            if (settings.muxEnabled && node.flow.isBlank() && !ProtocolGuardrails.isDatagramProtocol(protocol)) {
                put("mux", JSONObject().apply {
                    put("enabled", true)
                    put("concurrency", settings.muxConcurrency)
                    put("xudpConcurrency", settings.muxConcurrency)
                    put("xudpProxyUDP443", if (settings.blockQuicUdp) "reject" else "allow")
                })
            }
        }
    }

    fun compileXrayConfigObject(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): JSONObject {
        val baseSettings = settings.asEffectiveBaseline()
        val nodeTuned = NodeFeaturePolicy.apply(baseSettings, node)
        val isExperimental = baseSettings.experimentalModeEnabled

        val dnsServers = mutableListOf<String>()
        if (baseSettings.fakeDnsEnabled) dnsServers += "fakedns"
        if (baseSettings.dohEnabled) dnsServers += baseSettings.dohServerUrl.ifBlank { "https://cloudflare-dns.com/dns-query" }
        if (baseSettings.dnsDotEnabled && baseSettings.dnsDotServer.isNotBlank()) {
            dnsServers += if (baseSettings.dnsDotServer.lowercase().startsWith("tls://")) {
                baseSettings.dnsDotServer
            } else {
                "tls://${baseSettings.dnsDotServer}"
            }
        }
        if (baseSettings.dnsTcpEnabled && baseSettings.primaryDns.isNotBlank()) {
            dnsServers += "tcp://${baseSettings.primaryDns}"
        }
        if (baseSettings.dnsUdpEnabled) {
            dnsServers += baseSettings.primaryDns
            if (baseSettings.fallbackDns.isNotBlank()) dnsServers += baseSettings.fallbackDns
        } else if (dnsServers.isEmpty()) {
            dnsServers += baseSettings.primaryDns
        }

        val isPrimaryDatagram = ProtocolGuardrails.isDatagramNode(node)
        val primaryProto = node?.let { ProxyProtocol.parse(it.protocol) }
        // Refuse before the experimental blackhole: a blackhole config would start the tunnel and
        // silently drop every packet for a protocol the core cannot run at all.
        if (primaryProto != null && !ProtocolGuardrails.isCompiledByCore(primaryProto)) {
            error(ProtocolGuardrails.uncompiledProtocolMessage(primaryProto))
        }
        val isPrimaryWgOrMasque = primaryProto == ProxyProtocol.WIREGUARD || primaryProto == ProxyProtocol.MASQUE
        val effectiveTfo = if (nodeTuned.tlsFragmentEnabled || nodeTuned.realityFragmentEnabled || isPrimaryDatagram) {
            false
        } else {
            nodeTuned.tcpFastOpen
        }
        val effectiveSettings = nodeTuned.copy(
            tcpFastOpen = effectiveTfo,
            muxEnabled = if (isPrimaryDatagram) false else nodeTuned.muxEnabled
        )

        val nodeSecurity = node?.let { SecurityType.parse(it.security) }
        val relaySecurity = relayNode?.let { SecurityType.parse(it.security) }
        val baseHopTag = when {
            !isExperimental -> null
            isPrimaryDatagram -> if (effectiveSettings.noiseEnabled) "noise-out" else null
            effectiveSettings.tlsFragmentEnabled -> "fragment-out"
            effectiveSettings.noiseEnabled -> "noise-out"
            else -> null
        }

        fun effectiveTransportHopFor(sec: SecurityType?, proto: ProxyProtocol? = null): String? {
            if (!isExperimental) return null
            if (proto != null && ProtocolGuardrails.isDatagramProtocol(proto)) {
                return if (effectiveSettings.noiseEnabled) "noise-out" else null
            }
            if (sec == SecurityType.REALITY && !effectiveSettings.realityFragmentEnabled) {
                return if (effectiveSettings.noiseEnabled) "noise-out" else null
            }
            if (sec == SecurityType.REALITY && effectiveSettings.realityFragmentEnabled) {
                return "fragment-out"
            }
            return baseHopTag
        }

        val effectiveChainingMode = if (!isExperimental) {
            ChainingMode.NONE
        } else if (
            isPrimaryWgOrMasque &&
            (effectiveSettings.chainingMode == ChainingMode.WARP_TO_PROXY ||
                effectiveSettings.chainingMode == ChainingMode.PROXY_TO_WARP)
        ) {
            ChainingMode.NONE
        } else {
            effectiveSettings.chainingMode
        }

        val outbounds = JSONArray()
        var relayUsesFragment = false

        when (effectiveChainingMode) {
            ChainingMode.WARP_TO_PROXY -> {
                if (node != null) {
                    outbounds.put(buildOutbound("proxy-out", node, baseSettings, "warp-out"))
                    val warpSource = if (primaryProto == ProxyProtocol.WIREGUARD) node else null
                    outbounds.put(buildWarpOutbound("warp-out", warpSource, effectiveSettings, baseHopTag))
                } else {
                    outbounds.put(buildWarpOutbound("proxy-out", null, effectiveSettings, baseHopTag))
                }
            }
            ChainingMode.PROXY_TO_WARP -> {
                val warpSource = if (relayNode != null && ProxyProtocol.parse(relayNode.protocol) == ProxyProtocol.WIREGUARD) {
                    relayNode
                } else {
                    null
                }
                val hasRelayProxy = node != null
                outbounds.put(buildWarpOutbound("proxy-out", warpSource, effectiveSettings, if (hasRelayProxy) "relay-proxy-out" else baseHopTag))
                if (node != null) {
                    val hop = effectiveTransportHopFor(nodeSecurity, primaryProto)
                    if (hop == "fragment-out") relayUsesFragment = true
                    outbounds.put(buildOutbound("relay-proxy-out", node, baseSettings, hop))
                }
            }
            ChainingMode.NODE_TO_NODE -> {
                val validRelay = if (relayNode != null && relayNode.id != node?.id) relayNode else null
                val relayProto = validRelay?.let { ProxyProtocol.parse(it.protocol) }
                if (node != null) {
                    val primaryDialer = if (validRelay != null) "relay-out" else effectiveTransportHopFor(nodeSecurity, primaryProto)
                    outbounds.put(buildOutbound("proxy-out", node, baseSettings, primaryDialer))
                }
                if (validRelay != null) {
                    val relayHop = effectiveTransportHopFor(relaySecurity, relayProto)
                    if (relayHop == "fragment-out") relayUsesFragment = true
                    outbounds.put(buildOutbound("relay-out", validRelay, baseSettings, relayHop))
                }
            }
            ChainingMode.NONE -> {
                if (node != null) {
                    if (!isExperimental && primaryProto != null && ProtocolGuardrails.isExperimentalProtocol(primaryProto)) {
                        outbounds.put(JSONObject().apply {
                            put("tag", "proxy-out")
                            put("protocol", "blackhole")
                            put("experimentalGated", true)
                        })
                    } else {
                        outbounds.put(buildOutbound("proxy-out", node, baseSettings, effectiveTransportHopFor(nodeSecurity, primaryProto)))
                    }
                }
            }
        }

        val emitFragmentOutbound = isExperimental &&
            (!isPrimaryDatagram || relayUsesFragment) &&
            (effectiveSettings.tlsFragmentEnabled || effectiveSettings.realityFragmentEnabled)

        if (emitFragmentOutbound) {
            outbounds.put(JSONObject().apply {
                put("tag", "fragment-out")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                    put("fragment", JSONObject().apply {
                        put("packets", effectiveSettings.fragmentPackets)
                        put("length", effectiveSettings.fragmentLength)
                        put("interval", effectiveSettings.fragmentInterval)
                        if (effectiveSettings.fragmentMaxSplit.isNotBlank()) put("maxSplit", effectiveSettings.fragmentMaxSplit)
                        if (effectiveSettings.fragmentHost.isNotBlank()) put("host", effectiveSettings.fragmentHost)
                    })
                })
                put("streamSettings", JSONObject().put("sockopt", buildSockopt(effectiveSettings, effectiveSettings.noiseEnabled, "noise-out", forceDisableTfo = true)))
            })
        }

        if (isExperimental && effectiveSettings.noiseEnabled) {
            val packetVal = when (effectiveSettings.noiseType) {
                "str" -> "GET / HTTP/1.1\r\nHost: www.cloudflare.com\r\n\r\n"
                "hex" -> "1603010020"
                "base64" -> "R0VUIC8gSFRUUC8xLjE="
                else -> effectiveSettings.noisePacketSize
            }
            outbounds.put(JSONObject().apply {
                put("tag", "noise-out")
                put("protocol", "freedom")
                put("settings", JSONObject().put("noise", JSONArray().put(JSONObject().apply {
                    put("type", effectiveSettings.noiseType.ifBlank { "rand" })
                    put("packet", packetVal)
                    put("delay", effectiveSettings.noiseDelayMs)
                    put("applyTo", effectiveSettings.noiseApplyTo.ifBlank { "ip" })
                })))
            })
        }

        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().put(
                "domainStrategy",
                freedomDomainStrategy(effectiveSettings.domainStrategy)
            ))
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })

        val compiledRules = JSONArray()
        if (effectiveSettings.blockQuicUdp) {
            compiledRules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("port", "443")
                put("outboundTag", "block")
            })
        }

        if (effectiveSettings.routingMode != RoutingMode.GLOBAL) {
            val orderedRules = GeoRulesEngine.sortRulesForCompilation(rules)
            for (rule in orderedRules) {
                val matchType = RuleMatchType.parse(rule.matchType)
                val action = RuleAction.parse(rule.action)
                if (effectiveSettings.routingMode == RoutingMode.BYPASS_LAN) {
                    val isPrivateRule = (matchType == RuleMatchType.GEOIP && rule.pattern.equals("private", ignoreCase = true)) ||
                        (matchType == RuleMatchType.IP_CIDR && GeoRulesEngine.isPrivateOrLanCidr(rule.pattern))
                    if (!isPrivateRule && action != RuleAction.BLOCK) continue
                }
                val targetTag = when (action) {
                    RuleAction.DIRECT -> "direct"
                    RuleAction.BLOCK -> "block"
                    RuleAction.PROXY -> "proxy-out"
                }
                val ruleJson = JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", targetTag)
                    when (matchType) {
                        RuleMatchType.IP_CIDR -> put("ip", JSONArray().put(rule.pattern.trim()))
                        RuleMatchType.GEOIP -> put("ip", JSONArray().put("geoip:${rule.pattern.trim().removePrefix("geoip:").lowercase()}"))
                        RuleMatchType.GEOSITE -> put("domain", JSONArray().put("geosite:${rule.pattern.trim().removePrefix("geosite:").lowercase()}"))
                        RuleMatchType.DOMAIN_KEYWORD -> put("domain", JSONArray().put("keyword:${rule.pattern.trim()}"))
                        RuleMatchType.DOMAIN_SUFFIX -> put("domain", JSONArray().put("domain:${rule.pattern.trim().removePrefix(".")}"))
                    }
                }
                compiledRules.put(ruleJson)
            }
        }
        compiledRules.put(JSONObject().apply {
            put("type", "field")
            put("network", "tcp,udp")
            put("outboundTag", "proxy-out")
        })

        val inboundTun = JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "luxy0")
                put("MTU", effectiveSettings.mtu.coerceIn(1280, 1500))
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", effectiveSettings.sniffingEnabled)
                put(
                    "destOverride",
                    JSONArray(
                        if (effectiveSettings.fakeDnsEnabled) listOf("http", "tls", "quic", "fakedns")
                        else listOf("http", "tls", "quic")
                    )
                )
            })
        }

        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            // Required for CoreController.queryAllOutboundTrafficStats()
            put("stats", JSONObject())
            put("policy", JSONObject().apply {
                put("levels", JSONObject().put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                }))
                put("system", JSONObject().apply {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            put("dns", JSONObject().apply {
                put("servers", JSONArray(dnsServers))
                put("queryStrategy", "UseIPv4")
                if (isExperimental) {
                    put("poolStrategy", effectiveSettings.dnsPoolStrategy.key)
                    put("integrityFilter", effectiveSettings.dnsIntegrityFilterEnabled)
                }
                put("disableFallback", false)
            })
            if (effectiveSettings.fakeDnsEnabled) {
                put("fakedns", JSONArray().put(JSONObject().apply {
                    put("ipPool", "198.18.0.0/15")
                    put("poolSize", 65535)
                }))
            }
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", effectiveSettings.sniffingEnabled)
                    put(
                        "destOverride",
                        JSONArray(
                            if (effectiveSettings.fakeDnsEnabled) listOf("http", "tls", "quic", "fakedns")
                            else listOf("http", "tls", "quic")
                        )
                    )
                    put("routeOnly", false)
                    put("metadataOnly", false)
                })
            }).put(inboundTun))
            put("outbounds", outbounds)
            put("routing", JSONObject().apply {
                put("domainStrategy", routingDomainStrategy(effectiveSettings.domainStrategy))
                put("domainMatcher", "hybrid")
                put("preFakeDnsEvaluation", true)
                put("strictUidIsolation", effectiveSettings.strictUidIsolationEnabled)
                put("rules", compiledRules)
            })
            put("experimentalMode", isExperimental)
        }
    }

    fun compileXrayConfig(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String = compileXrayConfigObject(node, settings, rules, relayNode).toString(2)

    fun compile(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String = compileXrayConfig(node, settings, rules, relayNode)

    fun compileRedactedForInspector(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String {
        val effective = settings.asEffectiveBaseline()
        val anyLocked = node?.isLocked == true || relayNode?.isLocked == true
        if (!anyLocked) return compileXrayConfig(node, effective, rules, relayNode)

        val redacted = EncryptedConfigVault.VAULT_REDACTED_VALUE
        fun maskProfile(src: ServerProfileEntity?): ServerProfileEntity? {
            if (src == null || !src.isLocked) return src
            return src.copy(
                address = redacted,
                port = 0,
                uuidOrPassword = redacted,
                sni = if (src.sni.isNotBlank()) redacted else "",
                hostHeader = if (src.hostHeader.isNotBlank()) redacted else "",
                publicKey = if (src.publicKey.isNotBlank()) redacted else "",
                shortId = if (src.shortId.isNotBlank()) "<LOCKED>" else "",
                echConfigList = if (src.echConfigList.isNotBlank()) redacted else "",
                mldsa65Verify = if (src.mldsa65Verify.isNotBlank()) redacted else "",
                path = if (src.path.isNotBlank()) "/<redacted>" else "",
                serviceName = if (src.serviceName.isNotBlank()) "<redacted>" else ""
            )
        }

        val safeSettings = if (anyLocked && effective.chainingMode != ChainingMode.NONE) {
            effective.copy(
                warpEndpoint = redacted,
                warpPrivateKey = redacted,
                warpPublicKey = redacted,
                warpReserved = "<LOCKED>"
            )
        } else {
            effective
        }

        val root = compileXrayConfigObject(maskProfile(node), safeSettings, rules, maskProfile(relayNode))
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val tag = ob.optString("tag", "")
            val proto = ob.optString("protocol", "")
            val shouldRedact = (tag == "proxy-out" && anyLocked) ||
                (tag == "relay-out" && relayNode?.isLocked == true) ||
                (tag == "relay-proxy-out" && node?.isLocked == true) ||
                (tag == "warp-out" && anyLocked)
            if (!shouldRedact) continue

            val pSettings = ob.optJSONObject("settings") ?: continue
            pSettings.optJSONArray("vnext")?.optJSONObject(0)?.put("port", "<LOCKED>")
            pSettings.optJSONArray("servers")?.optJSONObject(0)?.put("port", "<LOCKED>")
            if (pSettings.has("port")) pSettings.put("port", "<LOCKED>")

            when (proto) {
                "wireguard" -> {
                    pSettings.put("secretKey", redacted)
                    pSettings.put("reserved", JSONArray().put("<LOCKED>"))
                    val peers = pSettings.optJSONArray("peers")
                    if (peers != null) {
                        for (p in 0 until peers.length()) {
                            peers.optJSONObject(p)?.apply {
                                put("endpoint", redacted)
                                put("publicKey", redacted)
                            }
                        }
                    }
                }
                "masque" -> {
                    pSettings.put("privateKey", redacted)
                    pSettings.put("publicKey", redacted)
                    pSettings.put("endpoint", redacted)
                }
                "dnstt", "masterdns" -> {
                    pSettings.put("resolver", redacted)
                    pSettings.put("address", redacted)
                    pSettings.put("port", "<LOCKED>")
                    pSettings.put("domain", redacted)
                    pSettings.put("nameserver", redacted)
                    pSettings.put("publicKey", redacted)
                    if (pSettings.has("auth")) pSettings.put("auth", redacted)
                    if (pSettings.has("dohUrl")) pSettings.put("dohUrl", redacted)
                }
            }
        }
        root.put("vaultLockPolicy", "LOCKED_NO_REVEAL")
        return root.toString(2)
    }
}
