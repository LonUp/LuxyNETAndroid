package com.example

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.Base64Compat
import com.example.core.ConfigFingerprint
import com.example.data.import.ProfileImporter
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MigrationAndDeduplicationTest {
    private lateinit var context: Application
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository
    private lateinit var importer: ProfileImporter
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java).build()
        repository = LuxyVpnRepository(database, SettingsDataStore(context))
        runBlocking { repository.updateSettings { StabilitySettings() } }
        importer = ProfileImporter(repository)
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun migration5To6PreservesAllDataClassifiesOwnershipSafelyAndBackfillsFingerprints() = runBlocking {
        val dbFile = File(context.cacheDir, "test_v5_migration_${System.nanoTime()}.db")
        dbFile.delete()

        // Create an authentic v5 SQLite database directly.
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `subscriptions` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL,
                                `autoUpdateHours` INTEGER NOT NULL, `lastUpdatedAt` INTEGER,
                                `nodeCount` INTEGER NOT NULL, `lastStatus` TEXT NOT NULL,
                                `lastError` TEXT, `createdAt` INTEGER NOT NULL,
                                `uploadBytes` INTEGER NOT NULL, `downloadBytes` INTEGER NOT NULL,
                                `totalBytes` INTEGER NOT NULL, `expireAtEpochSec` INTEGER NOT NULL,
                                `profileTitle` TEXT, PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `server_profiles` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL,
                                `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `uuidOrPassword` TEXT NOT NULL,
                                `encryption` TEXT NOT NULL, `transport` TEXT NOT NULL, `security` TEXT NOT NULL,
                                `sni` TEXT NOT NULL, `alpn` TEXT NOT NULL, `fingerprint` TEXT NOT NULL,
                                `publicKey` TEXT NOT NULL, `shortId` TEXT NOT NULL, `path` TEXT NOT NULL,
                                `hostHeader` TEXT NOT NULL, `serviceName` TEXT NOT NULL, `flow` TEXT NOT NULL,
                                `allowInsecure` INTEGER NOT NULL, `cipherSuites` TEXT NOT NULL, `finalMask` TEXT NOT NULL,
                                `echConfigList` TEXT NOT NULL, `echForceQuery` TEXT NOT NULL, `mldsa65Verify` TEXT NOT NULL,
                                `subscriptionId` TEXT, `groupName` TEXT NOT NULL, `latencyMs` INTEGER,
                                `lastJitterMs` INTEGER NOT NULL, `lastTestedAt` INTEGER, `isPinned` INTEGER NOT NULL,
                                `rawUri` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL,
                                `vaultExpiresAtMs` INTEGER, `vaultKeyScheme` TEXT NOT NULL,
                                `featureOverrides` TEXT NOT NULL, PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `routing_rules` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `matchType` TEXT NOT NULL,
                                `pattern` TEXT NOT NULL, `action` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL,
                                `priority` INTEGER NOT NULL, `managedKey` TEXT, PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `stability_events` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL,
                                `level` TEXT NOT NULL, `category` TEXT NOT NULL, `message` TEXT NOT NULL,
                                `latencyMs` INTEGER
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `network_strategies` (
                                `networkFingerprint` TEXT NOT NULL, `transportType` TEXT NOT NULL,
                                `preferredEscalationLevel` INTEGER NOT NULL, `preferredPreset` TEXT NOT NULL,
                                `lastCleanEndpoint` TEXT NOT NULL, `lastSuccessAt` INTEGER NOT NULL,
                                `successCount` INTEGER NOT NULL, `stallCount` INTEGER NOT NULL,
                                PRIMARY KEY(`networkFingerprint`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routing_rules_managedKey` ON `routing_rules` (`managedKey`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stability_events_timestamp` ON `stability_events` (`timestamp`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val rawDb = helper.writableDatabase
        rawDb.insert("subscriptions", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
            put("id", "sub-1")
            put("name", "Premium")
            put("url", "https://example.com/sub1")
            put("autoUpdateHours", 12)
            put("nodeCount", 1)
            put("lastStatus", "Updated")
            put("createdAt", 1000L)
            put("uploadBytes", 10L)
            put("downloadBytes", 20L)
            put("totalBytes", 100L)
            put("expireAtEpochSec", 0L)
        })

        fun insertProfile(id: String, name: String, subId: String?, group: String, pinned: Int = 0) {
            rawDb.insert("server_profiles", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put("id", id)
                put("name", name)
                put("protocol", "VLESS")
                put("address", "$id.example.com")
                put("port", 443)
                put("uuidOrPassword", "11111111-2222-3333-4444-555555555555")
                put("encryption", "none")
                put("transport", "TCP")
                put("security", "TLS")
                put("sni", "$id.example.com")
                put("alpn", "")
                put("fingerprint", "chrome")
                put("publicKey", "")
                put("shortId", "")
                put("path", "")
                put("hostHeader", "")
                put("serviceName", "")
                put("flow", "")
                put("allowInsecure", 0)
                put("cipherSuites", "")
                put("finalMask", "")
                put("echConfigList", "")
                put("echForceQuery", "none")
                put("mldsa65Verify", "")
                if (subId != null) put("subscriptionId", subId) else putNull("subscriptionId")
                put("groupName", group)
                put("latencyMs", 55)
                put("lastJitterMs", 0)
                put("isPinned", pinned)
                put("rawUri", "")
                put("createdAt", 1000L)
                put("isLocked", 0)
                put("vaultKeyScheme", "")
                put("featureOverrides", "")
            })
        }

        insertProfile("p-sub", "🇩🇪 Sub Node", "sub-1", "Imported", pinned = 1)
        insertProfile("p-manual-local", "🇺🇸 Manual Local", null, "Local")
        insertProfile("p-manual-imp", "Manual Imported", null, "Imported")
        insertProfile("p-orphan-sub", "Orphaned Sub Node", "deleted-sub-99", "Imported")
        insertProfile("p-legacy-group", "Legacy Custom Group", null, "OldCustomGroup")
        helper.close()

        val migratedDb = Room.databaseBuilder(context, LuxyVpnDatabase::class.java, dbFile.absolutePath)
            .addMigrations(LuxyVpnDatabase.MIGRATION_5_6, LuxyVpnDatabase.MIGRATION_6_7, LuxyVpnDatabase.MIGRATION_7_8)
            .build()
        try {
            val migratedRepo = LuxyVpnRepository(migratedDb, SettingsDataStore(context))
            migratedRepo.ensureMigrated()

            val subs = migratedRepo.getAllSubscriptions()
            assertEquals(1, subs.size)
            assertTrue(subs.single().isEnabledForConnection)

            val all = migratedRepo.getAllProfiles().associateBy { it.id }
            assertEquals(5, all.size)

            // Existing IDs, edits, and metadata preserved
            val subNode = all.getValue("p-sub")
            assertEquals(SourceType.SUBSCRIPTION.name, subNode.sourceType)
            assertEquals("sub-1", subNode.subscriptionId)
            assertTrue(subNode.isPinned)
            assertEquals(55, subNode.latencyMs)
            assertEquals("DE", subNode.countryCode)
            assertEquals(CountryConfidence.ESTIMATED.name, subNode.countryConfidence)
            assertTrue(subNode.canonicalFingerprint.isNotBlank())

            assertEquals(SourceType.MANUAL.name, all.getValue("p-manual-local").sourceType)
            assertEquals("US", all.getValue("p-manual-local").countryCode)
            assertEquals(SourceType.MANUAL.name, all.getValue("p-manual-imp").sourceType)

            // Uncertain ownership kept safely in LEGACY_UNCLASSIFIED, never attached to sub-1
            val orphan = all.getValue("p-orphan-sub")
            assertEquals(SourceType.LEGACY_UNCLASSIFIED.name, orphan.sourceType)
            assertEquals("deleted-sub-99", orphan.subscriptionId)

            val legacyGroup = all.getValue("p-legacy-group")
            assertEquals(SourceType.LEGACY_UNCLASSIFIED.name, legacyGroup.sourceType)

            // User can explicitly reclassify legacy configs to Manual
            migratedRepo.reclassifyLegacyToManual(listOf("p-orphan-sub", "p-legacy-group"))
            val afterReclassify = migratedRepo.getAllProfiles().associateBy { it.id }
            assertEquals(SourceType.MANUAL.name, afterReclassify.getValue("p-orphan-sub").sourceType)
            assertEquals(null, afterReclassify.getValue("p-orphan-sub").subscriptionId)
            assertEquals(SourceType.MANUAL.name, afterReclassify.getValue("p-legacy-group").sourceType)
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun migration6To8PreservesDataBackfillsTrashExpiryAndIndexesExpiry() = runBlocking {
        val dbFile = File(context.cacheDir, "test_v6_migration_${System.nanoTime()}.db")
        dbFile.delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `subscriptions` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL,
                                `autoUpdateHours` INTEGER NOT NULL, `lastUpdatedAt` INTEGER,
                                `nodeCount` INTEGER NOT NULL, `lastStatus` TEXT NOT NULL,
                                `lastError` TEXT, `createdAt` INTEGER NOT NULL,
                                `uploadBytes` INTEGER NOT NULL, `downloadBytes` INTEGER NOT NULL,
                                `totalBytes` INTEGER NOT NULL, `expireAtEpochSec` INTEGER NOT NULL,
                                `profileTitle` TEXT, `isEnabledForConnection` INTEGER NOT NULL DEFAULT 1,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `server_profiles` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL,
                                `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `uuidOrPassword` TEXT NOT NULL,
                                `encryption` TEXT NOT NULL, `transport` TEXT NOT NULL, `security` TEXT NOT NULL,
                                `sni` TEXT NOT NULL, `alpn` TEXT NOT NULL, `fingerprint` TEXT NOT NULL,
                                `publicKey` TEXT NOT NULL, `shortId` TEXT NOT NULL, `path` TEXT NOT NULL,
                                `hostHeader` TEXT NOT NULL, `serviceName` TEXT NOT NULL, `flow` TEXT NOT NULL,
                                `allowInsecure` INTEGER NOT NULL, `cipherSuites` TEXT NOT NULL, `finalMask` TEXT NOT NULL,
                                `echConfigList` TEXT NOT NULL, `echForceQuery` TEXT NOT NULL, `mldsa65Verify` TEXT NOT NULL,
                                `subscriptionId` TEXT, `groupName` TEXT NOT NULL, `latencyMs` INTEGER,
                                `lastJitterMs` INTEGER NOT NULL, `lastTestedAt` INTEGER, `isPinned` INTEGER NOT NULL,
                                `rawUri` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL,
                                `vaultExpiresAtMs` INTEGER, `vaultKeyScheme` TEXT NOT NULL,
                                `featureOverrides` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL DEFAULT 'MANUAL',
                                `canonicalFingerprint` TEXT NOT NULL DEFAULT '',
                                `isEnabled` INTEGER NOT NULL DEFAULT 1,
                                `healthStatus` TEXT NOT NULL DEFAULT 'UNTESTED',
                                `lastSuccessAt` INTEGER, `lastAttemptAt` INTEGER,
                                `consecutiveFailures` INTEGER NOT NULL DEFAULT 0,
                                `lastErrorCategory` TEXT NOT NULL DEFAULT 'NONE',
                                `lastErrorMessage` TEXT,
                                `lastNetworkFingerprint` TEXT NOT NULL DEFAULT '',
                                `cooldownUntilMs` INTEGER NOT NULL DEFAULT 0,
                                `countryCode` TEXT,
                                `countryConfidence` TEXT NOT NULL DEFAULT 'UNKNOWN',
                                `countrySource` TEXT NOT NULL DEFAULT 'NONE',
                                `countryVerifiedAt` INTEGER, `exitIp` TEXT, `manualCountryCode` TEXT,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `deleted_profile_backups` (
                                `id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL,
                                `label` TEXT NOT NULL, `profileId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `routing_rules` (
                                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `matchType` TEXT NOT NULL,
                                `pattern` TEXT NOT NULL, `action` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL,
                                `priority` INTEGER NOT NULL, `managedKey` TEXT, PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `stability_events` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL,
                                `level` TEXT NOT NULL, `category` TEXT NOT NULL, `message` TEXT NOT NULL,
                                `latencyMs` INTEGER
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `network_strategies` (
                                `networkFingerprint` TEXT NOT NULL, `transportType` TEXT NOT NULL,
                                `preferredEscalationLevel` INTEGER NOT NULL, `preferredPreset` TEXT NOT NULL,
                                `lastCleanEndpoint` TEXT NOT NULL, `lastSuccessAt` INTEGER NOT NULL,
                                `successCount` INTEGER NOT NULL, `stallCount` INTEGER NOT NULL,
                                PRIMARY KEY(`networkFingerprint`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_subscriptionId` ON `server_profiles` (`subscriptionId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_sourceType` ON `server_profiles` (`sourceType`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_canonicalFingerprint` ON `server_profiles` (`canonicalFingerprint`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_isEnabled` ON `server_profiles` (`isEnabled`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_deleted_profile_backups_batchId` ON `deleted_profile_backups` (`batchId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_deleted_profile_backups_deletedAt` ON `deleted_profile_backups` (`deletedAt`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routing_rules_managedKey` ON `routing_rules` (`managedKey`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stability_events_timestamp` ON `stability_events` (`timestamp`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        val deletedAt = 1_700_000_000_000L
        rawDb.insert("server_profiles", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
            put("id", "v6-node")
            put("name", "V6 Node")
            put("protocol", "VLESS")
            put("address", "v6.example.com")
            put("port", 443)
            put("uuidOrPassword", "11111111-2222-3333-4444-555555555555")
            put("encryption", "none")
            put("transport", "TCP")
            put("security", "TLS")
            put("sni", "v6.example.com")
            put("alpn", "")
            put("fingerprint", "chrome")
            put("publicKey", "")
            put("shortId", "")
            put("path", "")
            put("hostHeader", "")
            put("serviceName", "")
            put("flow", "")
            put("allowInsecure", 0)
            put("cipherSuites", "")
            put("finalMask", "")
            put("echConfigList", "")
            put("echForceQuery", "none")
            put("mldsa65Verify", "")
            put("groupName", "Imported")
            put("lastJitterMs", 0)
            put("isPinned", 1)
            put("rawUri", "")
            put("createdAt", 1000L)
            put("isLocked", 0)
            put("vaultKeyScheme", "")
            put("featureOverrides", "")
        })
        rawDb.insert("deleted_profile_backups", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
            put("id", "bak-1")
            put("batchId", "batch-1")
            put("deletedAt", deletedAt)
            put("label", "Old delete")
            put("profileId", "old-1")
            put("payloadJson", "{}")
        })
        helper.close()

        val migratedDb = Room.databaseBuilder(context, LuxyVpnDatabase::class.java, dbFile.absolutePath)
            .addMigrations(LuxyVpnDatabase.MIGRATION_6_7, LuxyVpnDatabase.MIGRATION_7_8)
            .build()
        try {
            val profile = migratedDb.serverProfileDao().getById("v6-node")
            assertNotNull(profile)
            assertTrue(profile!!.isPinned)
            assertEquals(null, profile.disabledAt)
            assertEquals("NONE", profile.lastMeasurementType)

            val backup = migratedDb.deletedProfileBackupDao().getAll().single()
            val expiryIndex = migratedDb.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_deleted_profile_backups_expiresAt'"
            )
            try {
                assertTrue(expiryIndex.moveToFirst())
            } finally {
                expiryIndex.close()
            }
            assertEquals(deletedAt + 2_592_000_000L, backup.expiresAt)
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun fingerprintDetectsGenuineDuplicatesWithoutMergingMeaningfulDifferences() {
        val base = ServerProfileEntity(
            name = "Node A",
            protocol = "VLESS",
            address = "2001:0db8:0000:0000:0000:0000:0000:0001",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = "WS",
            security = "TLS",
            sni = "CDN.Example.COM",
            alpn = "h2, http/1.1",
            path = "/ws",
            hostHeader = "cdn.example.com"
        )
        val cosmeticVariant = base.copy(
            id = "other-id",
            name = "Renamed Node B",
            address = "[2001:db8::1]",
            sni = "cdn.example.com",
            alpn = "h2,http/1.1",
            groupName = "Other"
        )
        assertEquals(ConfigFingerprint.compute(base), ConfigFingerprint.compute(cosmeticVariant))

        // Meaningful differences must NEVER collide
        val diffSni = base.copy(sni = "other.example.com")
        val diffPath = base.copy(path = "/ws2")
        val diffTransport = base.copy(transport = "GRPC")
        val diffSecurity = base.copy(security = "REALITY", publicKey = "pbk123")
        val diffUuid = base.copy(uuidOrPassword = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val diffPort = base.copy(port = 8443)
        val diffFp = base.copy(fingerprint = "firefox")
        val diffFlow = base.copy(flow = "xtls-rprx-vision")

        val baseFp = ConfigFingerprint.compute(base)
        for (variant in listOf(diffSni, diffPath, diffTransport, diffSecurity, diffUuid, diffPort, diffFp, diffFlow)) {
            assertNotEquals(baseFp, ConfigFingerprint.compute(variant))
        }
    }

    @Test
    fun repeatedManualImportsDeduplicateAndReportCountsWithoutGrowingDatabase() = runBlocking {
        val link1 = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls&sni=one.example#First"
        val link1Renamed = "vless://11111111-2222-3333-4444-555555555555@one.example:443?sni=ONE.EXAMPLE&security=tls#Renamed"
        val link2 = "trojan://secret@two.example:443?sni=two.example#Second"
        val invalid = "hy2://secret@bad.example:443#Unsupported"

        val firstSummary = importer.importText("$link1\n$link1Renamed\n$link2\n$invalid")
        assertEquals(2, firstSummary.profiles)
        assertEquals(1, firstSummary.duplicates)
        assertEquals(1, firstSummary.invalid)
        assertEquals(2, repository.getAllProfiles().size)

        // Re-importing the same links skips all duplicates and does not grow DB
        val secondSummary = importer.importText("$link1\n$link2")
        assertEquals(0, secondSummary.profiles)
        assertEquals(2, secondSummary.duplicates)
        assertEquals(2, repository.getAllProfiles().size)
    }

    @Test
    fun subscriptionRefreshPreservesSourceToggleHealthVerifiedCountryAndManualConfigs() = runBlocking {
        val manualLink = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls#ManualCopy"
        importer.importText(manualLink)
        val manualId = repository.getAllProfiles().single().id

        val subLink1 = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls#SubNode1"
        val subLink2 = "trojan://secret@two.example:443?sni=two.example#SubNode2"
        val url = server.url("/sub").toString()
        server.enqueue(MockResponse().setBody(Base64Compat.encodeToString("$subLink1\n$subLink2".toByteArray())))
        importer.importSubscriptionUrl(url, customName = "MySub")

        val sub = repository.getAllSubscriptions().single()
        repository.setSubscriptionEnabledForConnection(sub.id, false)

        val subNode1 = repository.getAllProfiles().first { it.subscriptionId == sub.id && it.address == "one.example" }
        repository.updateProfileHealth(
            subNode1.copy(
                healthStatus = HealthStatus.WORKING.name,
                lastSuccessAt = 123_456L,
                countryCode = "DE",
                countryConfidence = CountryConfidence.VERIFIED.name,
                countrySource = CountrySource.EXIT_VERIFIED.name,
                countryVerifiedAt = 123_456L,
                exitIp = "203.0.113.10",
                isEnabled = false
            )
        )

        // Refresh with subLink1 (renamed) + duplicate subLink1 + new subLink3
        val subLink1Renamed = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls#SubNode1-Updated"
        val subLink3 = "trojan://secret3@three.example:443?sni=three.example#SubNode3"
        server.enqueue(MockResponse().setBody(Base64Compat.encodeToString("$subLink1Renamed\n$subLink1\n$subLink3".toByteArray())))
        val refreshSummary = importer.refreshSubscription(sub.id)

        assertEquals(2, refreshSummary.profiles)
        assertEquals(1, refreshSummary.duplicates)

        val refreshedSub = repository.getAllSubscriptions().single()
        assertFalse(refreshedSub.isEnabledForConnection)
        assertEquals(2, refreshedSub.nodeCount)

        val all = repository.getAllProfiles()
        // Manual node untouched
        val manualAfter = all.first { it.id == manualId }
        assertEquals(SourceType.MANUAL.name, manualAfter.sourceType)
        assertEquals("ManualCopy", manualAfter.name)

        // Unchanged fingerprint in subscription kept its ID, health, verified country, and disabled state
        val subNode1After = all.first { it.id == subNode1.id }
        assertEquals("SubNode1-Updated", subNode1After.name)
        assertEquals(HealthStatus.WORKING.name, subNode1After.healthStatus)
        assertEquals("DE", subNode1After.countryCode)
        assertEquals(CountryConfidence.VERIFIED.name, subNode1After.countryConfidence)
        assertFalse(subNode1After.isEnabled)
    }

    @Test
    fun largeBatchImportOf1000ConfigsCompletesCleanlyWithoutDuplicates() = runBlocking {
        val lines = (1..1000).joinToString("\n") { i ->
            "vless://11111111-2222-3333-4444-555555555555@node$i.example:443?security=tls#Node-$i"
        }
        val summary = importer.importText(lines)
        assertEquals(1000, summary.profiles)
        assertEquals(0, summary.duplicates)
        assertEquals(1000, repository.getAllProfiles().size)
    }
}
