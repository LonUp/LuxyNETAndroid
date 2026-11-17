package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.GeoRulesEngine
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
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
import com.example.data.model.StabilitySettings
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
        assertEquals(1, report.nodes.size)
        assertEquals(ProxyProtocol.VLESS.name, report.nodes[0].protocol)
        assertEquals(SecurityType.REALITY.name, report.nodes[0].security)

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
    fun storedManagedRulesRemainMigrationDataNotExecutableConfiguration() {
        val settings = StabilitySettings(
            domesticBypassEnabled = true, domesticCountryCode = "IR", blockAdsEnabled = true,
            socialPolicies = mapOf(SocialServiceId.YOUTUBE to RuleAction.BLOCK)
        )
        val rules = GeoRulesEngine.buildManagedGeoRules(settings, emptyList())
        assertTrue(rules.isNotEmpty())
        val compiled = XrayConfigCompiler.compileXrayConfigObject(sampleVlessNode(), settings, rules)
        val baseline = XrayConfigCompiler.compileXrayConfigObject(sampleVlessNode(), StabilitySettings(), emptyList())
        assertEquals(baseline.toString(), compiled.toString())
    }

}
