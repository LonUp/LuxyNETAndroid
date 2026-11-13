package com.example.core

import android.util.Base64
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType

enum class ProtocolFormKind(val label: String, val underlying: ProxyProtocol) {
    VLESS("VLESS", ProxyProtocol.VLESS),
    VMESS("VMess", ProxyProtocol.VMESS),
    TROJAN("Trojan", ProxyProtocol.TROJAN),
    SHADOWSOCKS("Shadowsocks", ProxyProtocol.SHADOWSOCKS),
    SOCKS("SOCKS5", ProxyProtocol.SOCKS),
    HYSTERIA2("Hysteria2", ProxyProtocol.HYSTERIA2),
    WIREGUARD("WireGuard", ProxyProtocol.WIREGUARD),
    AMNEZIA_WG("AmneziaWG", ProxyProtocol.WIREGUARD),
    MASQUE("MASQUE", ProxyProtocol.MASQUE),
    DNSTT("DNSTT", ProxyProtocol.DNSTT),
    MASTERDNS("MasterDNS", ProxyProtocol.MASTERDNS);

    companion object {
        fun fromProfile(profile: ServerProfileEntity?): ProtocolFormKind {
            if (profile == null) return VLESS
            return when (ProxyProtocol.fromString(profile.protocol)) {
                ProxyProtocol.VLESS -> VLESS
                ProxyProtocol.VMESS -> VMESS
                ProxyProtocol.TROJAN -> TROJAN
                ProxyProtocol.SHADOWSOCKS -> SHADOWSOCKS
                ProxyProtocol.SOCKS -> SOCKS
                ProxyProtocol.HYSTERIA2 -> HYSTERIA2
                ProxyProtocol.WIREGUARD -> {
                    val hasAwg = profile.finalMask.equals("awg", ignoreCase = true) ||
                        ProxyUriParser.parseAwgQuery(profile.serviceName) != null
                    if (hasAwg) AMNEZIA_WG else WIREGUARD
                }
                ProxyProtocol.MASQUE -> MASQUE
                ProxyProtocol.DNSTT -> DNSTT
                ProxyProtocol.MASTERDNS -> MASTERDNS
            }
        }
    }
}

data class ProtocolFieldSpec(
    val kind: ProtocolFormKind,
    val isDatagram: Boolean,
    val supportedTransports: List<TransportType>,
    val supportedSecurities: List<SecurityType>,
    val showUuid: Boolean = false,
    val showPassword: Boolean = false,
    val secretLabel: String = "Password",
    val showCipherMethod: Boolean = false,
    val cipherMethodOptions: List<String> = emptyList(),
    val showFlow: Boolean = false,
    val showPathAndHost: Boolean = false,
    val showGrpcServiceName: Boolean = false,
    val showXhttpNotice: Boolean = false,
    val showTlsFields: Boolean = false,
    val showAllowInsecure: Boolean = false,
    val showCipherSuitesAndEch: Boolean = false,
    val showRealityFields: Boolean = false,
    val showWireGuardFields: Boolean = false,
    val showAwgFields: Boolean = false,
    val showMasqueFields: Boolean = false,
    val showDnsTunnelFields: Boolean = false,
    val showHysteriaFields: Boolean = false,
    val showFinalMaskField: Boolean = false
)

data class NodeDraftInput(
    val kind: ProtocolFormKind,
    val name: String,
    val address: String,
    val port: String,
    val uuidOrPassword: String,
    val encryption: String,
    val transport: TransportType,
    val security: SecurityType,
    val sni: String,
    val alpn: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val path: String,
    val hostHeader: String,
    val serviceName: String,
    val flow: String,
    val allowInsecure: Boolean,
    val cipherSuites: String,
    val finalMask: String,
    val echConfigList: String,
    val mldsa65Verify: String,
    val dnsTransportMode: String = "udp",
    val wgMtu: String = "1420",
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
    val notices: List<String> = emptyList(),
    val isDatagramNode: Boolean = false,
    val isRealityNode: Boolean = false,
    val isWarpChainingAllowed: Boolean = true
)

object ProtocolGuardrails {

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
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

    fun isDatagramProtocol(protocol: ProxyProtocol): Boolean = protocol in DATAGRAM_PROTOCOLS

    fun isDatagramNode(node: ServerProfileEntity?): Boolean =
        node != null && isDatagramProtocol(ProxyProtocol.fromString(node.protocol))

    fun isWarpChainingAllowed(node: ServerProfileEntity?): Boolean =
        node == null || ProxyProtocol.fromString(node.protocol) !in WARP_CHAINING_BLOCKED_PROTOCOLS

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
        ProtocolFormKind.TROJAN, ProtocolFormKind.HYSTERIA2, ProtocolFormKind.MASQUE -> SecurityType.TLS
        ProtocolFormKind.SHADOWSOCKS, ProtocolFormKind.SOCKS,
        ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG,
        ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> SecurityType.NONE
        ProtocolFormKind.VLESS, ProtocolFormKind.VMESS -> SecurityType.TLS
    }

    fun fieldSpecFor(
        kind: ProtocolFormKind,
        requestedTransport: TransportType,
        requestedSecurity: SecurityType
    ): ProtocolFieldSpec {
        val supportedTransports = when (kind) {
            ProtocolFormKind.VLESS, ProtocolFormKind.VMESS, ProtocolFormKind.TROJAN -> listOf(
                TransportType.TCP,
                TransportType.WS,
                TransportType.XHTTP,
                TransportType.GRPC,
                TransportType.HTTPUPGRADE
            )
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
            ProtocolFormKind.SHADOWSOCKS, ProtocolFormKind.SOCKS,
            ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG,
            ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> listOf(SecurityType.NONE)
            ProtocolFormKind.HYSTERIA2, ProtocolFormKind.MASQUE -> listOf(SecurityType.TLS)
        }

        val transport = if (requestedTransport in supportedTransports) requestedTransport else supportedTransports.first()
        val security = if (requestedSecurity in supportedSecurities) requestedSecurity else supportedSecurities.first()

        val isTcpLikeStream = kind in setOf(ProtocolFormKind.VLESS, ProtocolFormKind.VMESS, ProtocolFormKind.TROJAN)
        val showPathAndHost = isTcpLikeStream && transport in setOf(
            TransportType.WS,
            TransportType.XHTTP,
            TransportType.HTTPUPGRADE
        )
        val showGrpcServiceName = isTcpLikeStream && transport == TransportType.GRPC
        val showXhttpNotice = isTcpLikeStream && transport == TransportType.XHTTP

        val isTls = security == SecurityType.TLS && kind in setOf(
            ProtocolFormKind.VLESS,
            ProtocolFormKind.VMESS,
            ProtocolFormKind.TROJAN
        )
        val isReality = security == SecurityType.REALITY && kind in setOf(
            ProtocolFormKind.VLESS,
            ProtocolFormKind.TROJAN
        )

        return ProtocolFieldSpec(
            kind = kind,
            isDatagram = isDatagramProtocol(kind.underlying),
            supportedTransports = supportedTransports,
            supportedSecurities = supportedSecurities,
            showUuid = kind == ProtocolFormKind.VLESS || kind == ProtocolFormKind.VMESS,
            showPassword = kind in setOf(
                ProtocolFormKind.TROJAN,
                ProtocolFormKind.SHADOWSOCKS,
                ProtocolFormKind.SOCKS,
                ProtocolFormKind.HYSTERIA2
            ),
            secretLabel = when (kind) {
                ProtocolFormKind.SOCKS -> "Username / Password (optional)"
                ProtocolFormKind.HYSTERIA2 -> "Hysteria2 Auth Secret"
                else -> "Password"
            },
            showCipherMethod = kind == ProtocolFormKind.SHADOWSOCKS || kind == ProtocolFormKind.VMESS,
            cipherMethodOptions = when (kind) {
                ProtocolFormKind.SHADOWSOCKS -> listOf(
                    "aes-256-gcm",
                    "chacha20-ietf-poly1305",
                    "2022-blake3-aes-256-gcm",
                    "aes-128-gcm"
                )
                ProtocolFormKind.VMESS -> listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")
                else -> emptyList()
            },
            showFlow = kind == ProtocolFormKind.VLESS,
            showPathAndHost = showPathAndHost,
            showGrpcServiceName = showGrpcServiceName,
            showXhttpNotice = showXhttpNotice,
            showTlsFields = isTls,
            showAllowInsecure = isTls || kind == ProtocolFormKind.HYSTERIA2 || kind == ProtocolFormKind.MASQUE,
            showCipherSuitesAndEch = isTls,
            showRealityFields = isReality,
            showWireGuardFields = kind == ProtocolFormKind.WIREGUARD || kind == ProtocolFormKind.AMNEZIA_WG,
            showAwgFields = kind == ProtocolFormKind.AMNEZIA_WG,
            showMasqueFields = kind == ProtocolFormKind.MASQUE,
            showDnsTunnelFields = kind == ProtocolFormKind.DNSTT || kind == ProtocolFormKind.MASTERDNS,
            showHysteriaFields = kind == ProtocolFormKind.HYSTERIA2,
            showFinalMaskField = kind in setOf(
                ProtocolFormKind.VLESS,
                ProtocolFormKind.VMESS,
                ProtocolFormKind.TROJAN,
                ProtocolFormKind.HYSTERIA2,
                ProtocolFormKind.MASQUE,
                ProtocolFormKind.WIREGUARD,
                ProtocolFormKind.AMNEZIA_WG
            )
        )
    }

    fun isValidUuid(value: String): Boolean = UUID_REGEX.matches(value.trim())

    fun isValidWireGuardKey(value: String): Boolean {
        val clean = value.trim()
        if (clean.length !in 43..44) return false
        return try {
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            decoded.size == 32
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
        val effectiveSec = if (draft.security in spec.supportedSecurities) draft.security else spec.supportedSecurities.first()

        when (draft.kind) {
            ProtocolFormKind.VLESS, ProtocolFormKind.VMESS -> {
                if (!isValidUuid(draft.uuidOrPassword)) {
                    errors["uuidOrPassword"] = "Enter a valid RFC 4122 UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)"
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
                val mtu = draft.wgMtu.trim().toIntOrNull()
                if (draft.wgMtu.isNotBlank() && (mtu == null || mtu !in 576..1500)) {
                    errors["wgMtu"] = "MTU must be 576–1500"
                }
                if (draft.kind == ProtocolFormKind.AMNEZIA_WG) {
                    val jmin = draft.awgJmin.trim().toIntOrNull() ?: -1
                    val jmax = draft.awgJmax.trim().toIntOrNull() ?: -1
                    if (jmin < 0 || jmax < jmin || jmax > 1280) {
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
                val pub = draft.publicKey.ifBlank { draft.uuidOrPassword }.trim()
                if (pub.isEmpty()) {
                    errors["publicKey"] = "DNS tunnel public key is required"
                }
                if (draft.dnsTransportMode.equals("doh", ignoreCase = true) && draft.path.trim().isEmpty()) {
                    errors["path"] = "DoH URL is required when DNS mode is DoH"
                }
            }
        }

        if (effectiveSec == SecurityType.REALITY) {
            if (draft.sni.trim().isEmpty()) {
                errors["sni"] = "REALITY requires a valid target SNI domain"
            }
            if (draft.publicKey.trim().isEmpty()) {
                errors["publicKey"] = "REALITY requires Public Key (pbk)"
            }
            val cleanSid = draft.shortId.trim()
            if (cleanSid.isEmpty() || cleanSid.length > 16 || cleanSid.length % 2 != 0 || !HEX_REGEX.matches(cleanSid)) {
                errors["shortId"] = "REALITY Short ID must be even-length hex (2–16 chars)"
            }
        }

        return NodeValidationResult(errors)
    }

    /**
     * Enforces mutual exclusion between TLS ClientHello Fragmentation and TCP Fast Open (TFO).
     * Enabling [enableFragment] clears TFO; enabling [enableTfo] clears Fragmentation.
     */
    fun enforceFragmentVsTfo(
        tlsFragmentEnabled: Boolean,
        tcpFastOpen: Boolean,
        lastChangedIsFragment: Boolean
    ): Pair<Boolean, Boolean> {
        if (!tlsFragmentEnabled || !tcpFastOpen) return tlsFragmentEnabled to tcpFastOpen
        return if (lastChangedIsFragment) {
            true to false
        } else {
            false to true
        }
    }

    /**
     * Prevents NODE_TO_NODE self-chaining and WARP_TO_PROXY / PROXY_TO_WARP recursion on
     * datagram/DNS tunnel primary nodes.
     */
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
            relayNodeId?.takeIf { it.isNotBlank() && it != activeNode?.id }
        } else {
            null
        }
        return mode to cleanRelay
    }

    /**
     * Produces sanitized [StabilitySettings] respecting all mutual-exclusion and protocol rules,
     * plus human-readable compatibility notices for Expert Lab UI.
     */
    fun evaluateAndSanitizeSettings(
        settings: StabilitySettings,
        activeNode: ServerProfileEntity?,
        preferFragmentOverTfo: Boolean = true
    ): SettingsCompatibilityReport {
        val notices = mutableListOf<String>()
        val isDatagram = isDatagramNode(activeNode)
        val isReality = activeNode != null && SecurityType.fromString(activeNode.security) == SecurityType.REALITY
        val warpAllowed = isWarpChainingAllowed(activeNode)

        var effective = settings

        // Rule 1: TLS Fragmentation vs TCP Fast Open mutual exclusion
        if (effective.tlsFragmentEnabled && effective.tcpFastOpen) {
            val (frag, tfo) = enforceFragmentVsTfo(true, true, preferFragmentOverTfo)
            effective = effective.copy(
                tlsFragmentEnabled = frag,
                realityFragmentEnabled = if (frag) effective.realityFragmentEnabled else false,
                tcpFastOpen = tfo
            )
            notices.add("TLS Fragmentation and TCP Fast Open are mutually exclusive.")
        }

        // Rule 2: REALITY ignores allowInsecure, cipherSuites, ECH, Mixed-Case SNI
        if (isReality) {
            effective = effective.copy(
                mixedCaseSni = false,
                echEnabled = false,
                echConfigList = "",
                echForceQuery = "none",
                failClosedEch = false,
                cipherSuites = ""
            )
            notices.add("REALITY ignores CipherSuites, ECH, Mixed-Case SNI, and AllowInsecure.")
        }

        // Rule 3: Non-TCP / Datagram protocols disable TLS Fragmentation, TFO, Mux, Mixed-Case SNI
        if (isDatagram) {
            effective = effective.copy(
                tlsFragmentEnabled = false,
                realityFragmentEnabled = false,
                tcpFastOpen = false,
                muxEnabled = false,
                mixedCaseSni = false
            )
            notices.add("${activeNode?.protocol} is datagram/UDP-based: TCP Fragmentation, TFO, Mux, and Mixed-Case SNI are disabled.")
        }

        // Rule 4: Chaining guardrails
        val (safeChain, safeRelay) = sanitizeChaining(
            effective.chainingMode,
            effective.relayNodeId,
            activeNode
        )
        if (safeChain != effective.chainingMode || safeRelay != effective.relayNodeId) {
            effective = effective.copy(chainingMode = safeChain, relayNodeId = safeRelay)
            if (!warpAllowed) {
                notices.add("WARP/Proxy chaining is unavailable while ${activeNode?.protocol} is active.")
            } else {
                notices.add("Removed invalid self-relay hop from multi-hop chain.")
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

    /**
     * Strips fields on a saved profile that contradict its protocol, transport, or security mode.
     */
    fun sanitizeProfileForSave(profile: ServerProfileEntity): ServerProfileEntity {
        val kind = ProtocolFormKind.fromProfile(profile)
        val spec = fieldSpecFor(
            kind = kind,
            requestedTransport = TransportType.fromString(profile.transport),
            requestedSecurity = SecurityType.fromString(profile.security)
        )
        val safeTransport = TransportType.fromString(profile.transport)
            .takeIf { it in spec.supportedTransports } ?: spec.supportedTransports.first()
        val safeSecurity = SecurityType.fromString(profile.security)
            .takeIf { it in spec.supportedSecurities } ?: spec.supportedSecurities.first()

        return profile.copy(
            protocol = kind.underlying.name,
            address = profile.address.trim(),
            name = profile.name.trim().ifBlank { "${profile.address.trim()}:${profile.port}" },
            transport = safeTransport.name,
            security = safeSecurity.name,
            flow = if (spec.showFlow) profile.flow.trim() else if (spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.flow.trim() else "",
            path = if (spec.showPathAndHost || spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.path.trim() else "",
            hostHeader = if (spec.showPathAndHost || spec.showDnsTunnelFields) profile.hostHeader.trim() else "",
            serviceName = if (spec.showGrpcServiceName || spec.showAwgFields || spec.showDnsTunnelFields) profile.serviceName.trim() else "",
            allowInsecure = if (safeSecurity == SecurityType.REALITY) false else if (spec.showAllowInsecure) profile.allowInsecure else false,
            cipherSuites = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.cipherSuites.trim() else "",
            echConfigList = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.echConfigList.trim() else "",
            echForceQuery = if (safeSecurity == SecurityType.TLS && spec.showCipherSuitesAndEch) profile.echForceQuery else "none",
            publicKey = if (spec.showRealityFields || spec.showWireGuardFields || spec.showMasqueFields || spec.showDnsTunnelFields) profile.publicKey.trim() else "",
            shortId = if (spec.showRealityFields || spec.showWireGuardFields) profile.shortId.trim() else "",
            mldsa65Verify = if (spec.showRealityFields) profile.mldsa65Verify.trim() else "",
            rawUri = ""
        )
    }
}
