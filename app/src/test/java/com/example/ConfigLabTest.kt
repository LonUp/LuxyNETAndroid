package com.example

import com.example.core.Base64Compat
import com.example.core.ProxyUriParser
import com.example.data.import.LinkImporter
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.xraycore.XrayConfigCompiler
import com.example.i18n.UiText
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.TunnelBackend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Config lab (P3 item 17): every importable link type goes through the real parser and the real
 * config compiler, so a protocol that stops compiling breaks the build instead of the user.
 *
 * This is the compile-level half of the reference's `lab` job — it cannot open sockets, so a
 * handshake test on a device is still required before a release (documented in the plan).
 */
class ConfigLabTest {

    // The lab compiles the *full* config: MASQUE is experimental-gated and would otherwise be
    // emitted as a deliberate blackhole (see the standard-mode test below).
    private val settings = StabilitySettings(experimentalModeEnabled = true)
    private val rules = emptyList<com.example.data.local.RoutingRuleEntity>()

    private val vmessLink: String = run {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", "vmess-lab")
            put("add", "vmess.example.com")
            put("port", "443")
            put("id", "11111111-2222-3333-4444-555555555555")
            put("aid", "0")
            put("scy", "auto")
            put("net", "ws")
            put("type", "none")
            put("host", "cdn.example.com")
            put("path", "/ray")
            put("tls", "tls")
            put("sni", "cdn.example.com")
        }.toString()
        "vmess://" + Base64Compat.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private val ssLink: String = run {
        val userInfo = Base64Compat.encodeToString("aes-256-gcm:secret".toByteArray(Charsets.UTF_8))
        "ss://$userInfo@ss.example.com:8388#ss-lab"
    }

    private val wgKey: String = Base64Compat.encodeToString(ByteArray(32) { (it + 1).toByte() })

    /** Protocol → (sample link, the `protocol` value the compiler must emit). */
    private val linkSamples: Map<ProxyProtocol, Pair<String, String>> = mapOf(
        ProxyProtocol.VLESS to (
            "vless://11111111-2222-3333-4444-555555555555@vless.example.com:443?type=ws&security=tls&sni=sni.example.com&path=%2Fws#vless-lab"
                to "vless"
            ),
        ProxyProtocol.VMESS to (vmessLink to "vmess"),
        ProxyProtocol.TROJAN to ("trojan://secret@trojan.example.com:443?sni=sni.example.com#trojan-lab" to "trojan"),
        ProxyProtocol.SHADOWSOCKS to (ssLink to "shadowsocks"),
        ProxyProtocol.SOCKS to ("socks5://user:pass@socks.example.com:1080#socks-lab" to "socks"),
        ProxyProtocol.HYSTERIA2 to ("hy2://auth@hy2.example.com:443?sni=sni.example.com#hy2-lab" to "hysteria"),
        ProxyProtocol.WIREGUARD to ("wireguard://$wgKey@wg.example.com:51820?publickey=$wgKey#wg-lab" to "wireguard"),
        ProxyProtocol.MASQUE to ("masque://$wgKey@masque.example.com:443?publickey=$wgKey#masque-lab" to "masque")
    )

    /**
     * HTTP proxies have no share scheme in the reference catalogue (37 aliases, none of them
     * `http://`), so they reach the compiler only through the manual add-server form. The lab
     * builds the profile directly rather than pretending a link exists.
     */
    private val manualNodes: Map<ProxyProtocol, ServerProfileEntity> = mapOf(
        ProxyProtocol.HTTP to ServerProfileEntity(
            name = "http-lab",
            protocol = ProxyProtocol.HTTP.name,
            address = "proxy.example.com",
            port = 8080,
            uuidOrPassword = "user:pass",
            security = SecurityType.NONE.name
        )
    )

    private val labProtocols: Set<ProxyProtocol> get() = linkSamples.keys + manualNodes.keys

    private fun compile(link: String): JSONObject {
        val node = ProxyUriParser.parseSingleProxyUri(link)
            ?: fail("parser rejected a supported link: $link").let { error("unreachable") }
        return XrayConfigCompiler.compileXrayConfigObject(node, settings, rules)
    }

    private fun compileFor(protocol: ProxyProtocol): JSONObject =
        linkSamples[protocol]?.let { compile(it.first) }
            ?: XrayConfigCompiler.compileXrayConfigObject(manualNodes.getValue(protocol), settings, rules)

    private fun expectedOutProto(protocol: ProxyProtocol): String =
        linkSamples[protocol]?.second ?: when (protocol) {
            ProxyProtocol.HTTP -> "http"
            else -> error("no expected outbound for $protocol")
        }

    /**
     * Guard rail for the class of bug that broke 3.0.0 at runtime: an enum-typed field that
     * accepts a value the core rejects. The core's own parser is the authority, so these lists
     * mirror `infra/conf` (freedom: AsIs/UseIP/UseIPv4/UseIPv6, routing: AsIs/IPIfNonMatch/
     * IPOnDemand) and the lab fails before a release can ship such a config again.
     */
    private fun assertEnumsValid(json: JSONObject) {
        val freedomStrategies = setOf("AsIs", "UseIP", "UseIPv4", "UseIPv6")
        val routingStrategies = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")
        val protocols = setOf(
            "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard",
            "masque", "dnstt", "masterdns", "freedom", "blackhole"
        )

        val outbounds = json.getJSONArray("outbounds")
        (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.forEach { outbound ->
            val protocol = outbound.optString("protocol")
            assertTrue("unknown outbound protocol '$protocol'", protocol in protocols)
            val settings = outbound.optJSONObject("settings")
            if (protocol == "freedom") {
                settings?.optString("domainStrategy")?.takeIf { it.isNotEmpty() }?.let { strategy ->
                    assertTrue(
                        "freedom outbound carries routing-only strategy '$strategy'",
                        strategy in freedomStrategies
                    )
                }
            }
            outbound.optJSONObject("streamSettings")
                ?.optJSONObject("sockopt")
                ?.optString("domainStrategy")
                ?.takeIf { it.isNotEmpty() }
                ?.let { strategy ->
                    assertTrue("sockopt strategy '$strategy' is invalid", strategy in freedomStrategies)
                }
        }

        val routing = json.optJSONObject("routing")
        if (routing != null) {
            val strategy = routing.optString("domainStrategy")
            assertTrue("routing strategy '$strategy' is invalid", strategy in routingStrategies)
        }
    }

    private fun outboundProtocols(json: JSONObject): List<String> {
        val outbounds = json.getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("protocol") }
    }

    @Test
    fun theLabCoversExactlyTheCompilableProtocols() {
        assertEquals(
            "add a sample here whenever LinkImporter starts treating a protocol as compilable",
            LinkImporter.compilableProtocols,
            labProtocols
        )
    }

    @Test
    fun everyCompiledProtocolProducesItsOutbound() {
        labProtocols.forEach { protocol ->
            val expectedOutProto = expectedOutProto(protocol)
            val json = compileFor(protocol)
            val protocols = outboundProtocols(json)
            assertTrue(
                "$protocol compiled without a `$expectedOutProto` outbound (got $protocols)",
                expectedOutProto in protocols
            )
            // A config that parses but has no inbound would still fail on device.
            assertTrue("$protocol produced no inbound", json.getJSONArray("inbounds").length() > 0)
            assertTrue("$protocol produced no routing rules", json.has("routing"))
            assertEnumsValid(json)
        }
    }

    @Test
    fun freedomOutboundNeverCarriesARoutingOnlyStrategy() {
        // IPIfNonMatch is the shipped default and the exact value that broke 3.0.0.
        listOf("IPIfNonMatch", "IPOnDemand", "AsIs", "UseIP", "UseIPv4", "UseIPv6").forEach { strategy ->
            val node = ProxyUriParser.parseSingleProxyUri(linkSamples.getValue(ProxyProtocol.VLESS).first)
            requireNotNull(node)
            val json = XrayConfigCompiler.compileXrayConfigObject(
                node,
                StabilitySettings(domainStrategy = strategy),
                rules
            )
            assertEnumsValid(json)
            val direct = (0 until json.getJSONArray("outbounds").length())
                .map { json.getJSONArray("outbounds").getJSONObject(it) }
                .first { it.optString("tag") == "direct" }
            val emitted = direct.getJSONObject("settings").getString("domainStrategy")
            val expected = when (strategy) {
                "UseIP", "UseIPv4", "UseIPv6" -> strategy
                else -> "AsIs"
            }
            assertEquals("strategy '$strategy' must not leak onto the direct outbound", expected, emitted)
            assertEquals(
                XrayConfigCompiler.routingDomainStrategy(strategy),
                json.getJSONObject("routing").getString("domainStrategy")
            )
        }
    }

    @Test
    fun unsupportedProtocolsAreRejectedBeforeNodeCreation() {
        val unsupported = ProxyProtocol.entries.toSet() - LinkImporter.compilableProtocols
        val links = mapOf(
            ProxyProtocol.HYSTERIA to "hysteria://auth@h.example.com:443#hy1",
            ProxyProtocol.TUIC to "tuic://uuid:pass@h.example.com:443?sni=sni.example.com#tuic",
            ProxyProtocol.ANYTLS to "anytls://pass@h.example.com:443?sni=sni.example.com#anytls",
            ProxyProtocol.SSH to "ssh://user:pass@h.example.com:22#ssh",
            ProxyProtocol.OPENCONNECT to "openconnect://user@h.example.com:443#oc",
            ProxyProtocol.OPENVPN to "openvpn://user@h.example.com:1194#ovpn",
            ProxyProtocol.DNSTT to "dnstt://dns.example.com:53?publickey=pubkey#dnstt",
            ProxyProtocol.MASTERDNS to "masterdns://md.example.com:53?publickey=pubkey#md",
            ProxyProtocol.MIERU to ("mieru://" + Base64Compat.encodeToString(
                JSONObject().apply {
                    put("server", "mieru.example.com")
                    put("port", 8443)
                    put("password", "pw")
                    put("method", "AES-128-GCM")
                }.toString().toByteArray(Charsets.UTF_8)
            ))
        )
        assertEquals("every uncompiled core protocol needs a rejection case", unsupported, links.keys)
        links.forEach { (protocol, link) ->
            assertNull("$protocol must not be created by the Xray import parser", ProxyUriParser.parseSingleProxyUri(link))
        }
    }

    @Test
    fun forkUnencryptedOutboundSupportRemainsExplicitlyGated() {
        val unencryptedVless = ServerProfileEntity(
            id = "unencrypted-vless",
            name = "VLESS without TLS",
            protocol = ProxyProtocol.VLESS.name,
            address = "vless.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = "TCP",
            security = SecurityType.NONE.name
        )
        val blocked = runCatching {
            XrayConfigCompiler.compileXrayConfigObject(unencryptedVless, settings, rules)
        }.exceptionOrNull()
        assertTrue(
            "unencrypted VLESS must stay opt-in",
            blocked is IllegalStateException && blocked.message.orEmpty().contains("Unencrypted VLESS is disabled")
        )
        val backend = object : TunnelBackend {
            override val label = "test"
            override val isRealCore = true
            override fun start(configJson: String, tunFd: Int): Result<Unit> =
                error("invalid profile must fail before starting the backend")
            override fun stop() = Unit
            override val isRunning = false
            override fun trafficTotals(): Pair<Long, Long> = 0L to 0L
            override fun measureThroughTunnel(url: String): Long? = null
        }
        val core = LuxyVpnCore(backend)
        val lastGoodConfig = """{"outbounds":[]}"""
        core.applyConfig(lastGoodConfig)
        val startResult = core.startCore(unencryptedVless, settings, rules)
        assertTrue("compile failure should be returned, not thrown", startResult.isFailure)
        assertEquals(lastGoodConfig, core.lastCompiledConfigJson)
        val failureMessage = startResult.exceptionOrNull()?.message.orEmpty()
        assertTrue(failureMessage.contains("Unencrypted VLESS is disabled"))
        assertEquals(
            "اتصال VLESS بدون رمزنگاری غیرفعال است؛ برای فعال‌کردن، گزینهٔ آزمایشی مربوط را روشن کنید",
            UiText.translate("fa", failureMessage)
        )


        val allowed = XrayConfigCompiler.compileXrayConfigObject(
            unencryptedVless,
            settings.copy(allowUnencryptedVless = true),
            rules
        )
        val proxy = (0 until allowed.getJSONArray("outbounds").length())
            .map { allowed.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.optString("tag") == "proxy-out" }
        assertEquals("none", proxy.getJSONObject("streamSettings").getString("security"))

        val unencryptedTrojan = unencryptedVless.copy(
            protocol = ProxyProtocol.TROJAN.name,
            uuidOrPassword = "password"
        )
        val trojanBlocked = runCatching {
            XrayConfigCompiler.compileXrayConfigObject(
                unencryptedTrojan,
                settings.copy(allowUnencryptedVless = true),
                rules
            )
        }.exceptionOrNull()
        assertTrue(
            "Trojan never inherits the VLESS unencrypted opt-in",
            trojanBlocked is IllegalStateException && trojanBlocked.message.orEmpty().contains("Unencrypted Trojan")
        )
    }

    @Test
    fun standardModeKeepsExperimentalProtocolsFailClosed() {
        val standard = StabilitySettings(experimentalModeEnabled = false)
        // MASQUE is the only experimental-gated protocol the core actually implements; DNSTT
        // and MasterDNS are absent from libgojni.so and now refuse to compile at all.
        listOf(ProxyProtocol.MASQUE).forEach { protocol ->
            val (link, _) = linkSamples.getValue(protocol)
            val node = ProxyUriParser.parseSingleProxyUri(link)
            requireNotNull(node) { "$protocol sample was rejected by the parser" }
            val json = XrayConfigCompiler.compileXrayConfigObject(node, standard, rules)
            val outbounds = json.getJSONArray("outbounds")
            val proxy = (0 until outbounds.length())
                .map { outbounds.getJSONObject(it) }
                .first { it.optString("tag") == "proxy-out" }
            assertEquals("$protocol must not leak traffic in standard mode", "blackhole", proxy.getString("protocol"))
            assertTrue("$protocol gate must be marked for the UI", proxy.optBoolean("experimentalGated"))
        }
    }

    @Test
    fun compiledConfigsCarryNoPlaintextSecretsInLogs() {
        // Redacted compile is what the inspector and bug reports show; it must not include keys.
        val node = ProxyUriParser.parseSingleProxyUri(linkSamples.getValue(ProxyProtocol.WIREGUARD).first)
        requireNotNull(node)
        val redacted = XrayConfigCompiler.compileRedactedForInspector(
            node.copy(isLocked = true), settings, rules
        )
        assertTrue(redacted.contains(com.example.core.EncryptedConfigVault.VAULT_REDACTED_VALUE))
        assertTrue(!redacted.contains(wgKey))
    }
}
