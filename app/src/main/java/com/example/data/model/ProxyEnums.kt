package com.example.data.model

enum class ProxyProtocol(val label: String, val scheme: String, val shortTag: String) {
    VLESS("VLESS", "vless", "VLESS"),
    VMESS("VMess", "vmess", "VMess"),
    TROJAN("Trojan", "trojan", "Trojan"),
    SHADOWSOCKS("Shadowsocks", "ss", "SS"),
    HYSTERIA2("Hysteria2", "hysteria2", "HY2"),
    SOCKS("SOCKS5", "socks", "SOCKS"),
    WIREGUARD("WireGuard / AWG", "wireguard", "WG"),
    MASQUE("MASQUE", "masque", "MASQUE"),
    DNSTT("DNSTT", "dnstt", "DNSTT"),
    MASTERDNS("MasterDNS", "masterdns", "MDNS"),
    HYSTERIA("Hysteria v1", "hysteria", "HY1"),
    TUIC("TUIC", "tuic", "TUIC"),
    ANYTLS("AnyTLS", "anytls", "AnyTLS"),
    SSH("SSH", "ssh", "SSH"),
    OPENCONNECT("OpenConnect", "openconnect", "OC"),
    OPENVPN("OpenVPN", "openvpn", "OVPN"),
    HTTP("HTTP proxy", "http", "HTTP"),
    MIERU("Mieru", "mieru", "MIERU");

    companion object {
        fun parse(value: String?): ProxyProtocol {
            return when (value?.trim()?.uppercase()) {
                "HY2" -> HYSTERIA2
                "SS" -> SHADOWSOCKS
                "SOCKS5" -> SOCKS
                "WG", "AWG", "AMNEZIAWG", "WARP" -> WIREGUARD
                "DNS-TUNNEL", "DNSTUNNEL", "MDNS" -> MASTERDNS
                else -> entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: VLESS
            }
        }
    }
}

enum class TransportType(val label: String) {
    TCP("TCP"),
    WS("WebSocket"),
    GRPC("gRPC"),
    HTTPUPGRADE("HTTPUpgrade"),
    XHTTP("XHTTP"),
    QUIC("QUIC"),
    WG("WireGuard"),
    MASQUE("MASQUE"),
    DNSTT("DNSTT"),
    MASTERDNS("MasterDNS");

    companion object {
        fun parse(value: String?): TransportType {
            return when (value?.trim()?.lowercase()) {
                "ws", "websocket" -> WS
                "grpc" -> GRPC
                "httpupgrade" -> HTTPUPGRADE
                "xhttp", "splithttp" -> XHTTP
                "quic" -> QUIC
                "wg", "wireguard", "awg", "warp" -> WG
                "masque" -> MASQUE
                "dnstt" -> DNSTT
                "masterdns", "dns-tunnel", "dnstunnel" -> MASTERDNS
                else -> TCP
            }
        }
    }
}

enum class SecurityType(val label: String) {
    NONE("None"),
    TLS("TLS"),
    REALITY("REALITY");

    companion object {
        fun parse(value: String?): SecurityType {
            return when (value?.trim()?.lowercase()) {
                "reality" -> REALITY
                "tls", "xtls" -> TLS
                else -> NONE
            }
        }
    }
}

enum class RoutingMode(val label: String, val description: String) {
    RULE_BASED("Smart Rules", "Apply domain & CIDR rules automatically"),
    BYPASS_LAN("Bypass LAN", "Direct private networks, tunnel everything else"),
    GLOBAL("Global Tunnel", "Route all device traffic through active node");

    companion object {
        fun parse(value: String?): RoutingMode =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: RULE_BASED
    }
}

enum class RuleMatchType(val label: String) {
    IP_CIDR("IP / CIDR"),
    DOMAIN_SUFFIX("Domain Suffix"),
    DOMAIN_KEYWORD("Keyword"),
    GEOIP("GeoIP"),
    GEOSITE("GeoSite");

    companion object {
        fun parse(value: String?): RuleMatchType =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: DOMAIN_SUFFIX
    }
}

enum class RuleAction(val label: String) {
    PROXY("Proxy"),
    DIRECT("Direct"),
    BLOCK("Block");

    companion object {
        fun parse(value: String?): RuleAction =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: PROXY
    }
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    companion object {
        fun parse(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: DARK
    }
}

enum class ColorPalette(val label: String, val subtitle: String) {
    LUXY_OBSIDIAN_GOLD("Obsidian Gold", "Luxury obsidian & champagne gold"),
    CATPPUCCIN("Catppuccin", "Mocha dark & Latte pastel"),
    DRACULA("Dracula", "Midnight charcoal, violet & cyan"),
    NORD("Nord", "Arctic polar night & frost blue"),
    TOKYO_NIGHT("Tokyo Night", "Deep indigo & luminous blue"),
    SOLARIZED("Solarized", "Precision teal & warm sand"),
    GRUVBOX("Gruvbox", "Retro charcoal, aqua & amber"),
    MATERIAL_YOU("Material You", "Dynamic adaptive accent palette");

    companion object {
        fun parse(value: String?): ColorPalette =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: LUXY_OBSIDIAN_GOLD
    }
}

enum class ConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    FAILOVER("Failover"),
    ERROR("Error")
}

enum class LogLevel { INFO, WARN, RECOVERY, ERROR }

enum class LogCategory { TUNNEL, XRAY_CORE, ANTI_DPI, HANDOFF, FAILOVER, PROBE, DNS }

enum class AntiCensorshipPreset(val label: String, val description: String) {
    BALANCED_STEALTH(
        "Balanced Stealth",
        "TLS Fragment 10-20B (tlshello), randomized uTLS, DoH & Sniffing"
    ),
    STRICT_DPI_BYPASS(
        "Strict DPI Bypass",
        "Aggressive Fragment 3-10B (1-3), Noise padding, FakeDNS, Block QUIC"
    ),
    CDN_FRONTING(
        "CDN Clean IP",
        "Fragment 20-40B, XHTTP packet-up padding, Mux 8, Strict DoH"
    ),
    MAX_STEALTH(
        "Max Stealth",
        "Adaptive stack: fp=unsafe, FinalMask, Fragment 5-12B, Noise, ECH half & HappyEyeballs"
    ),
    DIRECT_LOW_LATENCY(
        "Low Latency",
        "Minimal overhead: Fragment OFF, Noise OFF, TFO ON"
    ),
    CUSTOM(
        "Custom Core",
        "User-configured Xray core anti-filtering parameters"
    );

    companion object {
        fun parse(value: String?): AntiCensorshipPreset =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: BALANCED_STEALTH
    }
}

enum class NodeSortOrder(val label: String) {
    DEFAULT("Added Order"),
    LATENCY("Lowest Latency"),
    NAME("Name A-Z"),
    PROTOCOL("Protocol")
}

enum class ChainingMode(val label: String, val summary: String) {
    NONE("Single Hop", "Direct connection through active node"),
    WARP_TO_PROXY("WARP → Proxy", "Active proxy outbound dials through WARP/AWG tunnel"),
    PROXY_TO_WARP("Proxy → WARP", "WARP exits via active proxy for a clean CDN/Cloudflare egress IP"),
    NODE_TO_NODE("Node → Node", "Active node routes through a selected relay node outbound");

    companion object {
        fun parse(value: String?): ChainingMode =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: NONE
    }
}

enum class DatagramShapingPreset(val key: String, val label: String) {
    OFF("off", "Off"),
    RAW("raw", "Raw"),
    QUIC("quic", "QUIC"),
    DNS("dns", "DNS"),
    SIP("sip", "SIP"),
    RANDOM("random", "Random")
}

enum class DnsPoolStrategy(val label: String, val key: String, val description: String) {
    PARALLEL_FASTEST(
        "Parallel Fastest",
        "parallel_fastest",
        "Race all enabled resolvers and pick fastest valid answer"
    ),
    SEQUENTIAL_FALLBACK(
        "Sequential Fallback",
        "sequential_fallback",
        "Query DoH → DoT → TCP → UDP in order until valid"
    ),
    STICKY(
        "Sticky Resolver",
        "sticky",
        "Pin last healthy resolver and failover only on error"
    );

    companion object {
        fun parse(value: String?): DnsPoolStrategy =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: PARALLEL_FASTEST
    }
}

enum class AppSplitTunnelMode(val label: String, val summary: String) {
    DISABLED("All Apps", "Route all non-loopback apps through active rules"),
    ALLOWLIST("Only Selected", "Allow only listed apps; reject/bypass all others"),
    BLOCKLIST("Bypass Selected", "Bypass listed apps directly outside tunnel");

    companion object {
        fun parse(value: String?): AppSplitTunnelMode =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: DISABLED
    }
}

enum class StallSensitivity(val label: String, val thresholdSec: Int) {
    FAST("Fast (6s)", 6),
    BALANCED("Balanced (8s)", 8),
    CONSERVATIVE("Conservative (12s)", 12)
}

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
    MASTERDNS("MasterDNS", ProxyProtocol.MASTERDNS)
}

enum class SocialServiceId(
    val id: String,
    val label: String,
    val geositeTag: String,
    val geoipTag: String?,
    val domains: List<String>
) {
    TELEGRAM(
        "telegram",
        "Telegram",
        "telegram",
        "telegram",
        listOf("t.me", "telegram.org", "telegram.me", "tdesktop.com", "telesco.pe", "tg.dev")
    ),
    YOUTUBE(
        "youtube",
        "YouTube",
        "youtube",
        null,
        listOf("youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "youtubei.googleapis.com")
    ),
    INSTAGRAM(
        "instagram",
        "Instagram",
        "instagram",
        null,
        listOf("instagram.com", "cdninstagram.com", "ig.me")
    ),
    WHATSAPP(
        "whatsapp",
        "WhatsApp",
        "whatsapp",
        null,
        listOf("whatsapp.com", "whatsapp.net", "wa.me")
    ),
    TWITTER(
        "twitter",
        "X / Twitter",
        "twitter",
        null,
        listOf("twitter.com", "x.com", "twimg.com", "t.co")
    );

    companion object {
        fun fromId(raw: String): SocialServiceId? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
    }
}

enum class SpeedTestMode(val label: String) {
    TUNNELED("With VPN (Tunnel)"),
    DIRECT("Without VPN (Direct)")
}

enum class SpeedTestStage(val label: String) {
    IDLE("Ready"),
    PING("Ping & Jitter"),
    DOWNLOAD("Download"),
    UPLOAD("Upload"),
    COMPLETE("Complete"),
    CANCELLED("Cancelled"),
    ERROR("Error")
}
