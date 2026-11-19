package com.example.xraycore

import com.example.core.ClientPolicy
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Emits only the minimal client's audited official Xray configuration subset. */
object XrayConfigCompiler {
    const val LUXY_CORE_VERSION = "Xray 26.11.9"


    private fun buildOutbound(node: ServerProfileEntity): JSONObject {
        val protocol = ProxyProtocol.parse(node.protocol)
        val features = if (node.featureOverrides.isBlank()) null else NodeFeatureOverrides.decode(node.featureOverrides)
        val server = JSONObject().put("address", node.address).put("port", node.port)
        val outboundSettings = JSONObject()
        val outboundProtocol = when (protocol) {
            ProxyProtocol.VLESS, ProxyProtocol.VMESS -> {
                val user = JSONObject().put("id", node.uuidOrPassword)
                if (protocol == ProxyProtocol.VLESS) {
                    user.put("encryption", node.encryption.trim().ifEmpty { "none" })
                    if (node.flow.isNotBlank()) user.put("flow", node.flow)
                    val packetEncoding = features?.packetEncoding.orEmpty()
                    if (packetEncoding.isNotBlank()) user.put("packetEncoding", packetEncoding)
                } else {
                    user.put("security", node.encryption.ifBlank { "auto" })
                }
                server.put("users", JSONArray().put(user))
                outboundSettings.put("vnext", JSONArray().put(server))
                protocol.name.lowercase(Locale.ROOT)
            }
            ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS, ProxyProtocol.SOCKS -> {
                when (protocol) {
                    ProxyProtocol.TROJAN -> server.put("password", node.uuidOrPassword)
                    ProxyProtocol.SHADOWSOCKS -> {
                        server.put("method", node.encryption)
                        server.put("password", node.uuidOrPassword)
                    }
                    ProxyProtocol.SOCKS -> if (node.uuidOrPassword.isNotBlank()) {
                        val credentials = node.uuidOrPassword.split(':', limit = 2)
                        server.put("users", JSONArray().put(JSONObject()
                            .put("user", credentials[0])
                            .put("pass", credentials.getOrElse(1) { "" })))
                    }
                    else -> Unit
                }
                outboundSettings.put("servers", JSONArray().put(server))
                protocol.name.lowercase(Locale.ROOT)
            }
            else -> error("${node.protocol} is not supported by this client")
        }
        val transport = TransportType.parse(node.transport)
        val security = SecurityType.parse(node.security)
        val stream = JSONObject()
            .put("network", transport.name.lowercase(Locale.ROOT))
            .put("security", security.name.lowercase(Locale.ROOT))
        val serverName = node.sni.ifBlank { node.hostHeader.ifBlank { node.address } }
        when (security) {
            SecurityType.TLS -> stream.put("tlsSettings", JSONObject().apply {
                put("serverName", serverName)
                put("fingerprint", node.fingerprint.ifBlank { "chrome" })
                put("allowInsecure", false)
                val alpn = node.alpn.split(',').map(String::trim).filter(String::isNotEmpty)
                if (alpn.isNotEmpty()) put("alpn", JSONArray(alpn))
                if (node.cipherSuites.isNotBlank()) put("cipherSuites", node.cipherSuites)
            })
            SecurityType.REALITY -> stream.put("realitySettings", JSONObject().apply {
                put("serverName", serverName)
                put("fingerprint", node.fingerprint.ifBlank { "chrome" })
                put("publicKey", node.publicKey)
                put("shortId", node.shortId)
                put("spiderX", features?.spiderX?.ifBlank { "/" } ?: "/")
            })
            SecurityType.NONE -> Unit
        }
        when (transport) {
            TransportType.WS -> stream.put("wsSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
            })
            TransportType.GRPC -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", node.serviceName)
                if (node.hostHeader.isNotBlank()) put("authority", node.hostHeader)
            })
            TransportType.HTTPUPGRADE -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
            })
            TransportType.XHTTP -> stream.put("xhttpSettings", JSONObject().apply {
                put("path", node.path.ifBlank { "/" })
                put("mode", features?.xhttpMode?.ifBlank { "auto" } ?: "auto")
                if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
            })
            else -> Unit
        }
        return JSONObject().put("tag", "proxy-out").put("protocol", outboundProtocol)
            .put("settings", outboundSettings).put("streamSettings", stream)
    }

    /** Old routing/relay arguments are never executed, even for persisted experimental settings. */
    @Suppress("UNUSED_PARAMETER")
    fun compileXrayConfigObject(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): JSONObject {
        requireNotNull(node) { "No server profile selected" }
        require(ClientPolicy.isSupported(node)) {
            "${node.protocol} profile is not supported: insecure or experimental features are disabled"
        }
        val baseline = ClientPolicy.settings(settings)
        val country = BuiltInRegion.country()
        return JSONObject().apply {
            put("log", JSONObject().put("access", "none").put("error", "")
                .put("loglevel", "warning").put("dnsLog", false))
            put("stats", JSONObject())
            put("policy", JSONObject()
                .put("levels", JSONObject().put("0", JSONObject()
                    .put("bufferSize", 64)
                    .put("statsUserUplink", false)
                    .put("statsUserDownlink", false)
                    .put("statsUserOnline", false)))
                .put("system", JSONObject()
                    .put("statsOutboundUplink", true)
                    .put("statsOutboundDownlink", true)))
            put("dns", BuiltInNetworkDefaults.dns(country))
            put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "tun-in").put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "luxy0").put("MTU", baseline.mtu))
                    .put("sniffing", BuiltInNetworkDefaults.sniffing()))
                .put(JSONObject().put("tag", "connection-check").put("protocol", "socks")
                    .put("listen", "127.0.0.1").put("port", TunnelConnectivityCheck.SOCKS_PORT)
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
            // Xray selects its first outbound when no routing rule matches.
            val outbounds = JSONArray().put(buildOutbound(node))
            BuiltInNetworkDefaults.supportingOutbounds().forEach { outbounds.put(it) }
            put("outbounds", outbounds)
            put("routing", BuiltInNetworkDefaults.routing(country))
        }
    }

    fun compileXrayConfig(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String = compileXrayConfigObject(node, settings, rules, relayNode).toString()

    /**
     * Compiles an isolated loopback-SOCKS probe config (no TUN inbound, no DNS hijack)
     * for manual retesting and exit-country verification.
     */
    fun compileProbeConfig(node: ServerProfileEntity, socksPort: Int): String {
        require(ClientPolicy.isSupported(node)) {
            "${node.protocol} profile is not supported: insecure or experimental features are disabled"
        }
        val inbounds = JSONArray()
        if (socksPort > 0) {
            inbounds.put(
                JSONObject()
                    .put("tag", "probe-in")
                    .put("protocol", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            )
        }
        val outbounds = JSONArray()
            .put(buildOutbound(node))
            .put(JSONObject().put("tag", "direct-out").put("protocol", "freedom").put("settings", JSONObject()))
            .put(JSONObject().put("tag", "block-out").put("protocol", "blackhole").put("settings", JSONObject()))
        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("type", "field")
                        .put("inboundTag", JSONArray(listOf("probe-in")))
                        .put("outboundTag", "proxy-out")
                )
            )
        return JSONObject().apply {
            put("log", JSONObject().put("access", "none").put("error", "").put("loglevel", "warning").put("dnsLog", false))
            put("inbounds", inbounds)
            put("outbounds", outbounds)
            put("routing", routing)
        }.toString()
    }

}
