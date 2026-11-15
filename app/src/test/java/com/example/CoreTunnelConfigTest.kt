package com.example

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.xraycore.CoreStatsParser
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the config handed to the native core (ADR 0001).
 *
 * The core silently ignores unknown JSON keys, so a wrong (or missing) `tun` inbound or
 * `stats` block would only surface as "connects, but nothing is tunnelled" on a device.
 * These assertions pin the parts the runtime depends on.
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
    fun configDeclaresTunInboundWithConfiguredMtu() {
        val settings = StabilitySettings().copy(mtu = 1360)
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), settings, emptyList()))

        val inbounds = json.getJSONArray("inbounds")
        val protocols = (0 until inbounds.length()).map { inbounds.getJSONObject(it).getString("protocol") }

        assertTrue("tun inbound required for fd-based tunnelling, got $protocols", protocols.contains("tun"))
        assertTrue("socks inbound kept for internal probes", protocols.contains("socks"))

        val tun = (0 until inbounds.length())
            .map { inbounds.getJSONObject(it) }
            .first { it.getString("protocol") == "tun" }
        assertEquals(1360, tun.getJSONObject("settings").getInt("MTU"))
    }

    @Test
    fun configEnablesOutboundTrafficStatsForTelemetry() {
        val json = JSONObject(XrayConfigCompiler.compileXrayConfig(node(), StabilitySettings(), emptyList()))

        assertTrue("stats section is required by queryAllOutboundTrafficStats", json.has("stats"))
        val system = json.getJSONObject("policy").getJSONObject("system")
        assertTrue(system.getBoolean("statsOutboundUplink"))
        assertTrue(system.getBoolean("statsOutboundDownlink"))
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

    @Test
    fun statsParserSumsBothDirectionsAndIgnoresGarbage() {
        val parsed = CoreStatsParser.parse("proxy,up,1200;proxy,down,34000;broken;x,sideways,9;")
        assertEquals(1200L, parsed.uplinkBytes)
        assertEquals(34000L, parsed.downlinkBytes)

        val empty = CoreStatsParser.parse(null)
        assertEquals(0L, empty.uplinkBytes)
        assertEquals(0L, empty.downlinkBytes)
    }
}
