package com.example

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.EncryptedConfigVault
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
import com.example.core.VaultImportResult
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1VaultSanitizerMigrationTest {

    @Test
    fun roomMigration2To3PreservesServerProfilesAndCreatesNetworkStrategies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v2_v3.db"
        context.deleteDatabase(dbName)

        val helperV2 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS server_profiles (
                                id TEXT NOT NULL PRIMARY KEY,
                                name TEXT NOT NULL,
                                protocol TEXT NOT NULL,
                                address TEXT NOT NULL,
                                port INTEGER NOT NULL,
                                uuidOrPassword TEXT NOT NULL,
                                encryption TEXT NOT NULL,
                                transport TEXT NOT NULL,
                                security TEXT NOT NULL,
                                sni TEXT NOT NULL,
                                alpn TEXT NOT NULL,
                                fingerprint TEXT NOT NULL,
                                publicKey TEXT NOT NULL,
                                shortId TEXT NOT NULL,
                                path TEXT NOT NULL,
                                hostHeader TEXT NOT NULL,
                                serviceName TEXT NOT NULL,
                                flow TEXT NOT NULL,
                                allowInsecure INTEGER NOT NULL,
                                cipherSuites TEXT NOT NULL DEFAULT '',
                                finalMask TEXT NOT NULL DEFAULT '',
                                echConfigList TEXT NOT NULL DEFAULT '',
                                echForceQuery TEXT NOT NULL DEFAULT 'none',
                                mldsa65Verify TEXT NOT NULL DEFAULT '',
                                subscriptionId TEXT,
                                groupName TEXT NOT NULL,
                                latencyMs INTEGER,
                                lastJitterMs INTEGER NOT NULL,
                                lastTestedAt INTEGER,
                                isPinned INTEGER NOT NULL,
                                rawUri TEXT NOT NULL,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS subscriptions (
                                id TEXT NOT NULL PRIMARY KEY,
                                name TEXT NOT NULL,
                                url TEXT NOT NULL,
                                autoUpdateHours INTEGER NOT NULL,
                                lastUpdatedAt INTEGER,
                                nodeCount INTEGER NOT NULL,
                                lastStatus TEXT NOT NULL,
                                lastError TEXT,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS routing_rules (
                                id TEXT NOT NULL PRIMARY KEY,
                                name TEXT NOT NULL,
                                matchType TEXT NOT NULL,
                                pattern TEXT NOT NULL,
                                action TEXT NOT NULL,
                                isEnabled INTEGER NOT NULL,
                                priority INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS stability_events (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                level TEXT NOT NULL,
                                category TEXT NOT NULL,
                                message TEXT NOT NULL,
                                latencyMs INTEGER
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val dbV2 = helperV2.writableDatabase
        val values = ContentValues().apply {
            put("id", "node-v2-1")
            put("name", "Legacy v2 Node")
            put("protocol", "VLESS")
            put("address", "203.0.113.10")
            put("port", 443)
            put("uuidOrPassword", "11111111-2222-3333-4444-555555555555")
            put("encryption", "none")
            put("transport", "TCP")
            put("security", "REALITY")
            put("sni", "cdn.example.com")
            put("alpn", "")
            put("fingerprint", "chrome")
            put("publicKey", "pbk-123")
            put("shortId", "01")
            put("path", "")
            put("hostHeader", "")
            put("serviceName", "")
            put("flow", "xtls-rprx-vision")
            put("allowInsecure", 0)
            put("groupName", "Local")
            put("lastJitterMs", 0)
            put("isPinned", 1)
            put("rawUri", "")
            put("createdAt", 1000L)
        }
        dbV2.insert("server_profiles", SQLiteDatabase.CONFLICT_REPLACE, values)
        dbV2.close()
        helperV2.close()

        // Open via Room with MIGRATION_2_3 to exercise Room's full onValidateSchema check and DAOs
        val migratedRoomDb = androidx.room.Room.databaseBuilder(
            context,
            LuxyVpnDatabase::class.java,
            dbName
        )
            .addMigrations(LuxyVpnDatabase.MIGRATION_1_2, LuxyVpnDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        kotlinx.coroutines.runBlocking {
            val profiles = migratedRoomDb.serverProfileDao().getAllOnce()
            assertEquals(1, profiles.size)
            val migrated = profiles.first()
            assertEquals("Legacy v2 Node", migrated.name)
            assertFalse(migrated.isLocked)
            assertNull(migrated.vaultExpiresAtMs)
            assertEquals("", migrated.vaultKeyScheme)

            migratedRoomDb.networkStrategyDao().upsert(
                com.example.data.local.NetworkStrategyEntity(
                    networkFingerprint = "wifi-hash-abc",
                    transportType = "Wi-Fi",
                    preferredEscalationLevel = 2,
                    preferredPreset = "STRICT_DPI_BYPASS",
                    lastCleanEndpoint = "104.16.10.10",
                    lastSuccessAt = 9999L,
                    successCount = 4,
                    stallCount = 1
                )
            )
            val loadedStrategy = migratedRoomDb.networkStrategyDao().getByFingerprint("wifi-hash-abc")
            assertNotNull(loadedStrategy)
            assertEquals(2, loadedStrategy!!.preferredEscalationLevel)
            assertEquals("STRICT_DPI_BYPASS", loadedStrategy.preferredPreset)
            assertEquals(4, loadedStrategy.successCount)
        }

        migratedRoomDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun rawXrayJsonSanitizerStripsUnsafeDirectivesAndExtractsNodes() {
        val maliciousJson = """
            {
              "remarks": "Hardened-Xray-Node",
              "log": {
                "loglevel": "warning",
                "access": "/var/log/xray/access.log",
                "error": "/tmp/xray-err.log"
              },
              "api": { "tag": "api", "services": ["HandlerService"] },
              "stats": {},
              "inbounds": [
                { "tag": "socks-in", "listen": "0.0.0.0", "port": 10808, "protocol": "socks" }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "exec": "/bin/sh -c whoami",
                  "settings": {
                    "vnext": [
                      {
                        "address": "198.51.100.42",
                        "port": 8443,
                        "users": [
                          { "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "encryption": "none", "flow": "xtls-rprx-vision" }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "xhttp",
                    "security": "reality",
                    "tlsSettings": {
                      "certificateFile": "/etc/ssl/private.crt",
                      "keyFile": "/etc/ssl/private.key"
                    },
                    "realitySettings": {
                      "serverName": "dl.example.org",
                      "fingerprint": "randomized",
                      "publicKey": "pub_reality_xyz",
                      "shortId": "abcd",
                      "mldsa65Verify": "pq_verify_123"
                    },
                    "xhttpSettings": {
                      "path": "/api/v2",
                      "host": "cdn.example.org"
                    },
                    "finalmask": {
                      "tcp": [{ "type": "header-custom" }]
                    }
                  }
                },
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
        """.trimIndent()

        val result = RawXrayJsonSanitizer.sanitize(maliciousJson, groupName = "JsonSub")
        val sanitizedRoot = JSONObject(result.sanitizedJson)

        assertFalse(sanitizedRoot.has("api"))
        assertFalse(sanitizedRoot.has("stats"))
        assertFalse(sanitizedRoot.getJSONObject("log").has("access"))
        assertFalse(sanitizedRoot.getJSONObject("log").has("error"))
        assertEquals(
            "127.0.0.1",
            sanitizedRoot.getJSONArray("inbounds").getJSONObject(0).getString("listen")
        )
        val proxyOut = sanitizedRoot.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(proxyOut.has("exec"))
        val tlsObj = proxyOut.getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertFalse(tlsObj.has("certificateFile"))
        assertFalse(tlsObj.has("keyFile"))

        assertTrue(result.strippedDirectives.any { it.contains("exec") })
        assertTrue(result.strippedDirectives.any { it.contains("certificateFile") })
        assertTrue(result.strippedDirectives.any { it.contains("listen=0.0.0.0->127.0.0.1") })

        assertEquals(1, result.extractedNodes.size)
        val node = result.extractedNodes.first()
        assertEquals("Hardened-Xray-Node", node.name)
        assertEquals("198.51.100.42", node.address)
        assertEquals(8443, node.port)
        assertEquals(TransportType.XHTTP.name, node.transport)
        assertEquals(SecurityType.REALITY.name, node.security)
        assertEquals("header-custom", node.finalMask)
        assertEquals("pq_verify_123", node.mldsa65Verify)
    }

    @Test
    fun parseBatchWithReportAppliesLazyTopNOptimization() {
        val bulk = buildString {
            for (i in 1..480) {
                appendLine("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@192.0.2.${i % 250}:8388#SS-$i")
            }
            for (i in 1..40) {
                appendLine("vless://11111111-2222-3333-4444-555555555555@198.51.100.$i:443?security=reality&type=xhttp&sni=cdn.example.com&pbk=abc&sid=01#Reality-$i")
            }
        }
        val report = ProxyUriParser.parseBatchWithReport(bulk, groupName = "MegaSub", topN = 50)
        assertEquals(520, report.totalParsed)
        assertEquals(50, report.nodes.size)
        assertEquals(470, report.trimmedCount)
        assertEquals(40, report.nodes.count { it.protocol == ProxyProtocol.VLESS.name })
    }

    @Test
    fun encryptedVaultRoundTripsPassphraseAndX25519AndEnforcesExpiryAndRedaction() {
        val secretNode = ServerProfileEntity(
            name = "Vault-Confidential",
            protocol = ProxyProtocol.VLESS.name,
            address = "198.51.100.77",
            port = 2096,
            uuidOrPassword = "secret-uuid-9999-8888",
            transport = TransportType.XHTTP.name,
            security = SecurityType.REALITY.name,
            sni = "hidden-sni.example.net",
            hostHeader = "hidden-host.example.net",
            publicKey = "hidden_reality_pbk",
            shortId = "deadbeef",
            mldsa65Verify = "hidden_pq_verify"
        )

        // 1. Passphrase AES-256-GCM round-trip + wrong key rejection
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        val passEnvelope = EncryptedConfigVault.exportWithPassphrase(
            nodes = listOf(secretNode),
            passphrase = "correct-horse-battery-staple",
            expiresAtMs = futureExpiry
        )
        assertTrue(EncryptedConfigVault.isVaultPayload(passEnvelope))

        val wrongPass = EncryptedConfigVault.importVault(passEnvelope, passphrase = "wrong-password")
        assertTrue(wrongPass is VaultImportResult.Error)

        val unlockedPass = EncryptedConfigVault.importVault(passEnvelope, passphrase = "correct-horse-battery-staple")
        assertTrue(unlockedPass is VaultImportResult.Success)
        val lockedNode = (unlockedPass as VaultImportResult.Success).nodes.first()
        assertTrue(lockedNode.isLocked)
        assertEquals("", lockedNode.rawUri)
        assertEquals(EncryptedConfigVault.SCHEME_PBKDF2, lockedNode.vaultKeyScheme)
        assertEquals(futureExpiry, lockedNode.vaultExpiresAtMs)
        assertEquals("198.51.100.77", lockedNode.address)

        // 2. Expiry rejection
        val expiredResult = EncryptedConfigVault.importVault(
            rawPayload = passEnvelope,
            passphrase = "correct-horse-battery-staple",
            nowMs = futureExpiry + 1000L
        )
        assertTrue(expiredResult is VaultImportResult.Expired)
        assertTrue(EncryptedConfigVault.isVaultExpired(lockedNode, nowMs = futureExpiry + 1000L))

        // 3. X25519 sealed envelope round-trip + wrong key rejection
        val recipientPair = EncryptedConfigVault.generateX25519KeyPair()
        val otherPair = EncryptedConfigVault.generateX25519KeyPair()
        val x25519Envelope = EncryptedConfigVault.exportWithX25519(
            nodes = listOf(secretNode),
            recipientPublicKeyBase64 = recipientPair.publicKeyBase64,
            asUriEnvelope = false
        )
        val wrongKeyRes = EncryptedConfigVault.importVault(
            rawPayload = x25519Envelope,
            recipientPrivateKeyBase64 = otherPair.privateKeyBase64
        )
        assertTrue(wrongKeyRes is VaultImportResult.Error)

        val okX25519Res = EncryptedConfigVault.importVault(
            rawPayload = x25519Envelope,
            recipientPrivateKeyBase64 = recipientPair.privateKeyBase64
        )
        assertTrue(okX25519Res is VaultImportResult.Success)
        assertEquals(EncryptedConfigVault.SCHEME_X25519, (okX25519Res as VaultImportResult.Success).nodes.first().vaultKeyScheme)

        // 4. Tampering with expiresAtMs inside AAD fails AEAD verification
        val tamperedJson = JSONObject(x25519Envelope).put("expiresAtMs", System.currentTimeMillis() + 99_999_999L).toString()
        val tamperedRes = EncryptedConfigVault.importVault(
            rawPayload = tamperedJson,
            recipientPrivateKeyBase64 = recipientPair.privateKeyBase64
        )
        assertTrue(tamperedRes is VaultImportResult.Error)

        // 5. Redaction verification across UI label, logs, and Xray JSON inspector
        val uiLabel = EncryptedConfigVault.redactEndpointLabel(lockedNode)
        assertFalse(uiLabel.contains("198.51.100.77"))
        assertFalse(uiLabel.contains("2096"))
        assertTrue(uiLabel.contains("Locked Vault Endpoint"))

        val redactedLog = EncryptedConfigVault.sanitizeLogText(
            "Handshake failed connecting to 198.51.100.77 with sni hidden-sni.example.net",
            lockedNode
        )
        assertFalse(redactedLog.contains("198.51.100.77"))
        assertFalse(redactedLog.contains("hidden-sni.example.net"))

        val inspectorJson = XrayConfigCompiler.compileRedactedForInspector(
            node = lockedNode,
            settings = StabilitySettings(),
            rules = emptyList()
        )
        assertFalse(inspectorJson.contains("198.51.100.77"))
        assertFalse(inspectorJson.contains("2096"))
        assertFalse(inspectorJson.contains("secret-uuid-9999-8888"))
        assertFalse(inspectorJson.contains("hidden-sni.example.net"))
        assertFalse(inspectorJson.contains("hidden_reality_pbk"))
        assertFalse(inspectorJson.contains("hidden_pq_verify"))
        assertTrue(inspectorJson.contains(EncryptedConfigVault.REDACTED_VALUE))
        assertTrue(inspectorJson.contains("LOCKED_NO_REVEAL"))
    }
}
