package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.core.ProtocolGuardrails
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
import com.example.core.SpeedProbeRunner
import com.example.core.SpeedTestEngine
import com.example.core.VaultImportResult
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LuxyVpnCoreTests {

    private fun sampleVlessNode(isLocked: Boolean = false): ServerProfileEntity = ServerProfileEntity(
        id = "node-1",
        name = "Frankfurt Edge",
        protocol = ProxyProtocol.VLESS.name,
        address = "203.0.113.42",
        port = 443,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        transport = "TCP",
        security = SecurityType.REALITY.name,
        sni = "www.cloudflare.com",
        publicKey = "test-reality-pbk",
        shortId = "abcd",
        isLocked = isLocked
    )

    @Test
    fun proxyUriParserParsesMultiProtocolBatchAndExportsRoundtrip() {
        val uuid = UUID.randomUUID().toString()
        val vless = "vless://$uuid@198.51.100.10:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=pubkey123&sid=a1b2&type=tcp#EdgeNode"
        val hy2 = "hysteria2://secretpass@198.51.100.11:8443?sni=hy2.example.com#HY2Node"
        val awg = "awg://privKeyBase64==@162.159.192.1:2408?publickey=pubKeyBase64==&jc=5&jmin=40&jmax=80#AwgWarp"

        val report = ProxyUriParser.parseBatchWithReport("$vless\n$hy2\n$awg")
        assertEquals(3, report.nodes.size)
        assertEquals(ProxyProtocol.VLESS.name, report.nodes[0].protocol)
        assertEquals(SecurityType.REALITY.name, report.nodes[0].security)
        assertEquals(ProxyProtocol.HYSTERIA2.name, report.nodes[1].protocol)
        assertEquals(ProxyProtocol.WIREGUARD.name, report.nodes[2].protocol)
        assertEquals("awg", report.nodes[2].finalMask)

        val shared = ProxyUriParser.toShareUri(report.nodes[0].copy(rawUri = ""))
        assertTrue(shared.startsWith("vless://"))
        assertTrue(shared.contains("pbk=pubkey123"))
    }

    @Test
    fun rawXrayJsonSanitizerStripsDangerousDirectivesAndNormalizesWildcardListen() {
        val rawJson = """
            {
              "log": { "access": "/tmp/access.log", "loglevel": "info" },
              "api": { "tag": "api" },
              "stats": {},
              "inbounds": [
                { "listen": "0.0.0.0", "port": 10808, "protocol": "socks", "exec": "/bin/sh" }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "trojan",
                  "certificateFile": "/etc/ssl/cert.pem",
                  "settings": { "servers": [{ "address": "198.51.100.50", "port": 443, "password": "secret" }] },
                  "streamSettings": { "network": "tcp", "security": "tls", "tlsSettings": { "serverName": "cdn.example.com" } }
                }
              ]
            }
        """.trimIndent()

        val result = RawXrayJsonSanitizer.sanitizeRawXrayJson(rawJson)
        assertEquals(1, result.extractedNodes.size)
        assertTrue(result.strippedDirectives.any { it.contains("exec") })
        assertTrue(result.strippedDirectives.any { it.contains("certificateFile") })
        assertTrue(result.strippedDirectives.any { it.contains("api") })
        assertFalse(result.sanitizedJson.contains("\"exec\""))
        assertTrue(result.sanitizedJson.contains("127.0.0.1"))
    }

    @Test
    fun encryptedVaultPbkdf2AndX25519RoundtripAndRedactionWork() {
        val node = sampleVlessNode(isLocked = false)
        val futureExpiry = System.currentTimeMillis() + 60_000L

        val pbkdf2Payload = EncryptedConfigVault.exportVaultWithPassphrase(
            nodes = listOf(node),
            passphrase = "correct-horse-battery",
            expiresAtMs = futureExpiry
        )
        assertTrue(EncryptedConfigVault.isVaultPayload(pbkdf2Payload))

        val wrongPass = EncryptedConfigVault.importVault(pbkdf2Payload, passphrase = "wrong")
        assertTrue(wrongPass is VaultImportResult.Error)

        val unlocked = EncryptedConfigVault.importVault(pbkdf2Payload, passphrase = "correct-horse-battery")
        assertTrue(unlocked is VaultImportResult.Success)
        val unlockedNode = (unlocked as VaultImportResult.Success).nodes.first()
        assertTrue(unlockedNode.isLocked)
        assertEquals("203.0.113.42", unlockedNode.address)

        val kp = EncryptedConfigVault.generateX25519KeyPair()
        val sealedPayload = EncryptedConfigVault.exportVaultWithX25519(listOf(node), kp.publicKeyBase64)
        val xUnlocked = EncryptedConfigVault.importVault(sealedPayload, recipientPrivateKeyBase64 = kp.privateKeyBase64)
        assertTrue(xUnlocked is VaultImportResult.Success)

        val redactedLog = EncryptedConfigVault.sanitizeLogText("Dialing 203.0.113.42 with www.cloudflare.com", unlockedNode)
        assertFalse(redactedLog.contains("203.0.113.42"))
        assertTrue(redactedLog.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))
    }

    @Test
    fun protocolGuardrailsAndInspectorRedactionEnforceSafety() {
        val (frag, tfo) = ProtocolGuardrails.enforceFragmentVsTfo(
            tlsFragmentEnabled = true,
            tcpFastOpen = true,
            lastChangedIsFragment = true
        )
        assertTrue(frag)
        assertFalse(tfo)

        val lockedNode = sampleVlessNode(isLocked = true)
        val compiledRedacted = XrayConfigCompiler.compileRedactedForInspector(
            node = lockedNode,
            settings = StabilitySettings(chainingMode = ChainingMode.WARP_TO_PROXY),
            rules = emptyList()
        )
        assertFalse(compiledRedacted.contains("203.0.113.42"))
        assertFalse(compiledRedacted.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(compiledRedacted.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))
    }

    @Test
    fun geoRulesEngineAndSplitTunnelEvaluatePreFakeDnsAndPriorityOrder() {
        val settings = StabilitySettings(
            domesticBypassEnabled = true,
            domesticCountryCode = "IR",
            blockAdsEnabled = true,
            blockPornEnabled = true,
            socialPolicies = mapOf(
                SocialServiceId.TELEGRAM to RuleAction.PROXY,
                SocialServiceId.YOUTUBE to RuleAction.BLOCK,
                SocialServiceId.INSTAGRAM to RuleAction.DIRECT
            ),
            countryPolicies = listOf(CountryPolicyEntry("CN", RuleAction.DIRECT))
        )

        val rules = GeoRulesEngine.buildManagedGeoRules(settings, listOf(
            RoutingRuleEntity(
                id = "custom-block-override",
                name = "Custom Block Instagram Ads",
                matchType = RuleMatchType.DOMAIN_SUFFIX.name,
                pattern = "ads.instagram.com",
                action = RuleAction.BLOCK.name,
                priority = 90
            ),
            RoutingRuleEntity(
                id = "lan-private",
                name = "Private LAN",
                matchType = RuleMatchType.GEOIP.name,
                pattern = "private",
                action = RuleAction.DIRECT.name,
                priority = 50
            )
        ))

        val splitTunnel = SplitTunnelEngine(null)
        splitTunnel.registerFakeDnsMapping("198.18.0.42", "shaparak.ir")
        splitTunnel.registerFakeDnsMapping("198.18.0.43", "doubleclick.net")
        splitTunnel.registerFakeDnsMapping("198.18.0.44", "youtube.com")

        // FakeDNS IP 198.18.0.42 resolves back to shaparak.ir -> Domestic IR DIRECT bypass
        val bankDecision = splitTunnel.evaluateDestination(
            domain = null,
            destIp = "198.18.0.42",
            routingMode = RoutingMode.RULE_BASED,
            rules = rules
        )
        assertEquals(RuleAction.DIRECT, bankDecision.action)
        assertTrue(bankDecision.evaluatedPreFakeDns)

        // Ads -> BLOCK
        val adsDecision = splitTunnel.evaluateDestination(
            domain = null,
            destIp = "198.18.0.43",
            routingMode = RoutingMode.RULE_BASED,
            rules = rules
        )
        assertEquals(RuleAction.BLOCK, adsDecision.action)

        // YouTube -> BLOCK
        val ytDecision = splitTunnel.evaluateDestination(
            domain = null,
            destIp = "198.18.0.44",
            routingMode = RoutingMode.RULE_BASED,
            rules = rules
        )
        assertEquals(RuleAction.BLOCK, ytDecision.action)

        // Compiled JSON validates blackhole outbound + geoip/geosite rules
        val compiled = XrayConfigCompiler.compileXrayConfigObject(sampleVlessNode(), settings, rules)
        val outbounds = compiled.getJSONArray("outbounds")
        assertTrue((0 until outbounds.length()).any { outbounds.getJSONObject(it).getString("protocol") == "blackhole" })
        val routing = compiled.getJSONObject("routing")
        assertTrue(routing.getBoolean("preFakeDnsEvaluation"))
    }

    @Test
    fun speedTestEngineComputesMedianJitterMbpsAndRespectsVaultLockAndDisconnectedState() = runTest {
        assertEquals(25.0, SpeedTestEngine.computeMedian(listOf(40.0, 20.0, 30.0, 10.0)), 0.01)
        assertEquals(10.0, SpeedTestEngine.computeJitter(listOf(20.0, 30.0, 20.0)), 0.01)
        assertEquals(80.0, SpeedTestEngine.computeMbps(10_000_000L, 1_000L), 0.01)

        val locked = sampleVlessNode(isLocked = true)
        val safeExit = SpeedTestEngine.resolveSafeExitLabel(SpeedTestMode.TUNNELED, locked)
        assertFalse(safeExit.contains("203.0.113.42"))
        assertTrue(safeExit.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))

        val fakeRunner = object : SpeedProbeRunner {
            private val pings = listOf(22.0, 26.0, 24.0, 28.0, 25.0, 23.0)
            private var idx = 0
            override suspend fun measurePing204(mode: SpeedTestMode): Double = pings[idx++ % pings.size]
            override suspend fun measureDownloadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ): Pair<Long, Long> {
                onSample(250L, 1_250_000L, 40.0)
                return 25_000_000L to 5_000L // 40 Mbps
            }
            override suspend fun measureUploadWindow(
                mode: SpeedTestMode,
                durationMs: Long,
                onSample: suspend (Long, Long, Double) -> Unit
            ): Pair<Long, Long> {
                onSample(250L, 625_000L, 20.0)
                return 12_500_000L to 5_000L // 20 Mbps
            }
        }

        // Direct mode succeeds even while VPN is DISCONNECTED
        val directEmissions = SpeedTestEngine.runDualModeSpeedTest(
            mode = SpeedTestMode.DIRECT,
            connectionState = ConnectionState.DISCONNECTED,
            activeNode = locked,
            runner = fakeRunner
        ).toList()
        val finalDirect = directEmissions.last().second
        assertNotNull(finalDirect)
        assertEquals(40.0, finalDirect!!.downloadMbps, 0.01)
        assertEquals(20.0, finalDirect.uploadMbps, 0.01)
        assertEquals(SpeedTestStage.COMPLETE, directEmissions.last().first.stage)

        // Tunneled mode rejects when DISCONNECTED & probe flag is false
        var threwDisconnected = false
        try {
            SpeedTestEngine.runDualModeSpeedTest(
                mode = SpeedTestMode.TUNNELED,
                connectionState = ConnectionState.DISCONNECTED,
                activeNode = locked,
                allowDisconnectedNodeProbe = false,
                runner = fakeRunner
            ).toList()
        } catch (e: IllegalStateException) {
            threwDisconnected = e.message == "VPN Disconnected"
        }
        assertTrue(threwDisconnected)

        // Cancellation propagates cleanly
        val slowRunner = object : SpeedProbeRunner {
            override suspend fun measurePing204(mode: SpeedTestMode): Double {
                kotlinx.coroutines.delay(5_000L)
                return 20.0
            }
            override suspend fun measureDownloadWindow(mode: SpeedTestMode, durationMs: Long, onSample: suspend (Long, Long, Double) -> Unit) = 0L to 1L
            override suspend fun measureUploadWindow(mode: SpeedTestMode, durationMs: Long, onSample: suspend (Long, Long, Double) -> Unit) = 0L to 1L
        }
        var cancelled = false
        val job = launch {
            try {
                SpeedTestEngine.runDualModeSpeedTest(
                    mode = SpeedTestMode.DIRECT,
                    connectionState = ConnectionState.DISCONNECTED,
                    activeNode = null,
                    runner = slowRunner
                ).toList()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertTrue(cancelled)
    }
}
