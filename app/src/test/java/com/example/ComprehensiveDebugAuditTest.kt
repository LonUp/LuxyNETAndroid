package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ConnectionState
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.data.import.LinkImporter
import com.example.data.model.ThemePreset
import com.example.ui.theme.schemeForPalette
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
        else -> ServerProfileEntity(
            id = "audit-${protocol.name.lowercase()}",
            name = "Audit ${protocol.label}",
            protocol = protocol.name,
            address = "198.51.100.99",
            port = 443,
            uuidOrPassword = "uncompiled-secret-${protocol.name.lowercase()}",
            transport = TransportType.TCP.name,
            security = SecurityType.TLS.name,
            sni = "uncompiled.example.org",
            isLocked = isLocked
        )
    }

    @Test
    fun stableProtocolsIgnoreFormerRoutingChainingAndExperimentalOptions() {
        val rules = listOf(RoutingRuleEntity(
            name = "Old bypass", matchType = "DOMAIN_SUFFIX", pattern = "example.com", action = "DIRECT"
        ))
        for (protocol in LinkImporter.compilableProtocols) {
            val node = buildNodeForProtocol(protocol).copy(
                transport = "TCP", echConfigList = "", mldsa65Verify = "",
                encryption = when (protocol) {
                    ProxyProtocol.VMESS -> "auto"
                    ProxyProtocol.SHADOWSOCKS -> "aes-256-gcm"
                    else -> "none"
                }
            )
            for (chaining in ChainingMode.entries) {
                for (routing in RoutingMode.entries) {
                    val compiled = XrayConfigCompiler.compileXrayConfigObject(
                        node, StabilitySettings(
                            experimentalModeEnabled = true, chainingMode = chaining, routingMode = routing,
                            tlsFragmentEnabled = true, noiseEnabled = true, muxEnabled = true,
                            finalMaskType = "sudoku", echEnabled = true
                        ), rules, buildNodeForProtocol(ProxyProtocol.TROJAN)
                    )
                    val outbounds = compiled.getJSONArray("outbounds")
                    assertEquals(1, outbounds.length())
                    val outbound = outbounds.getJSONObject(0)
                    assertEquals("proxy-out", outbound.getString("tag"))
                    assertFalse(outbound.has("mux"))
                    val stream = outbound.getJSONObject("streamSettings")
                    assertFalse(stream.has("sockopt"))
                    assertFalse(stream.has("finalmask"))
                    assertEquals(1, compiled.getJSONObject("routing").getJSONArray("rules").length())
                }
            }
        }
        assertTrue(runCatching {
            XrayConfigCompiler.compileXrayConfigObject(null, StabilitySettings(), emptyList())
        }.isFailure)
        val socks = XrayConfigCompiler.compileXrayConfigObject(
            buildNodeForProtocol(ProxyProtocol.SOCKS), StabilitySettings(), emptyList()
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("alice_user", socks.getString("user"))
        assertEquals("super_secret_pass", socks.getString("pass"))
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

    }

    @Test
    fun lockedVaultStoredProfilesStayRedactedAcrossLogsAndLabels() {
        for (protocol in listOf(
            ProxyProtocol.VLESS,
            ProxyProtocol.SOCKS,
            ProxyProtocol.WIREGUARD,
            ProxyProtocol.MASQUE,
            ProxyProtocol.HTTP
        )) {
            val locked = buildNodeForProtocol(protocol, isLocked = true)
            val compiled = EncryptedConfigVault.sanitizeLogText(
                "address=${locked.address} secret=${locked.uuidOrPassword} sni=${locked.sni} " +
                    "host=${locked.hostHeader} publicKey=${locked.publicKey} shortId=${locked.shortId} " +
                    "ech=${locked.echConfigList} verify=${locked.mldsa65Verify}",
                locked
            )
            assertFalse("Address leaked for $protocol", compiled.contains(locked.address))
            assertFalse("Secret leaked for $protocol", compiled.contains(locked.uuidOrPassword))
            if (locked.sni.isNotBlank()) assertFalse("SNI leaked for $protocol", compiled.contains(locked.sni))
            if (locked.hostHeader.isNotBlank()) assertFalse("Host leaked for $protocol", compiled.contains(locked.hostHeader))
            if (locked.publicKey.isNotBlank()) assertFalse("Pubkey leaked for $protocol", compiled.contains(locked.publicKey))
            if (locked.shortId.isNotBlank()) assertFalse("ShortId leaked for $protocol", compiled.contains(locked.shortId))
            if (locked.echConfigList.isNotBlank()) assertFalse("ECH leaked for $protocol", compiled.contains(locked.echConfigList))
            if (locked.mldsa65Verify.isNotBlank()) assertFalse("MLDSA65 leaked for $protocol", compiled.contains(locked.mldsa65Verify))
            assertTrue(compiled.contains(EncryptedConfigVault.VAULT_REDACTED_VALUE))

            val exitLabel = EncryptedConfigVault.redactEndpointLabel(locked)
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
    fun uncompiledProtocolsFailWithAClearReason() {
        val uncompiled = ProxyProtocol.entries.filterNot { LinkImporter.isCompilable(it) }
        assertTrue("expected at least one protocol to be pending a compiler", uncompiled.isNotEmpty())
        for (protocol in uncompiled) {
            val failure = runCatching {
                XrayConfigCompiler.compileXrayConfigObject(
                    node = buildNodeForProtocol(protocol),
                    settings = StabilitySettings(),
                    rules = emptyList(),
                    relayNode = null
                )
            }.exceptionOrNull()
            assertTrue("$protocol must fail loudly", failure != null)
            assertTrue(
                "message for $protocol should name the protocol: ${failure?.message}",
                failure?.message?.contains("not supported") == true
            )
        }
    }

    @Test
    fun allTwentyPresetSchemesMeetWcagAaContrast() {
        for (preset in ThemePreset.entries) {
            for (isDark in listOf(false, true)) {
                val s = schemeForPalette(if (isDark) preset.dark else preset.light, isDark)
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
                        "Low contrast on ${preset.name} (dark=$isDark) $label = ${"%.2f".format(ratio)}",
                        ratio >= 4.5
                    )
                }
            }
        }
    }
}
