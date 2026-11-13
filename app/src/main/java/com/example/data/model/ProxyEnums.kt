package com.example.data.model

enum class ProxyProtocol(val label: String, val scheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    HYSTERIA2("Hysteria2", "hysteria2"),
    SOCKS("SOCKS5", "socks"),
    WIREGUARD("WireGuard / AWG", "wireguard"),
    MASQUE("MASQUE", "masque"),
    DNSTT("DNSTT", "dnstt"),
    MASTERDNS("MasterDNS", "masterdns");

    companion object {
        fun fromString(value: String): ProxyProtocol =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: when (value.lowercase()) {
                    "wg", "awg", "warp" -> WIREGUARD
                    "dns-tunnel", "dnstunnel" -> MASTERDNS
                    else -> VLESS
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
        fun fromString(value: String?): TransportType =
            when (value?.lowercase()) {
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

enum class SecurityType(val label: String) {
    NONE("None"),
    TLS("TLS"),
    REALITY("REALITY");

    companion object {
        fun fromString(value: String?): SecurityType =
            when (value?.lowercase()) {
                "reality" -> REALITY
                "tls", "xtls" -> TLS
                else -> NONE
            }
    }
}

enum class RoutingMode(val label: String, val description: String) {
    RULE_BASED("Smart Rules", "Apply domain & CIDR rules automatically"),
    BYPASS_LAN("Bypass LAN", "Direct private networks, tunnel everything else"),
    GLOBAL("Global Tunnel", "Route all device traffic through active node");

    companion object {
        fun fromString(value: String?): RoutingMode =
            entries.firstOrNull { it.name == value } ?: RULE_BASED
    }
}

enum class RuleMatchType(val label: String) {
    IP_CIDR("IP / CIDR"),
    DOMAIN_SUFFIX("Domain Suffix"),
    DOMAIN_KEYWORD("Keyword"),
    GEOIP("GeoIP"),
    GEOSITE("GeoSite")
}

enum class RuleAction(val label: String) {
    PROXY("Proxy"),
    DIRECT("Direct"),
    BLOCK("Block")
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    companion object {
        fun fromString(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}

enum class ColorPalette(val label: String, val description: String) {
    LUXY_OBSIDIAN_GOLD("Obsidian Gold", "Luxury obsidian & champagne gold"),
    CATPPUCCIN("Catppuccin", "Mocha dark & Latte pastel"),
    DRACULA("Dracula", "Midnight charcoal, violet & cyan"),
    NORD("Nord", "Arctic polar night & frost blue"),
    TOKYO_NIGHT("Tokyo Night", "Deep indigo & luminous blue"),
    SOLARIZED("Solarized", "Precision teal & warm sand"),
    GRUVBOX("Gruvbox", "Retro charcoal, aqua & amber"),
    MATERIAL_YOU("Material You", "Dynamic wallpaper palette (Android 12+)");

    companion object {
        fun fromString(value: String?): ColorPalette =
            entries.firstOrNull { it.name == value } ?: LUXY_OBSIDIAN_GOLD
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILOVER,
    ERROR
}

enum class LogLevel {
    INFO,
    WARN,
    RECOVERY,
    ERROR
}

enum class LogCategory {
    TUNNEL,
    XRAY_CORE,
    ANTI_DPI,
    HANDOFF,
    FAILOVER,
    PROBE,
    DNS
}

enum class AntiCensorshipPreset(val label: String, val summary: String) {
    BALANCED_STEALTH(
        label = "Balanced Stealth",
        summary = "TLS Fragment 10-20B (tlshello), randomized uTLS, DoH & Sniffing"
    ),
    STRICT_DPI_BYPASS(
        label = "Strict DPI Bypass",
        summary = "Aggressive Fragment 3-10B (1-3), Noise padding, FakeDNS, Block QUIC"
    ),
    CDN_FRONTING(
        label = "CDN Clean IP",
        summary = "Fragment 20-40B, XHTTP packet-up padding, Mux 8, Strict DoH"
    ),
    MAX_STEALTH(
        label = "Max Stealth",
        summary = "LuxyVPN adaptive stack: fp=unsafe, FinalMask, Fragment 5-12B, Noise, ECH half & HappyEyeballs"
    ),
    DIRECT_LOW_LATENCY(
        label = "Low Latency",
        summary = "Minimal overhead: Fragment OFF, Noise OFF, TFO ON"
    ),
    CUSTOM(
        label = "Custom Core",
        summary = "User-configured Xray core anti-filtering parameters"
    );

    companion object {
        fun fromString(value: String?): AntiCensorshipPreset =
            entries.firstOrNull { it.name == value } ?: BALANCED_STEALTH
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
        fun fromString(value: String?): ChainingMode =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

enum class DatagramShapingPreset(val key: String, val label: String) {
    OFF("off", "Off"),
    RAW("raw", "Raw"),
    QUIC("quic", "QUIC"),
    DNS("dns", "DNS"),
    SIP("sip", "SIP"),
    RANDOM("random", "Random");

    companion object {
        fun fromKey(value: String?): DatagramShapingPreset =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: QUIC
    }
}

enum class DnsPoolStrategy(val label: String, val key: String) {
    PARALLEL_FASTEST("Parallel Fastest", "parallel_fastest"),
    SEQUENTIAL_FALLBACK("Sequential Fallback", "sequential_fallback"),
    STICKY("Sticky Resolver", "sticky");

    companion object {
        fun fromString(value: String?): DnsPoolStrategy =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.key.equals(value, ignoreCase = true)
            } ?: PARALLEL_FASTEST
    }
}

enum class AppSplitTunnelMode(val label: String, val summary: String) {
    DISABLED("All Apps", "Route all non-loopback apps through active rules"),
    ALLOWLIST("Only Selected", "Allow only listed apps; reject/bypass all others"),
    BLOCKLIST("Bypass Selected", "Bypass listed apps directly outside tunnel");

    companion object {
        fun fromString(value: String?): AppSplitTunnelMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DISABLED
    }
}

enum class StallSensitivity(val label: String, val thresholdSec: Int) {
    FAST("Fast (6s)", 6),
    BALANCED("Balanced (8s)", 8),
    CONSERVATIVE("Conservative (12s)", 12);

    companion object {
        fun fromSeconds(sec: Int): StallSensitivity = when {
            sec <= 6 -> FAST
            sec <= 9 -> BALANCED
            else -> CONSERVATIVE
        }
    }
}
