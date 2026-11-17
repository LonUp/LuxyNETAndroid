package com.example

import com.example.core.ClientPolicy
import com.example.core.ProtocolGuardrails
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.RoutingMode
import com.example.data.model.StabilitySettings
import com.example.data.model.TriState
import com.example.xraycore.XrayConfigCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalClientPolicyTest {
    private val vless = ServerProfileEntity(
        id = "stable", address = "server.example.com", uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        security = "TLS", transport = "TCP", sni = "server.example.com"
    )

    @Test
    fun onlyStableOfficialProtocolsAreEligible() {
        for (transport in listOf("TCP", "WS", "GRPC", "HTTPUPGRADE", "XHTTP")) {
            assertTrue(ClientPolicy.isSupported(vless.copy(transport = transport)))
        }
        assertTrue(ClientPolicy.isSupported(vless.copy(security = "REALITY", publicKey = "public-key")))
        assertTrue(ClientPolicy.isSupported(vless.copy(protocol = "VMESS", security = "NONE", encryption = "auto")))
        assertTrue(ClientPolicy.isSupported(vless.copy(protocol = "TROJAN")))
        assertTrue(ClientPolicy.isSupported(vless.copy(protocol = "SHADOWSOCKS", security = "NONE", encryption = "aes-256-gcm")))
        assertTrue(ClientPolicy.isSupported(vless.copy(protocol = "SOCKS", security = "NONE", uuidOrPassword = "")))
        assertTrue(ClientPolicy.isSupported(vless.copy(protocol = "SOCKS", security = "TLS", uuidOrPassword = "user:pass")))
        for (protocol in listOf("HYSTERIA2", "WIREGUARD", "AWG", "MASQUE", "DNSTT", "HTTP", "unknown")) {
            assertFalse(protocol, ClientPolicy.isSupported(vless.copy(protocol = protocol)))
        }
        for (transport in listOf("QUIC", "WG", "MASQUE", "DNSTT", "unknown")) {
            assertFalse(transport, ClientPolicy.isSupported(vless.copy(transport = transport)))
        }
        assertFalse(ClientPolicy.isSupported(vless.copy(security = "unknown")))
    }

    @Test
    fun insecureOrExperimentalProfileFeaturesAreRejectedWithoutDowngrading() {
        val rejected = listOf(
            vless.copy(security = "NONE"),
            vless.copy(encryption = "mlkem768x25519plus.native.1rtt." + "A".repeat(1578)),
            vless.copy(allowInsecure = true),
            vless.copy(fingerprint = "unsafe"),
            vless.copy(finalMask = "sudoku"),
            vless.copy(echConfigList = "ech"),
            vless.copy(echForceQuery = "full"),
            vless.copy(mldsa65Verify = "verification"),
            vless.copy(featureOverrides = NodeFeatureOverrides(fragment = TriState.ON).encode()),
            vless.copy(featureOverrides = NodeFeatureOverrides(noise = TriState.ON).encode()),
            vless.copy(featureOverrides = NodeFeatureOverrides(allowUnencryptedVless = TriState.ON).encode()),
            vless.copy(transport = "WS", flow = "xtls-rprx-vision"),
            vless.copy(protocol = "VMESS", security = "NONE", encryption = "none"),
            vless.copy(protocol = "SHADOWSOCKS", security = "NONE", encryption = "none")
        )
        for (profile in rejected) {
            assertFalse(ClientPolicy.isSupported(profile))
            assertTrue(runCatching {
                XrayConfigCompiler.compileXrayConfigObject(profile,
                    StabilitySettings(experimentalModeEnabled = true, allowUnencryptedVless = true), emptyList())
            }.isFailure)
        }
    }

    @Test
    fun invalidOrExpiredStoredProfilesNeverBecomeConnectionCandidates() {
        for (profile in listOf(
            vless.copy(address = ""), vless.copy(address = "bad host"), vless.copy(port = 0),
            vless.copy(port = 65536), vless.copy(uuidOrPassword = ""),
            vless.copy(isLocked = true, vaultExpiresAtMs = 1L)
        )) assertFalse(ClientPolicy.isSupported(profile))
    }

    @Test
    fun settingsProjectionPreservesUserIdentityButDisablesOldRuntimeComplexity() {
        val stored = StabilitySettings(
            activeProfileId = "chosen", language = "en", subscriptionUserAgent = "panel-client",
            experimentalModeEnabled = true, allowUnencryptedVless = true, muxEnabled = true,
            tlsFragmentEnabled = true, noiseEnabled = true, echEnabled = true, finalMaskType = "sudoku",
            adaptiveFallbackEnabled = true, smartFailover = true, autoConnectOnLaunch = true,
            autoReconnect = true, probeIntervalSec = 5, fakeDnsEnabled = true, sniffingEnabled = true,
            blockQuicUdp = true, routingMode = RoutingMode.BYPASS_LAN, chainingMode = ChainingMode.WARP_TO_PROXY,
            relayNodeId = "old-relay", appSplitTunnelMode = AppSplitTunnelMode.BLOCKLIST,
            splitTunnelPackages = "com.example.old", domesticBypassEnabled = true, blockAdsEnabled = true
        )
        val baseline = ClientPolicy.settings(stored)
        assertEquals(stored.activeProfileId, baseline.activeProfileId)
        assertEquals(stored.theme, baseline.theme)
        assertEquals(stored.language, baseline.language)
        assertEquals(stored.subscriptionUserAgent, baseline.subscriptionUserAgent)
        assertEquals(RoutingMode.GLOBAL, baseline.routingMode)
        assertEquals(ChainingMode.NONE, baseline.chainingMode)
        assertEquals(AppSplitTunnelMode.DISABLED, baseline.appSplitTunnelMode)
        assertEquals(0, baseline.probeIntervalSec)
        assertFalse(baseline.experimentalModeEnabled)
        assertFalse(baseline.allowUnencryptedVless)
        assertFalse(baseline.autoConnectOnLaunch)
        assertFalse(baseline.autoReconnect)
        assertFalse(baseline.smartFailover)
        assertFalse(baseline.adaptiveFallbackEnabled)
        assertFalse(baseline.muxEnabled)
        assertFalse(baseline.tlsFragmentEnabled)
        assertFalse(baseline.noiseEnabled)
        assertFalse(baseline.echEnabled)
        assertFalse(baseline.fakeDnsEnabled)
        assertFalse(baseline.sniffingEnabled)
        assertFalse(baseline.blockQuicUdp)
        assertFalse(baseline.domesticBypassEnabled)
        assertFalse(baseline.blockAdsEnabled)
        assertTrue(baseline.countryPolicies.isEmpty())
        assertTrue(baseline.socialPolicies.isEmpty())
        assertEquals(baseline, ClientPolicy.settings(baseline))
        assertTrue("projection must not mutate stored settings", stored.experimentalModeEnabled)
    }

    @Test
    fun oldRulesRelaysAndSettingsCannotExecuteInCompiledConfig() {
        val root = XrayConfigCompiler.compileXrayConfigObject(
            vless, StabilitySettings(experimentalModeEnabled = true, tlsFragmentEnabled = true,
                noiseEnabled = true, echEnabled = true, chainingMode = ChainingMode.NODE_TO_NODE,
                relayNodeId = "relay", muxEnabled = true),
            listOf(RoutingRuleEntity(name = "Old bypass", matchType = "DOMAIN_SUFFIX",
                pattern = "example.com", action = "DIRECT")), vless.copy(id = "relay")
        )
        val outbounds = root.getJSONArray("outbounds")
        val outbound = outbounds.getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        assertFalse(outbound.has("mux"))
        val stream = outbound.getJSONObject("streamSettings")
        assertFalse(stream.has("sockopt"))
        assertFalse(stream.has("finalmask"))
        assertFalse(stream.getJSONObject("tlsSettings").has("echConfigList"))
        val baseline = XrayConfigCompiler.compileXrayConfigObject(vless, StabilitySettings(), emptyList())
        assertEquals(baseline.toString(), root.toString())
        assertFalse(root.has("fakedns"))
        assertFalse(root.has("experimentalMode"))
        assertFalse(root.has("stats"))
        val policy = root.getJSONObject("policy")
        assertFalse(policy.has("system"))
        val level0 = policy.getJSONObject("levels").getJSONObject("0")
        assertFalse(level0.getBoolean("statsUserUplink"))
        assertFalse(level0.getBoolean("statsUserDownlink"))
        assertFalse(level0.getBoolean("statsUserOnline"))
        val inbounds = root.getJSONArray("inbounds")
        val socks = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
            .single { it.getString("protocol") == "socks" }
        assertEquals("127.0.0.1", socks.getString("listen"))
        assertFalse(socks.getJSONObject("settings").getBoolean("udp"))
    }

    @Test
    fun storageCleanupNeverConvertsUnsupportedProfilesIntoSupportedOnes() {
        val original = vless.copy(protocol = "MASQUE", transport = "MASQUE", security = "NONE",
            finalMask = "sudoku", echConfigList = "ech", featureOverrides = "{\"fragment\":\"ON\"}")
        val cleaned = ProtocolGuardrails.sanitizeProfileForSave(original)
        assertEquals(original.protocol, cleaned.protocol)
        assertEquals(original.transport, cleaned.transport)
        assertEquals(original.security, cleaned.security)
        assertEquals(original.finalMask, cleaned.finalMask)
        assertEquals(original.echConfigList, cleaned.echConfigList)
        assertEquals(original.featureOverrides, cleaned.featureOverrides)
        assertFalse(ClientPolicy.isSupported(cleaned))
    }

}
