package com.example

import com.example.core.ClientPolicy
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
    fun compilerNeverEnablesMuxOnXhttp() {
        val compiled = XrayConfigCompiler.compileXrayConfigObject(
            vless(transport = "XHTTP"),
            StabilitySettings(experimentalModeEnabled = true, muxEnabled = true), emptyList()
        )
        assertFalse(compiled.getJSONArray("outbounds").getJSONObject(0).has("mux"))
    }

    @Test
    fun compilerNeverEnablesMuxFromSavedGlobalSettings() {
        val compiled = XrayConfigCompiler.compileXrayConfigObject(
            vless(overrides = NodeFeatureOverrides(mux = TriState.OFF)),
            StabilitySettings(muxEnabled = true), emptyList()
        )
        assertFalse(compiled.getJSONArray("outbounds").getJSONObject(0).has("mux"))
    }

    @Test
    fun profileCannotEnableFragmentFromLegacyOverrides() {
        val node = vless(overrides = NodeFeatureOverrides(fragment = TriState.ON, fragmentLength = "3-8"))
        assertFalse(ClientPolicy.isSupported(node))
        assertTrue(runCatching {
            XrayConfigCompiler.compileXrayConfigObject(
                node, StabilitySettings(experimentalModeEnabled = true), emptyList()
            )
        }.isFailure)
    }

    @Test
    fun experimentalOverrideIsRejectedEvenInFormerExperimentalMode() {
        val node = vless(overrides = NodeFeatureOverrides(fragment = TriState.ON, ech = TriState.ON))
        assertFalse(ClientPolicy.isSupported(node))
    }

    @Test
    fun visionFlowNeverEnablesMux() {
        val compiled = XrayConfigCompiler.compileXrayConfigObject(
            vless(flow = "xtls-rprx-vision", overrides = NodeFeatureOverrides(mux = TriState.ON)),
            StabilitySettings(muxEnabled = true), emptyList()
        )
        assertFalse(compiled.getJSONArray("outbounds").getJSONObject(0).has("mux"))
    }

    @Test
    fun importedEchCannotBeSilentlyDowngradedByAnOverride() {
        val node = vless(echConfig = "ech-blob", overrides = NodeFeatureOverrides(ech = TriState.OFF))
        assertFalse(ClientPolicy.isSupported(node))
    }

    @Test
    fun legacyMuxOverrideCannotTurnMuxBackOn() {
        for (choice in TriState.entries) {
            val compiled = XrayConfigCompiler.compileXrayConfigObject(
                vless(overrides = NodeFeatureOverrides(mux = choice, muxConcurrency = 4)),
                StabilitySettings(experimentalModeEnabled = true, muxEnabled = true), emptyList()
            )
            assertFalse(compiled.getJSONArray("outbounds").getJSONObject(0).has("mux"))
        }
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
