package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.core.NetworkProbeEngine
import com.example.core.ProtocolGuardrails
import com.example.core.SpeedProbeRunner
import com.example.core.SpeedTestEngine
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ColorPalette
import com.example.data.model.ConnectionState
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.SpeedTestMode
import com.example.data.model.StabilitySettings
import com.example.ui.theme.getPaletteScheme
import com.example.vpn.SplitTunnelEngine
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ComprehensiveDebugAuditTest {

    private fun buildNodeForProtocol(
        protocol: ProxyProtocol,
        isLocked: Boolean = false
    ): ServerProfileEntity = when (protocol) {
        ProxyProtocol.VLESS -> ServerProfileEntity(
            id = "audit-vless",
            name = "Audit VLESS",
            protocol = ProxyProtocol.VLESS.name,
            address = "198.51.100.10",
            port = 8443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = "WS",
            security = SecurityType.REALITY.name,
            sni = "sni-secret.example.org",
            hostHeader = "cdn-secret.example.org",
            path = "/secret-ws-path",
            publicKey = "reality-pbk-secret-value",
            shortId = "cafebabe",
            echConfigList = "ech-secret-blob-12345",
            mldsa65Verify = "mldsa-secret-sig-12345",
            isLocked = isLocked
        )
        ProxyProtocol.VMESS -> ServerProfileEntity(
            id = "audit-vmess",
            name = "Audit VMess",
            protocol = ProxyProtocol.VMESS.name,
            address = "198.51.100.11",
            port = 443,
            uuidOrPassword = "22222222-3333-4444-5555-666666666666",
            transport = "TCP",
            security = SecurityType.TLS.name,
            sni = "vmess.example.org",
            isLocked = isLocked
        )
        ProxyProtocol.TROJAN -> ServerProfileEntity(
            id = "audit-trojan",
            name = "Audit Trojan",
            protocol = ProxyProtocol.TROJAN.name,
            address = "198.51.100.12",
            port = 443,
            uuidOrPassword = "trojan-password-secret",
            transport = "TCP",
            security = SecurityType.TLS.name,
            sni = "trojan.example.org",
            isLocked = isLocked
        )
        ProxyProtocol.SHADOWSOCKS -> ServerProfileEntity(
            id = "audit-ss",
            name = "Audit SS",
            protocol = ProxyProtocol.SHADOWSOCKS.name,
            address = "198.51.100.13",
            port = 8388,
            uuidOrPassword = "ss-secret-password",
            encryption = "aes-256-gcm",
            transport = "TCP",
            security = SecurityType.NONE.name,
            isLocked = isLocked
        )
        ProxyProtocol.SOCKS -> ServerProfileEntity(
            id = "audit-socks",
            name = "Audit SOCKS",
            protocol = ProxyProtocol.SOCKS.name,
            address = "198.51.100.14",
            port = 1080,
            uuidOrPassword = "alice_user:super_secret_pass",
            transport = "TCP",
            security = SecurityType.NONE.name,
            isLocked = isLocked
        )
        ProxyProtocol.HYSTERIA2 -> ServerProfileEntity(
            id = "audit-hy2",
            name = "Audit HY2",
            protocol = ProxyProtocol.HYSTERIA2.name,
            address = "198.51.100.15",
            port = 8443,
            uuidOrPassword = "hy2-auth-secret",
            transport = "QUIC",
            security = SecurityType.TLS.name,
            sni = "hy2.example.org",
            isLocked = isLocked
        )
        ProxyProtocol.WIREGUARD -> ServerProfileEntity(
            id = "audit-wg",
            name = "Audit WG",
            protocol = ProxyProtocol.WIREGUARD.name,
            address = "198.51.100.16",
            port = 2408,
            uuidOrPassword = "wg-private-key-secret==",
            publicKey = "wg-public-key-secret==",
            shortId = "12,34,56",
            path = "172.16.0.2/32",
            transport = "WG",
            security = SecurityType.NONE.name,
            isLocked = isLocked
        )
        ProxyProtocol.MASQUE -> ServerProfileEntity(
            id = "audit-masque",
            name = "Audit MASQUE",
            protocol = ProxyProtocol.MASQUE.name,
            address = "198.51.100.17",
            port = 443,
            uuidOrPassword = "masque-priv-secret==",
            publicKey = "masque-pub-secret==",
            path = "172.16.0.9/32",
            transport = "MASQUE",
            security = SecurityType.TLS.name,
            sni = "masque.example.org",
            isLocked = isLocked
        )
        ProxyProtocol.DNSTT -> ServerProfileEntity(
            id = "audit-dnstt",
            name = "Audit DNSTT",
            protocol = ProxyProtocol.DNSTT.name,
            address = "198.51.100.18",
            port = 53,
            uuidOrPassword = "dnstt-auth-secret",
            publicKey = "dnstt-pubkey-hex",
            hostHeader = "t.dnstt.example.org",
            encryption = "udp",
            transport = "DNSTT",
            security = SecurityType.NONE.name,
            isLocked = isLocked
        )
        ProxyProtocol.MASTERDNS -> ServerProfileEntity(
            id = "audit-mdns",
            name = "Audit MasterDNS",
            protocol = ProxyProtocol.MASTERDNS.name,
            address = "198.51.100.19",
            port = 443,
            uuidOrPassword = "mdns-auth-secret",
            publicKey = "mdns-pubkey-hex",
            hostHeader = "t.mdns.example.org",
            path = "https://dns.example.org/dns-query",
            encryption = "doh",
            transport = "MASTERDNS",
            security = SecurityType.TLS.name,
            isLocked = isLocked
        )
    }

    @Test
    fun protocolMatrixCompilationHasNoDuplicateTagsSelfDialersOrInvalidDatagramFlags() {
        val relayNode = ServerProfileEntity(
            id = "relay-node",
            name = "Relay Trojan",
            protocol = ProxyProtocol.TROJAN.name,
            address = "203.0.113.99",
            port = 443,
            uuidOrPassword = "relay-secret",
            transport = "TCP",
            security = SecurityType.TLS.name,
            sni = "relay.example.org"
        )
        val rules = listOf(
            RoutingRuleEntity(
                id = "lan-172",
                name = "RFC1918 172.20",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "172.20.0.0/16",
                action = RuleAction.DIRECT.name,
                priority = 40
            ),
            RoutingRuleEntity(
                id = "ir-direct",
                name = "GeoIP IR",
                matchType = RuleMatchType.GEOIP.name,
                pattern = "ir",
                action = RuleAction.DIRECT.name,
                priority = 200
            ),
            RoutingRuleEntity(
                id = "ads-block",
                name = "Block Ads",
                matchType = RuleMatchType.GEOSITE.name,
                pattern = "category-ads-all",
                action = RuleAction.BLOCK.name,
                priority = 5
            )
        )

        for (protocol in ProxyProtocol.entries) {
            val node = buildNodeForProtocol(protocol)
            val isDatagram = ProtocolGuardrails.isDatagramProtocol(protocol)

            for (chaining in ChainingMode.entries) {
                for (routingMode in RoutingMode.entries) {
                    val settings = StabilitySettings(
                        experimentalModeEnabled = true,
                        chainingMode = chaining,
                        routingMode = routingMode,
                        tlsFragmentEnabled = true,
                        tcpFastOpen = true,
                        muxEnabled = true,
                        noiseEnabled = false,
                        finalMaskType = "header-custom",
                        finalMaskFamily = "oob"
                    )

                    val compiled = XrayConfigCompiler.compileXrayConfigObject(
                        node = node,
                        settings = settings,
                        rules = rules,
                        relayNode = relayNode
                    )

                    val outbounds = compiled.getJSONArray("outbounds")
                    val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
                    assertEquals(
                        "Duplicate outbound tags for $protocol/$chaining/$routingMode: $tags",
                        tags.size,
                        tags.toSet().size
                    )
                    assertTrue("Missing proxy-out for $protocol/$chaining", tags.contains("proxy-out"))

                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.getJSONObject(i)
                        val tag = ob.getString("tag")
                        val stream = ob.optJSONObject("streamSettings")
                        val sockopt = stream?.optJSONObject("sockopt")
                        val dialer = sockopt?.optString("dialerProxy", "") ?: ""
                        assertFalse("Self-referential dialerProxy on $tag", dialer == tag)
                        if (dialer.isNotEmpty()) {
                            assertTrue("Missing dialer target $dialer for $protocol/$chaining", tags.contains(dialer))
                        }

                        if (isDatagram && tag == "proxy-out") {
                            if (sockopt != null && sockopt.has("tcpFastOpen")) {
                                assertFalse("TFO must be disabled on datagram $protocol", sockopt.getBoolean("tcpFastOpen"))
                            }
                            assertFalse("Mux must be off on datagram $protocol", ob.has("mux"))
                        }
                    }

                    // Check BYPASS_LAN rule filtering
                    val compiledRules = compiled.getJSONObject("routing").getJSONArray("rules")
                    val ruleTags = (0 until compiledRules.length()).map {
                        val r = compiledRules.getJSONObject(it)
                        Triple(
                            r.getString("outboundTag"),
                            r.optJSONArray("ip")?.optString(0).orEmpty(),
                            r.optJSONArray("domain")?.optString(0).orEmpty()
                        )
                    }
                    if (routingMode == RoutingMode.BYPASS_LAN) {
                        assertTrue("BYPASS_LAN must retain 172.20.0.0/16 DIRECT rule", ruleTags.any { it.second == "172.20.0.0/16" && it.first == "direct" })
                        assertFalse("BYPASS_LAN must not keep public geoip:ir DIRECT rule", ruleTags.any { it.second == "geoip:ir" })
                        assertTrue("BYPASS_LAN must still enforce BLOCK rules", ruleTags.any { it.third == "geosite:category-ads-all" && it.first == "block" })
                    }
                }
            }

            // Null-node compilation with WARP_TO_PROXY must also yield valid proxy-out
            val nullNodeCompiled = XrayConfigCompiler.compileXrayConfigObject(
                node = null,
                settings = StabilitySettings(
                    experimentalModeEnabled = true,
                    chainingMode = ChainingMode.WARP_TO_PROXY
                ),
                rules = emptyList()
            )
            val nullTags = (0 until nullNodeCompiled.getJSONArray("outbounds").length()).map {
                nullNodeCompiled.getJSONArray("outbounds").getJSONObject(it).getString("tag")
            }
            assertTrue(nullTags.contains("proxy-out"))
        }

        // Verify SOCKS user:pass serialization emits users array
        val socksCompiled = XrayConfigCompiler.compileXrayConfigObject(
            node = buildNodeForProtocol(ProxyProtocol.SOCKS),
            settings = StabilitySettings(),
            rules = emptyList()
        )
        val socksOutbound = socksCompiled.getJSONArray("outbounds").getJSONObject(0)
        val socksServer = socksOutbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        val socksUsers = socksServer.getJSONArray("users").getJSONObject(0)
        assertEquals("alice_user", socksUsers.getString("user"))
        assertEquals("super_secret_pass", socksUsers.getString("pass"))
    }

    @Test
    fun geoRulesBoundaryMatchingPreventsDomainSuffixFalsePositives() {
        assertFalse(GeoRulesEngine.domainMatchesSuffix("evil-ir.com", ".ir"))
        assertFalse(GeoRulesEngine.domainMatchesSuffix("evil-ir.com", "ir"))
        assertFalse(GeoRulesEngine.domainMatchesGeosite("evil-ir.com", "geosite:ir"))
        assertTrue(GeoRulesEngine.domainMatchesGeosite("bank.shaparak.ir", "geosite:ir"))

        assertFalse(GeoRulesEngine.domainMatchesSuffix("myyoutube.com", "youtube.com"))
        assertFalse(GeoRulesEngine.domainMatchesGeosite("myyoutube.com", "geosite:youtube"))
        assertTrue(GeoRulesEngine.domainMatchesGeosite("m.youtube.com", "geosite:youtube"))

        assertTrue(GeoRulesEngine.isPrivateOrLanCidr("10.0.0.0/8"))
        assertTrue(GeoRulesEngine.isPrivateOrLanCidr("172.20.0.0/16"))
        assertTrue(GeoRulesEngine.isPrivateOrLanCidr("192.168.1.0/24"))
        assertFalse(GeoRulesEngine.isPrivateOrLanCidr("172.32.0.1/32"))
        assertFalse(GeoRulesEngine.isPrivateOrLanCidr("8.8.8.8/32"))

        val splitTunnel = SplitTunnelEngine(null)
        val settings = com.example.core.GeoRulesEngine.buildManagedGeoRules(
            StabilitySettings(domesticBypassEnabled = true, domesticCountryCode = "IR"),
            emptyList()
        )
        splitTunnel.registerFakeDnsMapping("198.18.2.5", "evil-ir.com")
        val falseIrDecision = splitTunnel.evaluateDestination(null, "198.18.2.5", RoutingMode.RULE_BASED, settings)
        assertEquals(RuleAction.PROXY, falseIrDecision.action)
    }

    @Test
    fun lockedVaultZeroLeakAcrossLogsInspectorAndSpeedTest() {
        for (protocol in listOf(
            ProxyProtocol.VLESS,
            ProxyProtocol.SOCKS,
            ProxyProtocol.WIREGUARD,
            ProxyProtocol.MASQUE,
            ProxyProtocol.DNSTT,
            ProxyProtocol.MASTERDNS
        )) {
            val locked = buildNodeForProtocol(protocol, isLocked = true)
            val compiled = XrayConfigCompiler.compileRedactedForInspector(
                node = locked,
                settings = StabilitySettings(
                    experimentalModeEnabled = true,
                    chainingMode = ChainingMode.WARP_TO_PROXY,
                    warpEndpoint = "162.159.193.99:2408",
                    warpPrivateKey = "warp-priv-key-secret",
                    warpPublicKey = "warp-pub-key-secret"
                ),
                rules = emptyList()
            )
            assertFalse("Address leaked for $protocol", compiled.contains(locked.address))
            assertFalse("Secret leaked for $protocol", compiled.contains(locked.uuidOrPassword))
            if (locked.sni.isNotBlank()) assertFalse("SNI leaked for $protocol", compiled.contains(locked.sni))
            if (locked.hostHeader.isNotBlank()) assertFalse("Host leaked for $protocol", compiled.contains(locked.hostHeader))
            if (locked.publicKey.isNotBlank()) assertFalse("Pubkey leaked for $protocol", compiled.contains(locked.publicKey))
            if (locked.shortId.isNotBlank()) assertFalse("ShortId leaked for $protocol", compiled.contains(locked.shortId))
            if (locked.echConfigList.isNotBlank()) assertFalse("ECH leaked for $protocol", compiled.contains(locked.echConfigList))
            if (locked.mldsa65Verify.isNotBlank()) assertFalse("MLDSA65 leaked for $protocol", compiled.contains(locked.mldsa65Verify))
            assertFalse("WARP endpoint leaked for $protocol", compiled.contains("162.159.193.99:2408"))
            assertFalse("WARP priv key leaked for $protocol", compiled.contains("warp-priv-key-secret"))
            assertTrue(compiled.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))

            val exitLabel = SpeedTestEngine.resolveSafeExitLabel(SpeedTestMode.TUNNELED, locked)
            assertFalse(exitLabel.contains(locked.address))
            assertFalse(exitLabel.contains(locked.port.toString()))
        }

        val vlessLocked = buildNodeForProtocol(ProxyProtocol.VLESS, isLocked = true)
        val rawLog = "Dial ${vlessLocked.address}:${vlessLocked.port} sni=${vlessLocked.sni} sid=${vlessLocked.shortId} mldsa=${vlessLocked.mldsa65Verify} pass=${vlessLocked.uuidOrPassword}"
        val cleanLog = EncryptedConfigVault.sanitizeLogText(rawLog, vlessLocked)
        assertFalse(cleanLog.contains(vlessLocked.address))
        assertFalse(cleanLog.contains(vlessLocked.sni))
        assertFalse(cleanLog.contains(vlessLocked.shortId))
        assertFalse(cleanLog.contains(vlessLocked.mldsa65Verify))
        assertFalse(cleanLog.contains(vlessLocked.uuidOrPassword))
    }

    @Test
    fun coroutineCancellationPropagatesAcrossScannerAndSpeedTest() = runTest {
        val slowRunner = object : SpeedProbeRunner {
            override suspend fun measurePing204(mode: SpeedTestMode): Double {
                delay(10_000L)
                return 25.0
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

        var speedCancelled = false
        val speedJob = launch {
            try {
                SpeedTestEngine.runDualModeSpeedTest(
                    mode = SpeedTestMode.DIRECT,
                    connectionState = ConnectionState.DISCONNECTED,
                    activeNode = null,
                    runner = slowRunner
                ).toList()
            } catch (_: CancellationException) {
                speedCancelled = true
            }
        }
        yield()
        speedJob.cancel()
        speedJob.join()
        assertTrue(speedCancelled)

        val scannerEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var scannerCancelled = false
        val scanJob = launch {
            try {
                scannerEntered.complete(Unit)
                CleanIpScanner.scanCleanIps("104.16.0.0/24", 443, "www.cloudflare.com", false) { _, _ -> }
            } catch (_: CancellationException) {
                scannerCancelled = true
            }
        }
        scannerEntered.await()
        yield()
        scanJob.cancel()
        scanJob.join()
        assertTrue(scannerCancelled)
    }

    private fun relativeLuminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    @Test
    fun allSixteenColorSchemesMeetWcagAaContrast() {
        for (palette in ColorPalette.entries) {
            for (isDark in listOf(false, true)) {
                val s = getPaletteScheme(palette, isDark)
                val pairs = listOf(
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                    "onSecondary/secondary" to (s.onSecondary to s.secondary),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                    "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                    "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                    "onError/error" to (s.onError to s.error),
                    "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                    "onBackground/background" to (s.onBackground to s.background),
                    "onSurface/surface" to (s.onSurface to s.surface),
                    "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                    "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
                    "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
                    "primary/surface" to (s.primary to s.surface),
                    "secondary/surface" to (s.secondary to s.surface),
                    "tertiary/surface" to (s.tertiary to s.surface),
                    "error/surface" to (s.error to s.surface),
                    "statusConnected/surface" to (s.statusConnected to s.surface),
                    "statusConnecting/surface" to (s.statusConnecting to s.surface),
                    "statusError/surface" to (s.statusError to s.surface),
                    "statusInfo/surface" to (s.statusInfo to s.surface)
                )
                for ((label, colors) in pairs) {
                    val ratio = contrastRatio(colors.first, colors.second)
                    assertTrue(
                        "Low contrast on ${palette.name} (dark=$isDark) $label = ${"%.2f".format(ratio)}",
                        ratio >= 4.5
                    )
                }
            }
        }
    }
}
