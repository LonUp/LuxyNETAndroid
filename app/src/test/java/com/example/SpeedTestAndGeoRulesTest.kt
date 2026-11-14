package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.core.OkHttpSpeedProbeRunner
import com.example.core.SpeedProbeRunner
import com.example.core.SpeedTestEngine
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
import com.example.vpn.SplitTunnelEngine
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.SocketFactory

class SpeedTestAndGeoRulesTest {

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
    fun speedTestEngineConfiguresDualModesCalculatesMetricsAndCancelsCleanly() = runTest {
        // Verify OkHttpClient wiring for TUNNELED (SOCKS5) vs DIRECT (Protected Bypass SocketFactory)
        val probeRunner = OkHttpSpeedProbeRunner(activeSocksPort = 10808)
        val tunneledClient = probeRunner.buildClient(SpeedTestMode.TUNNELED)
        val tunneledProxy = tunneledClient.proxy
        assertNotNull(tunneledProxy)
        assertEquals(Proxy.Type.SOCKS, tunneledProxy!!.type())
        val socksAddr = tunneledProxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", socksAddr.hostString)
        assertEquals(10808, socksAddr.port)

        val directClient = probeRunner.buildClient(SpeedTestMode.DIRECT)
        assertNull(directClient.proxy)
        assertNotEquals(SocketFactory.getDefault(), directClient.socketFactory)

        // Verify math accuracy: median RTT, mean delta jitter, and Mbps
        assertEquals(24.5, SpeedTestEngine.computeMedian(listOf(30.0, 20.0, 25.0, 24.0, 22.0, 28.0)), 0.01)
        assertEquals(6.6, SpeedTestEngine.computeJitter(listOf(20.0, 30.0, 22.0, 28.0, 24.0, 29.0)), 0.01)
        assertEquals(50.0, SpeedTestEngine.computeMbps(bytes = 25_000_000L, elapsedMs = 4_000L), 0.01)

        // Locked profile preserves Vault Lock redaction on SpeedTest exit label
        val lockedNode = testNode(isLocked = true)
        val lockedLabel = SpeedTestEngine.resolveSafeExitLabel(SpeedTestMode.TUNNELED, lockedNode)
        assertFalse(lockedLabel.contains("198.51.100.77"))
        assertTrue(lockedLabel.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))

        val mockRunner = object : SpeedProbeRunner {
            private val rtts = listOf(20.0, 24.0, 22.0, 28.0, 26.0, 30.0)
            private var i = 0
            override suspend fun measurePing204(mode: SpeedTestMode): Double = rtts[i++ % rtts.size]
            override suspend fun measureDownloadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ): Pair<Long, Long> {
                onSample(250L, 1_500_000L, 48.0)
                return 30_000_000L to 5_000L // 48.0 Mbps
            }

            override suspend fun measureUploadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ): Pair<Long, Long> {
                onSample(250L, 750_000L, 24.0)
                return 15_000_000L to 5_000L // 24.0 Mbps
            }
        }

        // Run both TUNNELED (connected) and DIRECT modes end-to-end
        val tunneledEmissions = SpeedTestEngine.runDualModeSpeedTest(
            mode = SpeedTestMode.TUNNELED,
            connectionState = ConnectionState.CONNECTED,
            activeNode = lockedNode,
            runner = mockRunner
        ).toList()
        val tunneledResult = tunneledEmissions.last().second!!
        assertEquals(SpeedTestMode.TUNNELED, tunneledResult.mode)
        assertEquals(25.0, tunneledResult.pingMs, 0.01)
        assertEquals(3.6, tunneledResult.jitterMs, 0.01)
        assertEquals(48.0, tunneledResult.downloadMbps, 0.01)
        assertEquals(24.0, tunneledResult.uploadMbps, 0.01)
        assertEquals(SpeedTestStage.COMPLETE, tunneledEmissions.last().first.stage)

        val directEmissions = SpeedTestEngine.runDualModeSpeedTest(
            mode = SpeedTestMode.DIRECT,
            connectionState = ConnectionState.CONNECTED,
            activeNode = lockedNode,
            runner = mockRunner
        ).toList()
        val directResult = directEmissions.last().second!!
        assertEquals(SpeedTestMode.DIRECT, directResult.mode)
        assertEquals(48.0, directResult.downloadMbps, 0.01)

        // Verify clean coroutine cancellation mid-flight
        val hangingRunner = object : SpeedProbeRunner {
            override suspend fun measurePing204(mode: SpeedTestMode): Double {
                delay(10_000L)
                return 15.0
            }

            override suspend fun measureDownloadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ) = 0L to 1L

            override suspend fun measureUploadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ) = 0L to 1L
        }

        var didCancel = false
        val job = launch {
            try {
                SpeedTestEngine.runDualModeSpeedTest(
                    mode = SpeedTestMode.DIRECT,
                    connectionState = ConnectionState.DISCONNECTED,
                    activeNode = null,
                    runner = hangingRunner
                ).toList()
            } catch (_: CancellationException) {
                didCancel = true
            }
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(didCancel)
    }

    @Test
    fun xrayConfigCompilerCompilesBlockDirectProxyOutInStrictPriorityOrder() {
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
        assertTrue(outboundTags.contains("direct"))
        assertTrue(outboundTags.contains("block"))

        val compiledRules = compiled.getJSONObject("routing").getJSONArray("rules")
        val ruleList = (0 until compiledRules.length()).map { compiledRules.getJSONObject(it) }

        // Last rule is catch-all to proxy-out
        assertEquals("proxy-out", ruleList.last().getString("outboundTag"))

        // Verify BLOCK rules all appear strictly before DIRECT/PROXY rules
        val lastBlockIdx = ruleList.indexOfLast { it.getString("outboundTag") == "block" }
        val firstDirectIdx = ruleList.indexOfFirst { it.getString("outboundTag") == "direct" }
        val firstCustomProxyIdx = ruleList.dropLast(1).indexOfFirst { it.getString("outboundTag") == "proxy-out" }

        assertTrue(lastBlockIdx >= 0)
        assertTrue(firstDirectIdx > lastBlockIdx)
        assertTrue(firstCustomProxyIdx > lastBlockIdx)

        // Verify geoip:* and geosite:* formatting and outboundTag mappings
        val adsRule = ruleList.first {
            it.optJSONArray("domain")?.optString(0) == "geosite:category-ads-all"
        }
        assertEquals("block", adsRule.getString("outboundTag"))

        val usProxyRule = ruleList.first {
            it.optJSONArray("ip")?.optString(0) == "geoip:us"
        }
        assertEquals("proxy-out", usProxyRule.getString("outboundTag"))

        val domesticDirectRule = ruleList.first {
            it.optJSONArray("ip")?.optString(0) == "geoip:ir"
        }
        assertEquals("direct", domesticDirectRule.getString("outboundTag"))
    }

    @Test
    fun splitTunnelEngineMatchesAdsPornSocialAndDomesticBeforeFakeDnsRewrites() {
        val splitTunnel = SplitTunnelEngine(context = null)

        // Register FakeDNS synthetic 198.18.x.x mappings
        splitTunnel.registerFakeDnsMapping("198.18.1.10", "pagead2.googlesyndication.com")
        splitTunnel.registerFakeDnsMapping("198.18.1.11", "pornhub.com")
        splitTunnel.registerFakeDnsMapping("198.18.1.12", "t.me")
        splitTunnel.registerFakeDnsMapping("198.18.1.13", "youtube.com")
        splitTunnel.registerFakeDnsMapping("198.18.1.14", "shaparak.ir")
        splitTunnel.registerFakeDnsMapping("198.18.1.15", "baidu.cn")
        splitTunnel.registerFakeDnsMapping("198.18.1.16", "gosuslugi.ru")

        val baseSettings = StabilitySettings(
            domesticBypassEnabled = true,
            domesticCountryCode = "IR",
            blockAdsEnabled = true,
            blockPornEnabled = true,
            socialPolicies = mapOf(
                SocialServiceId.TELEGRAM to RuleAction.PROXY,
                SocialServiceId.YOUTUBE to RuleAction.DIRECT,
                SocialServiceId.INSTAGRAM to RuleAction.BLOCK
            ),
            countryPolicies = listOf(
                CountryPolicyEntry("CN", RuleAction.DIRECT),
                CountryPolicyEntry("RU", RuleAction.DIRECT)
            )
        )
        val rules = GeoRulesEngine.buildManagedGeoRules(baseSettings, emptyList())

        // Ads -> BLOCK (pre-FakeDNS)
        val adsDecision = splitTunnel.evaluateDestination(null, "198.18.1.10", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.BLOCK, adsDecision.action)
        assertTrue(adsDecision.evaluatedPreFakeDns)

        // Porn -> BLOCK (pre-FakeDNS)
        val pornDecision = splitTunnel.evaluateDestination(null, "198.18.1.11", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.BLOCK, pornDecision.action)

        // Social Media -> Telegram defaults to PROXY, YouTube set to DIRECT, Instagram set to BLOCK
        val tgDecision = splitTunnel.evaluateDestination(null, "198.18.1.12", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.PROXY, tgDecision.action)

        val ytDecision = splitTunnel.evaluateDestination(null, "198.18.1.13", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.DIRECT, ytDecision.action)

        val igDecision = splitTunnel.evaluateDestination("cdninstagram.com", null, RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.BLOCK, igDecision.action)

        // Domestic Country rules (IR, CN, RU) -> DIRECT pre-FakeDNS and by GeoIP CIDR
        val irDecision = splitTunnel.evaluateDestination(null, "198.18.1.14", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.DIRECT, irDecision.action)

        val cnDecision = splitTunnel.evaluateDestination(null, "198.18.1.15", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.DIRECT, cnDecision.action)

        val ruDecision = splitTunnel.evaluateDestination(null, "198.18.1.16", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.DIRECT, ruDecision.action)

        // Raw IR GeoIP CIDR match (5.22.10.5 -> geoip:ir -> DIRECT)
        val irIpDecision = splitTunnel.evaluateDestination(null, "5.22.10.5", RoutingMode.RULE_BASED, rules)
        assertEquals(RuleAction.DIRECT, irIpDecision.action)
    }
}
