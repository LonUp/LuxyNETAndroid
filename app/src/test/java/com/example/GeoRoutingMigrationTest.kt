package com.example

import com.example.core.GeoRulesEngine
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.CountryPolicyEntry
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.SocialServiceId
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestStage
import com.example.data.model.StabilitySettings
import com.example.xraycore.XrayConfigCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoRoutingMigrationTest {

    private fun testNode(isLocked: Boolean = false): ServerProfileEntity = ServerProfileEntity(
        id = "speed-geo-node",
        name = "Zurich Edge",
        protocol = ProxyProtocol.VLESS.name,
        address = "198.51.100.77",
        port = 443,
        uuidOrPassword = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        transport = "TCP",
        security = SecurityType.REALITY.name,
        sni = "www.cloudflare.com",
        publicKey = "reality-pbk-test",
        shortId = "12ab",
        isLocked = isLocked
    )


    @Test
    fun xrayConfigCompilerDoesNotExecuteStoredGeoAndCustomRoutingRules() {
        val settings = StabilitySettings(
            blockQuicUdp = false,
            domesticBypassEnabled = true,
            domesticCountryCode = "IR",
            blockAdsEnabled = true,
            blockPornEnabled = true,
            socialPolicies = mapOf(
                SocialServiceId.TELEGRAM to RuleAction.PROXY,
                SocialServiceId.YOUTUBE to RuleAction.DIRECT,
                SocialServiceId.INSTAGRAM to RuleAction.BLOCK
            ),
            countryPolicies = listOf(CountryPolicyEntry("CN", RuleAction.DIRECT))
        )

        val customProxyGeoip = RoutingRuleEntity(
            id = "custom-proxy-us",
            name = "Force US GeoIP Proxy",
            matchType = RuleMatchType.GEOIP.name,
            pattern = "us",
            action = RuleAction.PROXY.name,
            isEnabled = true,
            priority = 80
        )
        val customDirectSite = RoutingRuleEntity(
            id = "custom-direct-site",
            name = "Custom Local Service",
            matchType = RuleMatchType.GEOSITE.name,
            pattern = "private",
            action = RuleAction.DIRECT.name,
            isEnabled = true,
            priority = 90
        )

        val allRules = GeoRulesEngine.buildManagedGeoRules(
            settings = settings,
            existingRules = listOf(customProxyGeoip, customDirectSite)
        )

        val compiled = XrayConfigCompiler.compileXrayConfigObject(
            node = testNode(),
            settings = settings,
            rules = allRules
        )

        val outbounds = compiled.getJSONArray("outbounds")
        val outboundTags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
        assertTrue(outboundTags.contains("proxy-out"))
        assertEquals(listOf("proxy-out"), outboundTags)
        val compiledRules = compiled.getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, compiledRules.length())
        assertEquals("proxy-out", compiledRules.getJSONObject(0).getString("outboundTag"))
    }

}
