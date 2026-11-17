package com.example

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
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
    fun configDoesNotEnableUnusedTrafficStatistics() {
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), StabilitySettings(), emptyList()))
        assertFalse(json.has("stats"))
        assertFalse(json.has("policy"))
    }

    @Test
    fun configKeepsAProxyOutboundAndACatchAllRule() {
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), StabilitySettings(), emptyList()))

        val outbounds = json.getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }
        assertTrue("proxy outbound missing, tags=$tags", tags.any { it == "proxy-out" || it == "proxy" })

        val rules = json.getJSONObject("routing").getJSONArray("rules")
        val last = rules.getJSONObject(rules.length() - 1)
        assertEquals("field", last.getString("type"))
        assertTrue(last.has("outboundTag"))
    }

}
