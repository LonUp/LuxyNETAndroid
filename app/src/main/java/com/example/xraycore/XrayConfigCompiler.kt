package com.example.xraycore

import com.example.core.AwgProfileParams
import com.example.core.EncryptedConfigVault
import com.example.core.ProxyUriParser
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles LuxyVPN's active node, relay node, routing rules, and anti-censorship parameters into
 * a complete Xray-core JSON configuration with WireGuard / AmneziaWG / MASQUE support,
 * bidirectional multi-hop dialerProxy chaining, TLS extensions, and address-family FinalMasks.
 */
object XrayConfigCompiler {

    fun compileRedactedForInspector(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String {
        val anyLocked = (node?.isLocked == true) || (relayNode?.isLocked == true)
        if (!anyLocked) {
            return compile(node, settings, rules, relayNode)
        }
        val redactedToken = EncryptedConfigVault.REDACTED_VALUE
        fun maskProfile(src: ServerProfileEntity?): ServerProfileEntity? {
            if (src == null || !src.isLocked) return src
            return src.copy(
                address = redactedToken,
                port = 0,
                uuidOrPassword = redactedToken,
                sni = if (src.sni.isNotBlank()) redactedToken else "",
                hostHeader = if (src.hostHeader.isNotBlank()) redactedToken else "",
                publicKey = if (src.publicKey.isNotBlank()) redactedToken else "",
                shortId = if (src.shortId.isNotBlank()) "<LOCKED>" else "",
                echConfigList = if (src.echConfigList.isNotBlank()) redactedToken else "",
                mldsa65Verify = if (src.mldsa65Verify.isNotBlank()) redactedToken else "",
                path = if (src.path.isNotBlank()) "/<redacted>" else "",
                serviceName = if (src.serviceName.isNotBlank()) "<redacted>" else ""
            )
        }
        val safeSettings = if (anyLocked && settings.chainingMode != ChainingMode.NONE) {
            settings.copy(
                warpEndpoint = redactedToken,
                warpPrivateKey = redactedToken,
                warpPublicKey = redactedToken,
                warpReserved = "<LOCKED>"
            )
        } else {
            settings
        }
        val compiled = compile(maskProfile(node), safeSettings, rules, maskProfile(relayNode))
        return try {
            val root = JSONObject(compiled)
            val outbounds = root.optJSONArray("outbounds")
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    val tag = outbound.optString("tag", "")
                    val proto = outbound.optString("protocol", "")
                    val shouldRedactOutbound = when {
                        tag == "proxy" && (node?.isLocked == true || relayNode?.isLocked == true) -> true
                        tag == "relay-out" && relayNode?.isLocked == true -> true
                        tag == "relay-proxy-out" && node?.isLocked == true -> true
                        tag == "warp-out" && anyLocked -> true
                        else -> false
                    }
                    if (!shouldRedactOutbound) continue

                    val pSettings = outbound.optJSONObject("settings") ?: continue
                    pSettings.optJSONArray("vnext")?.optJSONObject(0)?.put("port", "<LOCKED>")
                    pSettings.optJSONArray("servers")?.optJSONObject(0)?.put("port", "<LOCKED>")
                    if (pSettings.has("port")) pSettings.put("port", "<LOCKED>")

                    if (proto == "wireguard") {
                        pSettings.put("secretKey", redactedToken)
                        pSettings.put("reserved", JSONArray().put("<LOCKED>"))
                        val peers = pSettings.optJSONArray("peers")
                        if (peers != null) {
                            for (p in 0 until peers.length()) {
                                val peer = peers.optJSONObject(p) ?: continue
                                peer.put("endpoint", redactedToken)
                                peer.put("publicKey", redactedToken)
                            }
                        }
                    } else if (proto == "masque") {
                        pSettings.put("privateKey", redactedToken)
                        pSettings.put("publicKey", redactedToken)
                        pSettings.put("endpoint", redactedToken)
                    } else if (proto == "dnstt" || proto == "masterdns") {
                        pSettings.put("resolver", redactedToken)
                        pSettings.put("address", redactedToken)
                        pSettings.put("port", "<LOCKED>")
                        pSettings.put("domain", redactedToken)
                        pSettings.put("nameserver", redactedToken)
                        pSettings.put("publicKey", redactedToken)
                        if (pSettings.has("auth")) pSettings.put("auth", redactedToken)
                        if (pSettings.has("dohUrl")) pSettings.put("dohUrl", redactedToken)
                    }
                }
            }
            root.put("vaultLockPolicy", "LOCKED_NO_REVEAL")
            root.toString(2)
        } catch (_: Exception) {
            compiled
        }
    }

    fun compile(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // DNS & Multi-Resolver Anti-Poisoning Pool Configuration
        val dnsServers = JSONArray()
        if (settings.fakeDnsEnabled) {
            dnsServers.put("fakedns")
        }
        if (settings.dohEnabled) {
            dnsServers.put(settings.dohServerUrl.ifBlank { "https://cloudflare-dns.com/dns-query" })
        }
        if (settings.dnsDotEnabled && settings.dnsDotServer.isNotBlank()) {
            val dotFormatted = if (settings.dnsDotServer.startsWith("tls://", ignoreCase = true)) {
                settings.dnsDotServer
            } else {
                "tls://${settings.dnsDotServer}"
            }
            dnsServers.put(dotFormatted)
        }
        if (settings.dnsTcpEnabled && settings.primaryDns.isNotBlank()) {
            dnsServers.put("tcp://${settings.primaryDns}")
        }
        if (settings.dnsUdpEnabled) {
            dnsServers.put(settings.primaryDns)
            if (settings.fallbackDns.isNotBlank()) {
                dnsServers.put(settings.fallbackDns)
            }
        } else if (dnsServers.length() == 0) {
            dnsServers.put(settings.primaryDns)
        }

        root.put("dns", JSONObject().apply {
            put("servers", dnsServers)
            put("queryStrategy", "UseIPv4")
            put("poolStrategy", settings.dnsPoolStrategy.key)
            put("integrityFilter", settings.dnsIntegrityFilterEnabled)
            put("disableFallback", false)
        })

        if (settings.fakeDnsEnabled) {
            root.put("fakedns", JSONArray().apply {
                put(JSONObject().apply {
                    put("ipPool", "198.18.0.0/15")
                    put("poolSize", 65535)
                })
            })
        }

        // Inbounds with Sniffing (Pre-FakeDNS original destination preserved when routeOnly=true)
        val sniffingObj = JSONObject().apply {
            put("enabled", settings.sniffingEnabled)
            put("destOverride", JSONArray().apply {
                put("http")
                put("tls")
                put("quic")
                if (settings.fakeDnsEnabled) put("fakedns")
            })
            put("routeOnly", false)
            put("metadataOnly", false)
        }

        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", sniffingObj)
            })
        })

        // Phase 5 Guardrails: resolve effective settings & chaining incompatibilities
        val isPrimaryDatagram = node != null && com.example.core.ProtocolGuardrails.isDatagramNode(node)
        val primaryProto = node?.let { ProxyProtocol.fromString(it.protocol) }
        val isPrimaryWgOrMasque = primaryProto == ProxyProtocol.WIREGUARD || primaryProto == ProxyProtocol.MASQUE
        val effectiveTfo = if (settings.tlsFragmentEnabled || settings.realityFragmentEnabled || isPrimaryDatagram) {
            false
        } else {
            settings.tcpFastOpen
        }
        val effectiveSettings = settings.copy(
            tcpFastOpen = effectiveTfo,
            muxEnabled = if (isPrimaryDatagram) false else settings.muxEnabled
        )

        // Determine if fragment-out is needed (for non-datagram TLS or REALITY with realityFragmentEnabled, or chained underlay)
        val nodeSecurity = node?.let { SecurityType.fromString(it.security) }
        val relaySecurity = relayNode?.let { SecurityType.fromString(it.security) }
        val baseHopTag = when {
            isPrimaryDatagram -> if (effectiveSettings.noiseEnabled) "noise-out" else null
            effectiveSettings.tlsFragmentEnabled -> "fragment-out"
            effectiveSettings.noiseEnabled -> "noise-out"
            else -> null
        }
        fun effectiveTransportHopFor(sec: SecurityType?, proto: ProxyProtocol? = null): String? {
            if (proto != null && com.example.core.ProtocolGuardrails.isDatagramProtocol(proto)) {
                return if (effectiveSettings.noiseEnabled) "noise-out" else null
            }
            return when {
                sec == SecurityType.REALITY && !effectiveSettings.realityFragmentEnabled ->
                    if (effectiveSettings.noiseEnabled) "noise-out" else null
                sec == SecurityType.REALITY && effectiveSettings.realityFragmentEnabled ->
                    "fragment-out"
                else -> baseHopTag
            }
        }

        val effectiveChainingMode = if (isPrimaryWgOrMasque &&
            (effectiveSettings.chainingMode == ChainingMode.WARP_TO_PROXY ||
                effectiveSettings.chainingMode == ChainingMode.PROXY_TO_WARP)
        ) {
            ChainingMode.NONE
        } else {
            effectiveSettings.chainingMode
        }

        // Outbounds (Primary / Multi-Hop Chain + Fragment + Noise + Direct + Block)
        val outbounds = JSONArray()
        var relayUsesFragment = false
        when (effectiveChainingMode) {
            ChainingMode.WARP_TO_PROXY -> {
                if (node != null) {
                    outbounds.put(buildOutbound(tag = "proxy", node = node, settings = effectiveSettings, dialerOverride = "warp-out"))
                }
                val warpSource = node?.takeIf { ProxyProtocol.fromString(it.protocol) == ProxyProtocol.WIREGUARD }
                outbounds.put(
                    buildWarpOutbound(
                        tag = "warp-out",
                        profile = warpSource,
                        settings = effectiveSettings,
                        dialerTarget = baseHopTag
                    )
                )
            }
            ChainingMode.PROXY_TO_WARP -> {
                val warpSource = relayNode?.takeIf { ProxyProtocol.fromString(it.protocol) == ProxyProtocol.WIREGUARD }
                val hasRelayProxy = node != null
                outbounds.put(
                    buildWarpOutbound(
                        tag = "proxy",
                        profile = warpSource,
                        settings = effectiveSettings,
                        dialerTarget = if (hasRelayProxy) "relay-proxy-out" else baseHopTag
                    )
                )
                if (node != null) {
                    val hop = effectiveTransportHopFor(nodeSecurity, primaryProto)
                    if (hop == "fragment-out") relayUsesFragment = true
                    outbounds.put(
                        buildOutbound(
                            tag = "relay-proxy-out",
                            node = node,
                            settings = effectiveSettings,
                            dialerOverride = hop
                        )
                    )
                }
            }
            ChainingMode.NODE_TO_NODE -> {
                val validRelay = relayNode?.takeIf { node == null || it.id != node.id }
                val relayProto = validRelay?.let { ProxyProtocol.fromString(it.protocol) }
                if (node != null) {
                    val primaryDialer = if (validRelay != null) "relay-out" else effectiveTransportHopFor(nodeSecurity, primaryProto)
                    outbounds.put(buildOutbound(tag = "proxy", node = node, settings = effectiveSettings, dialerOverride = primaryDialer))
                }
                if (validRelay != null) {
                    val relayHop = effectiveTransportHopFor(relaySecurity, relayProto)
                    if (relayHop == "fragment-out") relayUsesFragment = true
                    outbounds.put(
                        buildOutbound(
                            tag = "relay-out",
                            node = validRelay,
                            settings = effectiveSettings,
                            dialerOverride = relayHop
                        )
                    )
                }
            }
            ChainingMode.NONE -> {
                if (node != null) {
                    outbounds.put(
                        buildOutbound(
                            tag = "proxy",
                            node = node,
                            settings = effectiveSettings,
                            dialerOverride = effectiveTransportHopFor(nodeSecurity, primaryProto)
                        )
                    )
                }
            }
        }

        val emitFragmentOutbound = (!isPrimaryDatagram || relayUsesFragment) &&
            (effectiveSettings.tlsFragmentEnabled || effectiveSettings.realityFragmentEnabled)
        if (emitFragmentOutbound) {
            outbounds.put(JSONObject().apply {
                put("tag", "fragment-out")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                    put("fragment", JSONObject().apply {
                        put("packets", settings.fragmentPackets)
                        put("length", settings.fragmentLength)
                        put("interval", settings.fragmentInterval)
                        if (settings.fragmentMaxSplit.isNotBlank()) {
                            put("maxSplit", settings.fragmentMaxSplit)
                        }
                        if (settings.fragmentHost.isNotBlank()) {
                            put("host", settings.fragmentHost)
                        }
                    })
                })
                put("streamSettings", JSONObject().apply {
                    put("sockopt", buildSockopt(settings, includeDialerProxy = settings.noiseEnabled, dialerTarget = "noise-out"))
                })
            })
        }

        if (settings.noiseEnabled) {
            outbounds.put(JSONObject().apply {
                put("tag", "noise-out")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("noise", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", settings.noiseType.ifBlank { "rand" })
                            put("packet", when (settings.noiseType) {
                                "str" -> "GET / HTTP/1.1\r\nHost: www.cloudflare.com\r\n\r\n"
                                "hex" -> "1603010020"
                                "base64" -> "R0VUIC8gSFRUUC8xLjE="
                                else -> settings.noisePacketSize
                            })
                            put("delay", settings.noiseDelayMs)
                            put("applyTo", settings.noiseApplyTo.ifBlank { "ip" })
                        })
                    })
                })
            })
        }

        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", settings.domainStrategy)
            })
        })

        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })

        root.put("outbounds", outbounds)

        // Routing Rules
        val compiledRules = JSONArray()
        if (settings.blockQuicUdp) {
            compiledRules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("port", "443")
                put("outboundTag", "block")
            })
        }

        if (settings.routingMode != RoutingMode.GLOBAL) {
            rules.filter { it.isEnabled }.forEach { rule ->
                val ruleJson = JSONObject().apply {
                    put("type", "field")
                    val targetTag = when (rule.action) {
                        RuleAction.DIRECT.name -> "direct"
                        RuleAction.BLOCK.name -> "block"
                        else -> "proxy"
                    }
                    put("outboundTag", targetTag)
                    when (rule.matchType) {
                        RuleMatchType.IP_CIDR.name -> put("ip", JSONArray().put(rule.pattern))
                        RuleMatchType.GEOIP.name -> put("ip", JSONArray().put("geoip:${rule.pattern}"))
                        RuleMatchType.GEOSITE.name -> put("domain", JSONArray().put("geosite:${rule.pattern}"))
                        RuleMatchType.DOMAIN_KEYWORD.name -> put("domain", JSONArray().put("keyword:${rule.pattern}"))
                        else -> put("domain", JSONArray().put("domain:${rule.pattern.removePrefix(".")}"))
                    }
                }
                compiledRules.put(ruleJson)
            }
        }

        root.put("routing", JSONObject().apply {
            put("domainStrategy", settings.domainStrategy)
            put("domainMatcher", "hybrid")
            put("preFakeDnsEvaluation", true)
            put("strictUidIsolation", settings.strictUidIsolationEnabled)
            put("rules", compiledRules)
        })

        return root.toString(2)
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
        if (settings.happyEyeballsEnabled) {
            put("happyEyeballs", JSONObject().apply {
                put("tryDelayMs", settings.happyEyeballsTryDelayMs)
                put("prioritizeIPv6", false)
                put("maxConcurrentTry", 4)
            })
        }
        if (includeDialerProxy && !dialerTarget.isNullOrBlank()) {
            put("dialerProxy", dialerTarget)
        }
    }

    private fun buildFinalMaskObject(maskType: String): JSONObject {
        val clean = maskType.trim()
        return JSONObject().apply {
            put("type", clean)
            when {
                clean.startsWith("header-") -> {
                    put("settings", JSONObject().apply {
                        put("domain", "www.bing.com")
                    })
                }
                clean == "mkcp-aes128gcm" -> {
                    put("settings", JSONObject().apply {
                        put("password", "luxyvpn-aes-mask")
                    })
                }
                clean == "sudoku" -> {
                    put("settings", JSONObject().apply {
                        put("ascii", "prefer_entropy")
                        put("paddingMin", 8)
                        put("paddingMax", 32)
                    })
                }
                clean == "oob" -> {
                    put("settings", JSONObject().apply {
                        put("mode", "oob-urgent")
                    })
                }
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
            val hostFormatted = if (profile.address.contains(":") && !profile.address.startsWith("[")) {
                "[${profile.address}]"
            } else profile.address
            "$hostFormatted:${profile.port}"
        } else {
            settings.warpEndpoint.ifBlank { "162.159.192.1:2408" }
        }
        val secretKey = profile?.uuidOrPassword?.ifBlank { settings.warpPrivateKey } ?: settings.warpPrivateKey
        val publicKey = profile?.publicKey?.ifBlank { settings.warpPublicKey } ?: settings.warpPublicKey
        val reservedStr = profile?.shortId?.ifBlank { settings.warpReserved } ?: settings.warpReserved
        val reservedArr = JSONArray().apply {
            val parsed = reservedStr.split(",", ":")
                .mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 255) }
            if (parsed.isEmpty()) {
                put(0); put(0); put(0)
            } else {
                parsed.forEach { put(it) }
            }
        }
        val localAddresses = (profile?.path?.ifBlank { settings.warpLocalAddress } ?: settings.warpLocalAddress)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("172.16.0.2/32") }

        val awgOverride = profile?.serviceName?.let { ProxyUriParser.parseAwgQuery(it) }
        val jc = awgOverride?.jc ?: settings.awgJc
        val jmin = awgOverride?.jmin ?: settings.awgJmin
        val jmax = awgOverride?.jmax ?: settings.awgJmax
        val s1 = awgOverride?.s1 ?: settings.awgS1
        val s2 = awgOverride?.s2 ?: settings.awgS2
        val h1 = awgOverride?.h1 ?: settings.awgH1
        val h2 = awgOverride?.h2 ?: settings.awgH2
        val h3 = awgOverride?.h3 ?: settings.awgH3
        val h4 = awgOverride?.h4 ?: settings.awgH4
        val shaping = profile?.encryption?.takeIf { it.isNotBlank() && it != "none" } ?: settings.warpNoisePreset
        val mtu = profile?.flow?.toIntOrNull()?.takeIf { it in 576..1500 } ?: settings.mtu

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", secretKey)
                put("address", JSONArray(localAddresses))
                put("mtu", mtu)
                put("reserved", reservedArr)
                put("jc", jc)
                put("jmin", jmin)
                put("jmax", jmax)
                put("s1", s1)
                put("s2", s2)
                put("h1", h1)
                put("h2", h2)
                put("h3", h3)
                put("h4", h4)
                put("shaping", shaping)
                put("peers", JSONArray().put(JSONObject().apply {
                    put("endpoint", endpoint)
                    put("publicKey", publicKey)
                    put("allowedIPs", JSONArray().apply {
                        put("0.0.0.0/0")
                        put("::/0")
                    })
                    put("keepAlive", 25)
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "wg")
                put("security", "none")
                val effectiveMask = profile?.finalMask?.takeIf { it.isNotBlank() && it != "none" && it != "awg" }
                    ?: settings.finalMaskType.takeIf { it != "none" }
                if (!effectiveMask.isNullOrBlank()) {
                    val family = settings.finalMaskFamily.ifBlank { "udp4" }
                    put("finalmask", JSONObject().apply {
                        put(family, JSONArray().put(buildFinalMaskObject(effectiveMask)))
                    })
                }
                put("sockopt", buildSockopt(settings, includeDialerProxy = !dialerTarget.isNullOrBlank(), dialerTarget = dialerTarget, forceDisableTfo = true))
            })
        }
    }

    private fun buildOutbound(
        tag: String,
        node: ServerProfileEntity,
        settings: StabilitySettings,
        dialerOverride: String? = null
    ): JSONObject {
        val protocol = ProxyProtocol.fromString(node.protocol)
        if (protocol == ProxyProtocol.WIREGUARD) {
            return buildWarpOutbound(tag, node, settings, dialerOverride)
        }

        val outbound = JSONObject()
        outbound.put("tag", tag)

        when (protocol) {
            ProxyProtocol.VLESS -> {
                outbound.put("protocol", "vless")
                outbound.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuidOrPassword)
                            val enc = if (settings.allowUnencryptedVless) "none" else node.encryption.ifBlank { "none" }
                            put("encryption", enc)
                            if (node.flow.isNotBlank()) put("flow", node.flow)
                        }))
                    }))
                })
            }
            ProxyProtocol.VMESS -> {
                outbound.put("protocol", "vmess")
                outbound.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuidOrPassword)
                            put("security", node.encryption.ifBlank { "auto" })
                        }))
                    }))
                })
            }
            ProxyProtocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("password", node.uuidOrPassword)
                    }))
                })
            }
            ProxyProtocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("method", node.encryption.ifBlank { "aes-256-gcm" })
                        put("password", node.uuidOrPassword)
                    }))
                })
            }
            ProxyProtocol.SOCKS -> {
                outbound.put("protocol", "socks")
                outbound.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                    }))
                })
            }
            ProxyProtocol.HYSTERIA2 -> {
                outbound.put("protocol", "hysteria")
                outbound.put("settings", JSONObject().apply {
                    put("version", 2)
                    put("address", node.address)
                    put("port", node.port)
                    put("auth", node.uuidOrPassword)
                })
            }
            ProxyProtocol.MASQUE -> {
                val localAddresses = node.path.ifBlank { settings.warpLocalAddress }
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("172.16.0.2/32") }
                val shaping = node.encryption.takeIf { it.isNotBlank() && it != "none" } ?: settings.warpNoisePreset
                outbound.put("protocol", "masque")
                outbound.put("settings", JSONObject().apply {
                    val hostFormatted = if (node.address.contains(":") && !node.address.startsWith("[")) {
                        "[${node.address}]"
                    } else node.address
                    put("endpoint", "$hostFormatted:${node.port}")
                    put("privateKey", node.uuidOrPassword)
                    put("publicKey", node.publicKey)
                    put("address", JSONArray(localAddresses))
                    put("mtu", node.flow.toIntOrNull()?.takeIf { it in 576..1500 } ?: settings.mtu)
                    put("shaping", shaping)
                })
            }
            ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS -> {
                val dnsTransportMode = node.encryption.lowercase()
                    .takeIf { it in setOf("udp", "tcp", "dot", "doh") } ?: "udp"
                val hostFormatted = if (node.address.contains(":") && !node.address.startsWith("[")) {
                    "[${node.address}]"
                } else node.address
                outbound.put("protocol", protocol.scheme)
                outbound.put("settings", JSONObject().apply {
                    put("resolver", "$hostFormatted:${node.port}")
                    put("address", node.address)
                    put("port", node.port)
                    put("domain", node.hostHeader)
                    put("nameserver", node.hostHeader)
                    put("publicKey", node.publicKey.ifBlank { node.uuidOrPassword })
                    if (node.uuidOrPassword.isNotBlank() && node.uuidOrPassword != node.publicKey) {
                        put("auth", node.uuidOrPassword)
                    }
                    put("transport", dnsTransportMode)
                    put("mtu", node.flow.toIntOrNull()?.takeIf { it in 256..1500 } ?: 1232)
                    if (node.path.isNotBlank()) put("dohUrl", node.path)
                })
            }
            ProxyProtocol.WIREGUARD -> Unit
        }

        // Stream Settings
        val transport = when (protocol) {
            ProxyProtocol.MASQUE -> TransportType.MASQUE
            ProxyProtocol.DNSTT -> TransportType.DNSTT
            ProxyProtocol.MASTERDNS -> TransportType.MASTERDNS
            else -> TransportType.fromString(node.transport)
        }
        val security = when {
            protocol == ProxyProtocol.MASQUE -> SecurityType.TLS
            (protocol == ProxyProtocol.DNSTT || protocol == ProxyProtocol.MASTERDNS) &&
                node.encryption.lowercase() in setOf("dot", "doh") -> SecurityType.TLS
            protocol == ProxyProtocol.VLESS && settings.allowUnencryptedVless && node.security == SecurityType.NONE.name -> SecurityType.NONE
            else -> SecurityType.fromString(node.security)
        }
        val stream = JSONObject().apply {
            put("network", transport.name.lowercase())
            put("security", security.name.lowercase())

            val baseSni = node.sni.ifBlank { node.hostHeader.ifBlank { node.address } }
            val allowMixedSni = settings.mixedCaseSni &&
                security == SecurityType.TLS &&
                protocol !in setOf(ProxyProtocol.HYSTERIA2, ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS)
            val effectiveSni = if (allowMixedSni) LuxyVpnCore.randomizeCaseSni(baseSni) else baseSni
            val effectiveFp = when {
                node.fingerprint.equals("unsafe", ignoreCase = true) -> "unsafe"
                settings.utlsFingerprint.isNotBlank() -> settings.utlsFingerprint
                node.fingerprint.isNotBlank() -> node.fingerprint
                else -> "chrome"
            }
            val effectiveCipherSuites = if (security == SecurityType.REALITY) "" else node.cipherSuites.ifBlank { settings.cipherSuites }
            val effectiveEchConfig = if (security == SecurityType.REALITY) "" else node.echConfigList.ifBlank { settings.echConfigList }
            val effectiveEchEnabled = security != SecurityType.REALITY && (settings.echEnabled || effectiveEchConfig.isNotBlank())
            val effectiveEchForceQuery = if (security == SecurityType.REALITY) {
                "none"
            } else if (node.echForceQuery.isNotBlank() && node.echForceQuery != "none") {
                node.echForceQuery
            } else {
                settings.echForceQuery
            }
            val effectiveFinalMask = node.finalMask.ifBlank {
                settings.finalMaskType.takeIf { it != "none" }.orEmpty()
            }

            when (security) {
                SecurityType.TLS -> {
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", effectiveSni)
                        put("fingerprint", effectiveFp)
                        put("allowInsecure", node.allowInsecure)
                        if (settings.tlsPaddingEnabled) {
                            put("padding", true)
                            put("tlsPadding", "rfc7685")
                        }
                        if (node.alpn.isNotBlank()) {
                            put("alpn", JSONArray(node.alpn.split(",").map { it.trim() }))
                        }
                        if (effectiveCipherSuites.isNotBlank()) {
                            put("cipherSuites", effectiveCipherSuites)
                        }
                        val effectiveFailClosed = settings.failClosedEch ||
                            effectiveEchForceQuery.equals("full", ignoreCase = true)
                        if (effectiveEchEnabled || effectiveFailClosed) {
                            if (effectiveEchConfig.isNotBlank()) {
                                put("echConfigList", effectiveEchConfig)
                            }
                            put("echForceQuery", if (effectiveFailClosed) "full" else effectiveEchForceQuery)
                            put("failClosedEch", effectiveFailClosed)
                        }
                    })
                }
                SecurityType.REALITY -> {
                    put("realitySettings", JSONObject().apply {
                        put("serverName", baseSni)
                        put("fingerprint", effectiveFp)
                        put("publicKey", node.publicKey)
                        put("shortId", node.shortId)
                        put("spiderX", "/")
                        put("fragment", settings.realityFragmentEnabled && settings.tlsFragmentEnabled)
                        if (node.mldsa65Verify.isNotBlank()) {
                            put("mldsa65Verify", node.mldsa65Verify)
                        }
                    })
                }
                SecurityType.NONE -> Unit
            }

            when (transport) {
                TransportType.WS -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", node.path.ifBlank { "/" })
                        if (node.hostHeader.isNotBlank()) {
                            put("host", node.hostHeader)
                        }
                    })
                }
                TransportType.GRPC -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", node.serviceName)
                        put("multiMode", true)
                    })
                }
                TransportType.XHTTP -> {
                    put("xhttpSettings", JSONObject().apply {
                        put("path", node.path.ifBlank { "/" })
                        if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
                        put("mode", settings.xhttpMode)
                        put("xPaddingBytes", settings.xhttpPaddingBytes)
                    })
                }
                TransportType.HTTPUPGRADE -> {
                    put("httpupgradeSettings", JSONObject().apply {
                        put("path", node.path.ifBlank { "/" })
                        if (node.hostHeader.isNotBlank()) put("host", node.hostHeader)
                    })
                }
                else -> Unit
            }

            if (effectiveFinalMask.isNotBlank() && effectiveFinalMask != "none") {
                val maskFamily = settings.finalMaskFamily.ifBlank { "tcp" }.lowercase()
                put("finalmask", JSONObject().apply {
                    put(maskFamily, JSONArray().put(buildFinalMaskObject(effectiveFinalMask)))
                    if (maskFamily == "oob") {
                        put("oob", JSONArray().put(buildFinalMaskObject("oob")))
                    }
                })
            }

            val isDatagramOutbound = com.example.core.ProtocolGuardrails.isDatagramProtocol(protocol)
            put(
                "sockopt",
                buildSockopt(
                    settings = settings,
                    includeDialerProxy = !dialerOverride.isNullOrBlank(),
                    dialerTarget = dialerOverride,
                    forceDisableTfo = isDatagramOutbound
                )
            )
        }

        outbound.put("streamSettings", stream)

        if (settings.muxEnabled && node.flow.isBlank() && !com.example.core.ProtocolGuardrails.isDatagramProtocol(protocol)) {
            outbound.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", settings.muxConcurrency)
                put("xudpConcurrency", settings.muxConcurrency)
                put("xudpProxyUDP443", if (settings.blockQuicUdp) "reject" else "allow")
            })
        }

        return outbound
    }
}
