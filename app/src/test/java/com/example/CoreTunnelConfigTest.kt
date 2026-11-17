package com.example

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.xraycore.BuiltInNetworkDefaults
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the config handed to the native core (ADR 0001).
 *
 * The core ignores unknown JSON keys. These assertions pin the TUN contract and prevent
 * unused statistics collection from returning to the import-and-connect runtime.
 */
class CoreTunnelConfigTest {

    private fun node(): ServerProfileEntity = ServerProfileEntity(
        id = "node-core",
        name = "Core Contract",
        protocol = ProxyProtocol.VLESS.name,
        address = "203.0.113.7",
        port = 443,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        transport = "TCP",
        security = SecurityType.REALITY.name,
        sni = "www.cloudflare.com",
        publicKey = "test-pbk",
        shortId = "abcd"
    )

    @Test
    fun configUsesTheFixedBaselineMtuRegardlessOfFormerSettings() {
        val settings = StabilitySettings(mtu = 1360)
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), settings, emptyList()))

        val inbounds = json.getJSONArray("inbounds")
        val protocols = (0 until inbounds.length()).map { inbounds.getJSONObject(it).getString("protocol") }

        assertTrue("tun inbound required for fd-based tunnelling, got $protocols", protocols.contains("tun"))
        assertTrue("loopback SOCKS inbound required for connection verification", protocols.contains("socks"))

        val tun = (0 until inbounds.length())
            .map { inbounds.getJSONObject(it) }
            .first { it.getString("protocol") == "tun" }
        assertEquals(1400, tun.getJSONObject("settings").getInt("MTU"))
    }

    @Test
    fun iranUsesIpv4OnlyAnswersWhileOtherRegionsStayDualStack() {
        assertEquals("UseIPv4", BuiltInNetworkDefaults.dns("ir").getString("queryStrategy"))
        assertEquals("UseIP", BuiltInNetworkDefaults.dns("us").getString("queryStrategy"))
        assertEquals("UseIP", BuiltInNetworkDefaults.dns(null).getString("queryStrategy"))
        val servers = BuiltInNetworkDefaults.dns("ir").getJSONArray("servers")
        val localServer = servers.getJSONObject(0)
        assertEquals("localhost", localServer.getString("address"))
        assertTrue(localServer.getBoolean("finalQuery"))
        assertTrue(localServer.getBoolean("skipFallback"))
        val domesticServer = servers.getJSONObject(1)
        assertEquals("localhost", domesticServer.getString("address"))
        assertTrue(domesticServer.getBoolean("skipFallback"))
        val domestic = domesticServer.getJSONArray("domains")
        assertTrue(
            "Iranian services must bypass the tunneled DoH resolver",
            (0 until domestic.length()).map(domestic::getString).contains("domain:varzesh3.com")
        )
    }

    @Test
    fun configDoesNotEnableUnusedTrafficStatistics() {
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), StabilitySettings(), emptyList()))
        assertFalse(json.has("stats"))
        val policy = json.getJSONObject("policy")
        assertFalse(policy.has("system"))
        val level0 = policy.getJSONObject("levels").getJSONObject("0")
        assertFalse(level0.getBoolean("statsUserUplink"))
        assertFalse(level0.getBoolean("statsUserDownlink"))
        assertFalse(level0.getBoolean("statsUserOnline"))
        assertEquals(64, level0.getInt("bufferSize"))
    }

    @Test
    fun unmatchedTrafficDefaultsToTheProxyWithoutRedundantRoutingDnsLookups() {
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), StabilitySettings(), emptyList()))

        assertEquals("proxy-out", json.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        val routing = json.getJSONObject("routing")
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            assertTrue("Every rule must be scoped so unmatched traffic falls through to proxy-out",
                rule.has("inboundTag") || rule.has("domain") || rule.has("ip"))
        }
        val hasPrivateDnsProbeBlock = (0 until rules.length()).map { rules.getJSONObject(it) }.any { rule ->
            rule.getString("outboundTag") == "block-out" &&
                rule.optString("port") == "853" &&
                rule.optJSONArray("ip")?.let { arr -> (0 until arr.length()).any { arr.getString(it) == BuiltInNetworkDefaults.VPN_DNS } } == true
        }
        assertTrue("Opportunistic Private DNS probes to VPN_DNS:853 must be blocked", hasPrivateDnsProbeBlock)
    }

    @Test
    fun dnsOutboundForwardsNonAddressQueriesAndRejectsLocalNonIpQueries() {
        val dnsOut = BuiltInNetworkDefaults.supportingOutbounds().first { it.getString("tag") == "dns-out" }
        val rules = dnsOut.getJSONObject("settings").getJSONArray("rules")
        assertEquals("return", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack", rules.getJSONObject(1).getString("action"))
        assertEquals("return", rules.getJSONObject(2).getString("action"))
        assertEquals("direct", rules.getJSONObject(rules.length() - 1).getString("action"))
    }

}
