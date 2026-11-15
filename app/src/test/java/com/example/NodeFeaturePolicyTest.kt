package com.example

import com.example.core.NodeFeaturePolicy
import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TriState
import com.example.xraycore.XrayConfigCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFeaturePolicyTest {

    private fun vless(
        transport: String = "TCP",
        security: String = SecurityType.TLS.name,
        flow: String = "",
        overrides: NodeFeatureOverrides = NodeFeatureOverrides(),
        echConfig: String = ""
    ) = ServerProfileEntity(
        id = "feat-node",
        name = "Feature node",
        protocol = ProxyProtocol.VLESS.name,
        address = "203.0.113.9",
        port = 443,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        transport = transport,
        security = security,
        sni = "www.example.com",
        flow = flow,
        echConfigList = echConfig,
        featureOverrides = overrides.encode()
    )

    @Test
    fun emptyOverridesStillForbidMuxOnXhttp() {
        val settings = StabilitySettings(experimentalModeEnabled = true, muxEnabled = true)
        val applied = NodeFeaturePolicy.apply(settings, vless(transport = "XHTTP"))
        assertFalse(applied.muxEnabled)
    }

    @Test
    fun profileCanDisableMuxThatTheGlobalSettingEnables() {
        val settings = StabilitySettings(muxEnabled = true, muxConcurrency = 8)
        val applied = NodeFeaturePolicy.apply(
            settings,
            vless(overrides = NodeFeatureOverrides(mux = TriState.OFF))
        )
        assertFalse(applied.muxEnabled)
        assertEquals(8, applied.muxConcurrency)
    }

    @Test
    fun profileCanEnableFragmentWhileGlobalIsOff() {
        val settings = StabilitySettings(experimentalModeEnabled = true, tlsFragmentEnabled = false)
        val applied = NodeFeaturePolicy.apply(
            settings,
            vless(overrides = NodeFeatureOverrides(fragment = TriState.ON, fragmentLength = "3-8"))
        )
        assertTrue(applied.tlsFragmentEnabled)
        assertEquals("3-8", applied.fragmentLength)
        assertFalse("fragment and TFO are mutually exclusive", applied.tcpFastOpen)
    }

    @Test
    fun experimentalOverrideDoesNotLeakIntoStandardMode() {
        val settings = StabilitySettings(experimentalModeEnabled = false, tlsFragmentEnabled = true)
        val applied = NodeFeaturePolicy.apply(
            settings.asEffectiveBaseline(),
            vless(overrides = NodeFeatureOverrides(fragment = TriState.ON, ech = TriState.ON))
        )
        assertFalse(applied.tlsFragmentEnabled)
        assertFalse(applied.echEnabled)
    }

    @Test
    fun visionFlowForcesMuxOff() {
        val applied = NodeFeaturePolicy.apply(
            StabilitySettings(muxEnabled = true),
            vless(flow = "xtls-rprx-vision", overrides = NodeFeatureOverrides(mux = TriState.ON))
        )
        assertFalse(applied.muxEnabled)
    }

    @Test
    fun compilerOmitsEchWhenTheProfileForcesItOff() {
        val node = vless(
            echConfig = "ech-blob",
            overrides = NodeFeatureOverrides(ech = TriState.OFF)
        )
        val compiled = XrayConfigCompiler.compileXrayConfigObject(
            node,
            StabilitySettings(experimentalModeEnabled = true, echEnabled = true),
            emptyList()
        )
        val tls = compiled.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("tlsSettings")
        assertFalse(tls.has("echConfigList"))
        assertFalse(tls.has("echForceQuery"))
    }

    @Test
    fun compilerEmitsMuxOnlyWhenTheProfileAllowsIt() {
        val off = XrayConfigCompiler.compileXrayConfigObject(
            vless(overrides = NodeFeatureOverrides(mux = TriState.OFF)),
            StabilitySettings(muxEnabled = true),
            emptyList()
        )
        assertFalse(off.getJSONArray("outbounds").getJSONObject(0).has("mux"))

        val on = XrayConfigCompiler.compileXrayConfigObject(
            vless(overrides = NodeFeatureOverrides(mux = TriState.ON, muxConcurrency = 4)),
            StabilitySettings(muxEnabled = false),
            emptyList()
        )
        val mux = on.getJSONArray("outbounds").getJSONObject(0).getJSONObject("mux")
        assertTrue(mux.getBoolean("enabled"))
        assertEquals(4, mux.getInt("concurrency"))
    }

    @Test
    fun overrideJsonRoundTrips() {
        val original = NodeFeatureOverrides(
            mux = TriState.OFF,
            ech = TriState.ON,
            echForceQuery = "full",
            noise = TriState.ON,
            noiseType = "rand",
            spiderX = "/scan"
        )
        assertEquals(original, NodeFeatureOverrides.decode(original.encode()))
        assertEquals(NodeFeatureOverrides(), NodeFeatureOverrides.decode("not-json"))
    }
}
