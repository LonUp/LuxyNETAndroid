package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ProtocolFormKind
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType

data class ProtocolFieldSpec(
    val kind: ProtocolFormKind,
    val isDatagram: Boolean,
    val supportedTransports: List<TransportType>,
    val supportedSecurities: List<SecurityType>,
    val showUuid: Boolean,
    val showPassword: Boolean,
    val secretLabel: String,
    val showCipherMethod: Boolean,
    val cipherMethodOptions: List<String>,
    val showFlow: Boolean,
    val showPathAndHost: Boolean,
    val showGrpcServiceName: Boolean,
    val showXhttpNotice: Boolean,
    val showTlsFields: Boolean,
    val showAllowInsecure: Boolean,
    val showCipherSuitesAndEch: Boolean,
    val showRealityFields: Boolean,
    val showWireGuardFields: Boolean,
    val showAwgFields: Boolean,
    val showMasqueFields: Boolean,
    val showDnsTunnelFields: Boolean,
    val showHysteriaFields: Boolean,
    val showFinalMaskField: Boolean
)

data class NodeDraftInput(
    val kind: ProtocolFormKind = ProtocolFormKind.VLESS,
    val name: String = "",
    val address: String = "",
    val port: String = "443",
    val uuidOrPassword: String = "",
    val encryption: String = "none",
    val transport: TransportType = TransportType.TCP,
    val security: SecurityType = SecurityType.TLS,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val flow: String = "",
    val allowInsecure: Boolean = false,
    val cipherSuites: String = "",
    val finalMask: String = "",
    val echConfigList: String = "",
    val mldsa65Verify: String = "",
    val dnsTransportMode: String = "udp",
    val wgMtu: String = "",
    val awgJc: String = "4",
    val awgJmin: String = "40",
    val awgJmax: String = "70",
    val awgS1: String = "0",
    val awgS2: String = "0",
    val awgH1: String = "1",
    val awgH2: String = "2",
    val awgH3: String = "3",
    val awgH4: String = "4"
)

data class NodeValidationResult(
    val errors: Map<String, String> = emptyMap()
) {
    val isValid: Boolean get() = errors.isEmpty()
}

data class SettingsCompatibilityReport(
    val effectiveSettings: StabilitySettings,
    val notices: List<String>,
    val isDatagramNode: Boolean,
    val isRealityNode: Boolean,
    val isWarpChainingAllowed: Boolean
)

object ProtocolGuardrails {
    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HOST_NO_SPACE_REGEX = Regex("^[^\\s/]+$")
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

    private val DATAGRAM_PROTOCOLS = setOf(
        ProxyProtocol.WIREGUARD,
        ProxyProtocol.HYSTERIA2,
        ProxyProtocol.MASQUE,
        ProxyProtocol.DNSTT,
        ProxyProtocol.MASTERDNS
    )

    private val WARP_CHAINING_BLOCKED_PROTOCOLS = setOf(
        ProxyProtocol.WIREGUARD,
        ProxyProtocol.MASQUE,
        ProxyProtocol.DNSTT,
        ProxyProtocol.MASTERDNS
    )

    private val EXPERIMENTAL_PROTOCOLS = setOf(
        ProxyProtocol.MASQUE,
        ProxyProtocol.DNSTT,
        ProxyProtocol.MASTERDNS
    )

    /**
     * Protocols the bundled core has no outbound for. Evidence: `strings libgojni.so` contains no
     * `tuic`/`anytls`/`ssh`/`openconnect`/`openvpn`/`mieru`/`dnstt`/`masterdns` symbols. Emitting
     * them produced configs the parser rejected, so the compiler refuses before building anything.
     */
    private val UNCOMPILED_PROTOCOLS = setOf(
        ProxyProtocol.TUIC,
        ProxyProtocol.ANYTLS,
        ProxyProtocol.SSH,
        ProxyProtocol.OPENCONNECT,
        ProxyProtocol.OPENVPN,
        ProxyProtocol.MIERU,
        ProxyProtocol.HYSTERIA,
        ProxyProtocol.DNSTT,
        ProxyProtocol.MASTERDNS
    )

    fun isCompiledByCore(protocol: ProxyProtocol): Boolean = protocol !in UNCOMPILED_PROTOCOLS

    fun uncompiledProtocolMessage(protocol: ProxyProtocol): String =
        "${protocol.label} is not compiled by the bundled Xray core yet. " +
            "The profile is stored; connecting is disabled."

    fun isExperimentalProtocol(protocol: ProxyProtocol): Boolean = EXPERIMENTAL_PROTOCOLS.contains(protocol)

    fun isExperimentalNode(node: ServerProfileEntity?): Boolean {
        if (node == null) return false
        val proto = ProxyProtocol.parse(node.protocol)
        if (isExperimentalProtocol(proto)) return true
        return protocolFormKindFromProfile(node) == ProtocolFormKind.AMNEZIA_WG
    }

    fun isStandardProtocolKind(kind: ProtocolFormKind): Boolean = kind in setOf(
        ProtocolFormKind.VLESS,
        ProtocolFormKind.VMESS,
        ProtocolFormKind.TROJAN,
        ProtocolFormKind.SHADOWSOCKS,
        ProtocolFormKind.SOCKS,
        ProtocolFormKind.HYSTERIA2,
        ProtocolFormKind.WIREGUARD
    )

    fun protocolFormKindFromProfile(profile: ServerProfileEntity?): ProtocolFormKind {
        if (profile == null) return ProtocolFormKind.VLESS
        return when (ProxyProtocol.parse(profile.protocol)) {
            ProxyProtocol.VLESS -> ProtocolFormKind.VLESS
            ProxyProtocol.VMESS -> ProtocolFormKind.VMESS
            ProxyProtocol.TROJAN -> ProtocolFormKind.TROJAN
            ProxyProtocol.SHADOWSOCKS -> ProtocolFormKind.SHADOWSOCKS
            ProxyProtocol.SOCKS -> ProtocolFormKind.SOCKS
            ProxyProtocol.HYSTERIA2, ProxyProtocol.HYSTERIA -> ProtocolFormKind.HYSTERIA2
            ProxyProtocol.TUIC, ProxyProtocol.ANYTLS, ProxyProtocol.SSH,
            ProxyProtocol.OPENCONNECT, ProxyProtocol.OPENVPN, ProxyProtocol.HTTP,
            ProxyProtocol.MIERU -> ProtocolFormKind.VLESS
            ProxyProtocol.WIREGUARD -> {
                val hasAwg = profile.finalMask.trim().equals("awg", ignoreCase = true) ||
                    ProxyUriParser.parseAwgQuery(profile.serviceName) != null
                if (hasAwg) ProtocolFormKind.AMNEZIA_WG else ProtocolFormKind.WIREGUARD
            }
            ProxyProtocol.MASQUE -> ProtocolFormKind.MASQUE
            ProxyProtocol.DNSTT -> ProtocolFormKind.DNSTT
            ProxyProtocol.MASTERDNS -> ProtocolFormKind.MASTERDNS
        }
    }

    fun isDatagramProtocol(protocol: ProxyProtocol): Boolean = DATAGRAM_PROTOCOLS.contains(protocol)

    fun isDatagramNode(node: ServerProfileEntity?): Boolean =
        node != null && isDatagramProtocol(ProxyProtocol.parse(node.protocol))

    fun isWarpChainingAllowed(node: ServerProfileEntity?): Boolean =
        node == null || !WARP_CHAINING_BLOCKED_PROTOCOLS.contains(ProxyProtocol.parse(node.protocol))

    fun defaultPortFor(kind: ProtocolFormKind, dnsMode: String = "udp"): Int = when (kind) {
        ProtocolFormKind.SOCKS -> 1080
        ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG -> 2408
        ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> when (dnsMode.lowercase()) {
            "doh" -> 443
            "dot" -> 853
            else -> 53
        }
        else -> 443
    }

    fun defaultTransportFor(kind: ProtocolFormKind): TransportType = when (kind) {
        ProtocolFormKind.HYSTERIA2 -> TransportType.QUIC
        ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG -> TransportType.WG
        ProtocolFormKind.MASQUE -> TransportType.MASQUE
        ProtocolFormKind.DNSTT -> TransportType.DNSTT
        ProtocolFormKind.MASTERDNS -> TransportType.MASTERDNS
        else -> TransportType.TCP
    }

    fun defaultSecurityFor(kind: ProtocolFormKind): SecurityType = when (kind) {
        ProtocolFormKind.TROJAN, ProtocolFormKind.HYSTERIA2, ProtocolFormKind.MASQUE,
        ProtocolFormKind.VLESS, ProtocolFormKind.VMESS -> SecurityType.TLS
        else -> SecurityType.NONE
    }

    fun fieldSpecFor(
        kind: ProtocolFormKind,
        requestedTransport: TransportType,
        requestedSecurity: SecurityType,
        experimentalModeEnabled: Boolean = true
    ): ProtocolFieldSpec {
        val supportedTransports = when (kind) {
            ProtocolFormKind.VLESS, ProtocolFormKind.VMESS, ProtocolFormKind.TROJAN ->
                listOf(TransportType.TCP, TransportType.WS, TransportType.XHTTP, TransportType.GRPC, TransportType.HTTPUPGRADE)
            ProtocolFormKind.SHADOWSOCKS, ProtocolFormKind.SOCKS -> listOf(TransportType.TCP)
            ProtocolFormKind.HYSTERIA2 -> listOf(TransportType.QUIC)
            ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG -> listOf(TransportType.WG)
            ProtocolFormKind.MASQUE -> listOf(TransportType.MASQUE)
            ProtocolFormKind.DNSTT -> listOf(TransportType.DNSTT)
            ProtocolFormKind.MASTERDNS -> listOf(TransportType.MASTERDNS)
        }
        val supportedSecurities = when (kind) {
            ProtocolFormKind.VLESS -> listOf(SecurityType.NONE, SecurityType.TLS, SecurityType.REALITY)
            ProtocolFormKind.VMESS -> listOf(SecurityType.NONE, SecurityType.TLS)
            ProtocolFormKind.TROJAN -> listOf(SecurityType.TLS, SecurityType.REALITY)
            ProtocolFormKind.HYSTERIA2, ProtocolFormKind.MASQUE -> listOf(SecurityType.TLS)
            else -> listOf(SecurityType.NONE)
        }

        val transport = if (supportedTransports.contains(requestedTransport)) requestedTransport else supportedTransports.first()
        val security = if (supportedSecurities.contains(requestedSecurity)) requestedSecurity else supportedSecurities.first()

        val isTcpLikeStream = kind == ProtocolFormKind.VLESS || kind == ProtocolFormKind.VMESS || kind == ProtocolFormKind.TROJAN
        val showPathAndHost = isTcpLikeStream && (transport == TransportType.WS || transport == TransportType.XHTTP || transport == TransportType.HTTPUPGRADE)
        val showGrpcServiceName = isTcpLikeStream && transport == TransportType.GRPC
        val showXhttpNotice = isTcpLikeStream && transport == TransportType.XHTTP
        val isTls = security == SecurityType.TLS && isTcpLikeStream
        val isReality = security == SecurityType.REALITY && (kind == ProtocolFormKind.VLESS || kind == ProtocolFormKind.TROJAN)

        return ProtocolFieldSpec(
            kind = kind,
            isDatagram = isDatagramProtocol(kind.underlying),
            supportedTransports = supportedTransports,
            supportedSecurities = supportedSecurities,
            showUuid = kind == ProtocolFormKind.VLESS || kind == ProtocolFormKind.VMESS,
            showPassword = kind in setOf(ProtocolFormKind.TROJAN, ProtocolFormKind.SHADOWSOCKS, ProtocolFormKind.SOCKS, ProtocolFormKind.HYSTERIA2),
            secretLabel = when (kind) {
                ProtocolFormKind.SOCKS -> "Username / Password (optional)"
                ProtocolFormKind.HYSTERIA2 -> "Hysteria2 Auth Secret"
                else -> "Password"
            },
            showCipherMethod = kind == ProtocolFormKind.SHADOWSOCKS || kind == ProtocolFormKind.VMESS,
            cipherMethodOptions = when (kind) {
                ProtocolFormKind.SHADOWSOCKS -> listOf("aes-256-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-256-gcm", "aes-128-gcm")
                ProtocolFormKind.VMESS -> listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")
                else -> emptyList()
            },
            showFlow = kind == ProtocolFormKind.VLESS,
            showPathAndHost = showPathAndHost,
            showGrpcServiceName = showGrpcServiceName,
            showXhttpNotice = showXhttpNotice,
            showTlsFields = isTls,
            showAllowInsecure = isTls || kind == ProtocolFormKind.HYSTERIA2 || kind == ProtocolFormKind.MASQUE,
            showCipherSuitesAndEch = experimentalModeEnabled && isTls,
            showRealityFields = isReality,
            showWireGuardFields = kind == ProtocolFormKind.WIREGUARD || kind == ProtocolFormKind.AMNEZIA_WG,
            showAwgFields = experimentalModeEnabled && kind == ProtocolFormKind.AMNEZIA_WG,
            showMasqueFields = experimentalModeEnabled && kind == ProtocolFormKind.MASQUE,
            showDnsTunnelFields = experimentalModeEnabled && (kind == ProtocolFormKind.DNSTT || kind == ProtocolFormKind.MASTERDNS),
            showHysteriaFields = kind == ProtocolFormKind.HYSTERIA2,
            showFinalMaskField = experimentalModeEnabled && kind in setOf(
                ProtocolFormKind.VLESS, ProtocolFormKind.VMESS, ProtocolFormKind.TROJAN,
                ProtocolFormKind.HYSTERIA2, ProtocolFormKind.MASQUE, ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG
            )
        )
    }

    fun isValidUuid(value: String): Boolean = UUID_REGEX.matches(value.trim())

    fun isValidWireGuardKey(value: String): Boolean {
        val clean = value.trim()
        if (clean.length !in 43..44) return false
        return try {
            Base64Compat.decode(clean).size == 32
        } catch (_: Exception) {
            false
        }
    }

    fun validateNodeDraft(draft: NodeDraftInput): NodeValidationResult {
        val errors = linkedMapOf<String, String>()
        val cleanAddr = draft.address.trim()
        if (cleanAddr.isEmpty() || !HOST_NO_SPACE_REGEX.matches(cleanAddr)) {
            errors["address"] = "Enter a valid hostname or IP address"
        }
        val portNum = draft.port.trim().toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            errors["port"] = "Port must be between 1 and 65535"
        }

        val spec = fieldSpecFor(draft.kind, draft.transport, draft.security)
        val effectiveSec = if (spec.supportedSecurities.contains(draft.security)) draft.security else spec.supportedSecurities.first()

        when (draft.kind) {
            ProtocolFormKind.VLESS, ProtocolFormKind.VMESS -> {
                if (!isValidUuid(draft.uuidOrPassword)) {
                    errors["uuidOrPassword"] = "Enter a valid RFC 4122 UUID"
                }
            }
            ProtocolFormKind.TROJAN, ProtocolFormKind.SHADOWSOCKS, ProtocolFormKind.HYSTERIA2 -> {
                if (draft.uuidOrPassword.trim().isEmpty()) {
                    errors["uuidOrPassword"] = "${spec.secretLabel} is required"
                }
            }
            ProtocolFormKind.SOCKS -> Unit
            ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG -> {
                if (!isValidWireGuardKey(draft.uuidOrPassword)) {
                    errors["uuidOrPassword"] = "Private key must be a valid 32-byte Base64 WireGuard key"
                }
                if (!isValidWireGuardKey(draft.publicKey)) {
                    errors["publicKey"] = "Peer public key must be a valid 32-byte Base64 WireGuard key"
                }
                if (draft.wgMtu.isNotBlank()) {
                    val mtu = draft.wgMtu.trim().toIntOrNull()
                    if (mtu == null || mtu !in 576..1500) errors["wgMtu"] = "MTU must be 576–1500"
                }
                if (draft.kind == ProtocolFormKind.AMNEZIA_WG) {
                    val jmin = draft.awgJmin.trim().toIntOrNull()
                    val jmax = draft.awgJmax.trim().toIntOrNull()
                    if (jmin == null || jmax == null || jmin < 0 || jmax < jmin || jmax > 1280) {
                        errors["awgJunk"] = "AmneziaWG requires 0 <= Jmin <= Jmax <= 1280"
                    }
                }
            }
            ProtocolFormKind.MASQUE -> {
                if (draft.uuidOrPassword.trim().isEmpty()) {
                    errors["uuidOrPassword"] = "MASQUE private key or auth token is required"
                }
                if (draft.publicKey.trim().isEmpty()) {
                    errors["publicKey"] = "MASQUE peer/server public key is required"
                }
            }
            ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> {
                if (draft.hostHeader.trim().isEmpty()) {
                    errors["hostHeader"] = "Tunnel domain / NS hostname is required"
                }
                val pub = draft.publicKey.ifEmpty { draft.uuidOrPassword }.trim()
                if (pub.isEmpty()) {
                    errors["publicKey"] = "DNS tunnel public key is required"
                }
                if (draft.dnsTransportMode.equals("doh", ignoreCase = true) && draft.path.trim().isEmpty()) {
                    errors["path"] = "DoH URL is required when DNS mode is DoH"
                }
            }
        }

        if (effectiveSec == SecurityType.REALITY) {
            if (draft.sni.trim().isEmpty()) errors["sni"] = "REALITY requires a valid target SNI domain"
            if (draft.publicKey.trim().isEmpty()) errors["publicKey"] = "REALITY requires Public Key (pbk)"
            val cleanSid = draft.shortId.trim()
            if (cleanSid.isEmpty() || cleanSid.length > 16 || cleanSid.length % 2 != 0 || !HEX_REGEX.matches(cleanSid)) {
                errors["shortId"] = "REALITY Short ID must be even-length hex (2–16 chars)"
            }
        }

        return NodeValidationResult(errors)
    }

    fun enforceFragmentVsTfo(
        tlsFragmentEnabled: Boolean,
        tcpFastOpen: Boolean,
        lastChangedIsFragment: Boolean
    ): Pair<Boolean, Boolean> {
        if (!tlsFragmentEnabled || !tcpFastOpen) return tlsFragmentEnabled to tcpFastOpen
        return if (lastChangedIsFragment) true to false else false to true
    }

    fun sanitizeChaining(
        mode: ChainingMode,
        relayNodeId: String?,
        activeNode: ServerProfileEntity?
    ): Pair<ChainingMode, String?> {
        if ((mode == ChainingMode.WARP_TO_PROXY || mode == ChainingMode.PROXY_TO_WARP) &&
            !isWarpChainingAllowed(activeNode)
        ) {
            return ChainingMode.NONE to null
        }
        val cleanRelay = if (mode == ChainingMode.NODE_TO_NODE || mode == ChainingMode.PROXY_TO_WARP) {
            if (!relayNodeId.isNullOrBlank() && relayNodeId != activeNode?.id) relayNodeId else null
        } else {
            null
        }
        return mode to cleanRelay
    }

    fun evaluateAndSanitizeSettings(
        settings: StabilitySettings,
        activeNode: ServerProfileEntity? = null,
        preferFragmentOverTfo: Boolean = true
    ): SettingsCompatibilityReport {
        val notices = mutableListOf<String>()
        val isDatagram = isDatagramNode(activeNode)
        val isReality = activeNode != null && SecurityType.parse(activeNode.security) == SecurityType.REALITY
        val warpAllowed = settings.experimentalModeEnabled && isWarpChainingAllowed(activeNode)

        var effective = settings.asEffectiveBaseline()
        if (!effective.experimentalModeEnabled) {
            return SettingsCompatibilityReport(
                effectiveSettings = effective,
                notices = emptyList(),
                isDatagramNode = isDatagram,
                isRealityNode = isReality,
                isWarpChainingAllowed = false
            )
        }

        if (effective.tlsFragmentEnabled && effective.tcpFastOpen) {
            val (frag, tfo) = enforceFragmentVsTfo(true, true, preferFragmentOverTfo)
            effective = effective.copy(
                tlsFragmentEnabled = frag,
                realityFragmentEnabled = if (frag) effective.realityFragmentEnabled else false,
                tcpFastOpen = tfo
            )
            notices += "TLS Fragmentation and TCP Fast Open are mutually exclusive."
        }

        if (isReality) {
            effective = effective.copy(
                mixedCaseSni = false,
                echEnabled = false,
                echConfigList = "",
                echForceQuery = "none",
                failClosedEch = false,
                cipherSuites = ""
            )
            notices += "REALITY ignores CipherSuites, ECH, Mixed-Case SNI, and AllowInsecure."
        }

        if (isDatagram) {
            effective = effective.copy(
                tlsFragmentEnabled = false,
                realityFragmentEnabled = false,
                tcpFastOpen = false,
                muxEnabled = false,
                mixedCaseSni = false
            )
            notices += "${activeNode?.protocol} is datagram/UDP-based: TCP Fragmentation, TFO, Mux, and Mixed-Case SNI are disabled."
        }

        val (safeChain, safeRelay) = sanitizeChaining(effective.chainingMode, effective.relayNodeId, activeNode)
        if (safeChain != effective.chainingMode || safeRelay != effective.relayNodeId) {
            effective = effective.copy(chainingMode = safeChain, relayNodeId = safeRelay)
            notices += if (!warpAllowed) {
                "WARP/Proxy chaining is unavailable while ${activeNode?.protocol} is active."
            } else {
                "Removed invalid self-relay hop from multi-hop chain."
            }
        }

        return SettingsCompatibilityReport(
            effectiveSettings = effective,
            notices = notices,
            isDatagramNode = isDatagram,
            isRealityNode = isReality,
            isWarpChainingAllowed = warpAllowed
        )
    }

    fun sanitizeProfileForSave(profile: ServerProfileEntity): ServerProfileEntity {
        val kind = protocolFormKindFromProfile(profile)
        val parsedTransport = TransportType.parse(profile.transport)
        val parsedSecurity = SecurityType.parse(profile.security)
        val spec = fieldSpecFor(kind, parsedTransport, parsedSecurity)
        val safeTransport = if (spec.supportedTransports.contains(parsedTransport)) parsedTransport else spec.supportedTransports.first()
        val safeSecurity = if (spec.supportedSecurities.contains(parsedSecurity)) parsedSecurity else spec.supportedSecurities.first()

        val cleanAddress = profile.address.trim()
        val cleanName = profile.name.trim().ifEmpty { "$cleanAddress:${profile.port}" }

        return profile.copy(
            protocol = kind.underlying.name,
            address = cleanAddress,
            name = cleanName,
            transport = safeTransport.name,
            security = safeSecurity.name,
            flow = if (spec.showFlow || spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.flow.trim() else "",
            path = if (spec.showPathAndHost || spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.path.trim() else "",
            hostHeader = if (spec.showPathAndHost || spec.showDnsTunnelFields) profile.hostHeader.trim() else "",
            serviceName = if (spec.showGrpcServiceName || spec.showAwgFields || spec.showDnsTunnelFields) profile.serviceName.trim() else "",
            allowInsecure = if (safeSecurity == SecurityType.REALITY) false else if (spec.showAllowInsecure) profile.allowInsecure else false,
            cipherSuites = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.cipherSuites.trim() else "",
            echConfigList = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.echConfigList.trim() else "",
            echForceQuery = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.echForceQuery else "none",
            publicKey = if (spec.showRealityFields || spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.publicKey.trim() else "",
            shortId = if (spec.showRealityFields || spec.showWireGuardFields) profile.shortId.trim() else "",
            mldsa65Verify = if (spec.showRealityFields) profile.mldsa65Verify.trim() else ""
        )
    }
}
