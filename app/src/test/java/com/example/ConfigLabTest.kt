package com.example

import com.example.core.Base64Compat
import com.example.core.ProxyUriParser
import com.example.data.import.LinkImporter
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Import-to-compiler contracts for the minimal official-core protocol subset. */
class ConfigLabTest {
    private val settings = StabilitySettings(experimentalModeEnabled = true)
    private val rules = emptyList<com.example.data.local.RoutingRuleEntity>()
    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val vmessLink = "vmess://" + Base64Compat.encodeToString(JSONObject()
        .put("v", "2").put("add", "vmess.example.com").put("port", "443").put("id", uuid)
        .put("aid", "0").put("scy", "auto").put("net", "ws").put("path", "/ray")
        .put("tls", "tls").put("sni", "vmess.example.com").toString().toByteArray())
    private val ssUser = Base64Compat.encodeToString("aes-256-gcm:secret".toByteArray())
    private val samples = mapOf(
        ProxyProtocol.VLESS to "vless://$uuid@vless.example.com:443?type=ws&security=tls&sni=sni.example.com&path=%2Fws",
        ProxyProtocol.VMESS to vmessLink,
        ProxyProtocol.TROJAN to "trojan://secret@trojan.example.com:443?sni=sni.example.com",
        ProxyProtocol.SHADOWSOCKS to "ss://$ssUser@ss.example.com:8388",
        ProxyProtocol.SOCKS to "socks5://user:pass@socks.example.com:1080"
    )

    @Test
    fun theLabCoversExactlyTheSupportedProtocols() {
        assertEquals(LinkImporter.compilableProtocols, samples.keys)
    }

    @Test
    fun everySupportedUriCompilesToItsOfficialOutbound() {
        for ((protocol, link) in samples) {
            val node = requireNotNull(ProxyUriParser.parseSingleProxyUri(link))
            val compiled = XrayConfigCompiler.compileXrayConfigObject(node, settings, rules)
            val outbounds = compiled.getJSONArray("outbounds")
            assertEquals(protocol.name.lowercase(), outbounds.getJSONObject(0).getString("protocol"))
            assertEquals(2, compiled.getJSONArray("inbounds").length())
        }
    }

    @Test
    fun persistedRoutingStrategiesCannotOverrideBuiltInPolicy() {
        val node = requireNotNull(ProxyUriParser.parseSingleProxyUri(samples.getValue(ProxyProtocol.VLESS)))
        val baseline = XrayConfigCompiler.compileXrayConfigObject(node, settings, rules).toString()
        for (strategy in listOf("IPIfNonMatch", "IPOnDemand", "AsIs", "UseIP", "UseIPv4", "UseIPv6")) {
            val compiled = XrayConfigCompiler.compileXrayConfigObject(
                node, settings.copy(domainStrategy = strategy), rules
            )
            assertEquals(baseline, compiled.toString())
        }
    }

    @Test
    fun experimentalAndUnsupportedUrisAreRejectedBeforeNodeCreation() {
        val links = mapOf(
            ProxyProtocol.HYSTERIA2 to "hy2://auth@h.example.com:443",
            ProxyProtocol.WIREGUARD to "wireguard://key@h.example.com:51820?publickey=key",
            ProxyProtocol.MASQUE to "masque://key@h.example.com:443?publickey=key",
            ProxyProtocol.HYSTERIA to "hysteria://auth@h.example.com:443",
            ProxyProtocol.TUIC to "tuic://uuid:pass@h.example.com:443",
            ProxyProtocol.ANYTLS to "anytls://pass@h.example.com:443",
            ProxyProtocol.SSH to "ssh://user:pass@h.example.com:22",
            ProxyProtocol.OPENCONNECT to "openconnect://user@h.example.com:443",
            ProxyProtocol.OPENVPN to "openvpn://user@h.example.com:1194",
            ProxyProtocol.DNSTT to "dnstt://h.example.com:53?publickey=key",
            ProxyProtocol.MASTERDNS to "masterdns://h.example.com:53?publickey=key",
            ProxyProtocol.HTTP to "http://user:pass@h.example.com:8080",
            ProxyProtocol.MIERU to "mieru://unsupported"
        )
        assertEquals(ProxyProtocol.entries.toSet() - samples.keys, links.keys)
        for ((protocol, link) in links) {
            assertNull("$protocol must not enter the connection pool", ProxyUriParser.parseSingleProxyUri(link))
        }
    }

    @Test
    fun rawVlessAndTrojanRemainRejectedRegardlessOfFormerOptIns() {
        val node = requireNotNull(ProxyUriParser.parseSingleProxyUri(samples.getValue(ProxyProtocol.VLESS)))
        for (protocol in listOf(ProxyProtocol.VLESS, ProxyProtocol.TROJAN)) {
            val failure = runCatching {
                XrayConfigCompiler.compileXrayConfigObject(
                    node.copy(protocol = protocol.name, security = SecurityType.NONE.name),
                    settings.copy(allowUnencryptedVless = true), rules
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("not supported"))
            assertFalse(failure?.message.orEmpty().contains(node.address))
            assertFalse(failure?.message.orEmpty().contains(node.uuidOrPassword))
        }
    }

    @Test
    fun encryptedVlessSurvivesImportAndCompilationWithoutExperimentalOptIn() {
        val key = Base64Compat.encodeToString(ByteArray(32) { it.toByte() }, urlSafe = true, noPadding = true)
        for (mode in listOf("native", "xorpub", "random")) {
            for (rtt in listOf("0rtt", "1rtt")) {
                val encryption = "mlkem768x25519plus.$mode.$rtt.$key"
                val node = requireNotNull(ProxyUriParser.parseSingleProxyUri(
                    "vless://$uuid@vless.example.com:443?security=none&type=tcp&headerType=none" +
                        "&flow=xtls-rprx-vision&encryption=$encryption"
                ))
                val config = XrayConfigCompiler.compileXrayConfigObject(node, StabilitySettings(), rules)
                val outbound = config.getJSONArray("outbounds").getJSONObject(0)
                assertEquals(encryption, outbound.getJSONObject("settings").getJSONArray("vnext")
                    .getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("encryption"))
                assertEquals("none", outbound.getJSONObject("streamSettings").getString("security"))
                assertEquals(encryption, ProxyUriParser.parseBatchWithReport(config.toString()).nodes.single().encryption)
            }
        }
    }

    @Test
    fun malformedVlessEncryptionCannotEnterParserOrCompiler() {
        val node = requireNotNull(ProxyUriParser.parseSingleProxyUri(samples.getValue(ProxyProtocol.VLESS)))
        for (encryption in listOf(
            "mlkem768x25519plus.native.1rtt." + "A".repeat(1578),
            "mlkem768x25519plus.xorpub.0rtt.short",
            "mlkem768x25519plus.xorpub.0rtt." + "!".repeat(43), "unknown"
        )) {
            assertNull(ProxyUriParser.parseSingleProxyUri(
                "vless://$uuid@vless.example.com:443?security=tls&encryption=$encryption"))
            assertTrue(runCatching {
                XrayConfigCompiler.compileXrayConfigObject(node.copy(encryption = encryption), settings, rules)
            }.isFailure)
        }
    }

    @Test
    fun unsupportedStoredProfilesFailInsteadOfCreatingABlackholeVpn() {
        for (protocol in ProxyProtocol.entries.toSet() - samples.keys) {
            val stored = ServerProfileEntity(
                protocol = protocol.name, address = "server.example.com", uuidOrPassword = "secret"
            )
            assertTrue("$protocol must fail before native startup", runCatching {
                XrayConfigCompiler.compileXrayConfigObject(stored, settings, rules)
            }.isFailure)
        }
    }
}
