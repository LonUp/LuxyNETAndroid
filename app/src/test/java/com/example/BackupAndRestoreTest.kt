package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.Base64Compat
import com.example.core.ConfigFingerprint
import com.example.core.LuxyBackupManager
import com.example.core.SubscriptionNodePreference
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SubscriptionEntity
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeConfig
import com.example.data.repository.BackupRepositoryHelper
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupAndRestoreTest {
    private lateinit var context: Application
    private lateinit var dataStore: SettingsDataStore
    private val databases = mutableListOf<LuxyVpnDatabase>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        dataStore = SettingsDataStore(context)
        dataStore.updateSettings { StabilitySettings() }
    }

    @After
    fun tearDown() {
        databases.forEach(LuxyVpnDatabase::close)
        databases.clear()
    }

    @Test
    fun encryptedExportHidesSecretsAndOmitsTransientHealthFields() = runBlocking {
        val profile = realityProfile().copy(
            uuidOrPassword = "uuid-secret-4f77",
            publicKey = "reality-key-secret-9d2a",
            shortId = "short-id-secret-0b51",
            latencyMs = 321,
            lastJitterMs = 44,
            lastTestedAt = 1_700_000_000_000,
            lastNetworkFingerprint = "network-fingerprint-secret",
            cooldownUntilMs = 1_900_000_000_000,
            exitIp = "203.0.113.77"
        )
        val passwordProfile = realityProfile().copy(
            id = "password-profile",
            protocol = "TROJAN",
            security = "TLS",
            uuidOrPassword = "plaintext-password-secret-3d81",
            publicKey = "",
            shortId = ""
        )
        val subscription = SubscriptionEntity(
            id = "subscription-secret-id",
            name = "Private feed",
            url = "https://feed.example.net/path?token=subscription-url-secret-36aa"
        )
        val rawBackup = LuxyBackupManager.exportEncryptedBackup(
            passphrase = "correct horse",
            profiles = listOf(profile, passwordProfile),
            subscriptions = listOf(subscription),
            settings = StabilitySettings(),
            appVersion = "test"
        ).getOrThrow()

        listOf(
            profile.uuidOrPassword,
            passwordProfile.uuidOrPassword,
            profile.publicKey,
            profile.shortId,
            subscription.url
        ).forEach { secret -> assertFalse("plaintext secret leaked: $secret", rawBackup.contains(secret)) }

        val envelope = JSONObject(rawBackup)
        assertEquals("luxynet-backup", envelope.getString("format"))
        assertEquals(1, envelope.getInt("version"))
        assertEquals("PBKDF2WithHmacSHA256", envelope.getString("kdf"))
        assertEquals(100_000, envelope.getInt("iterations"))
        assertEquals("AES-256-GCM", envelope.getString("cipher"))

        val decrypted = decryptPayload(envelope, "correct horse")
        val profileJson = decrypted.getJSONArray("manualProfiles").getJSONObject(0)
        listOf("latencyMs", "lastJitterMs", "lastTestedAt", "lastSuccessAt", "lastAttemptAt",
            "consecutiveFailures", "lastErrorCategory", "lastErrorMessage", "lastNetworkFingerprint",
            "cooldownUntilMs", "exitIp", "countryVerifiedAt", "lastMeasurementType").forEach {
            assertFalse("transient field $it was exported", profileJson.has(it))
        }

        val importedProfiles = LuxyBackupManager.importEncryptedBackup(rawBackup, "correct horse").getOrThrow()
            .manualProfiles
        assertEquals(2, importedProfiles.size)
        val restored = importedProfiles.first()
        assertEquals(null, restored.latencyMs)
        assertEquals("", restored.lastNetworkFingerprint)
        assertEquals(0L, restored.cooldownUntilMs)
        assertEquals(null, restored.exitIp)
    }

    @Test
    fun restorePreservesManualSubscriptionAndPortablePreferences() = runBlocking {
        val sourceDb = newDatabase()
        val targetDb = newDatabase()
        val subscription = SubscriptionEntity(
            id = "source-subscription-id",
            name = "Disabled source",
            url = "https://feed.example.net/sub?key=source-secret",
            autoUpdateHours = 8,
            createdAt = 1_700_000_000_123,
            profileTitle = "Remote title",
            isEnabledForConnection = false
        )
        val manual = realityProfile().copy(
            id = "manual-profile-id",
            name = "Disabled manual",
            isEnabled = false,
            isPinned = true,
            manualCountryCode = "DE",
            disabledAt = 1_700_000_000_456,
            canonicalFingerprint = ConfigFingerprint.compute(realityProfile())
        )
        val subscribedNode = realityProfile().copy(
            id = "subscription-node-id",
            name = "Feed node",
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id,
            canonicalFingerprint = ConfigFingerprint.compute(realityProfile()),
            isEnabled = false,
            isPinned = true,
            manualCountryCode = "JP"
        )
        sourceDb.subscriptionDao().upsert(subscription)
        sourceDb.serverProfileDao().upsert(manual)
        sourceDb.serverProfileDao().upsert(subscribedNode)
        dataStore.updateSettings {
            it.copy(
                selectedCountryCode = "NL",
                manualConfigsEnabledForConnection = false,
                language = "en",
                theme = ThemeConfig(simpleMode = true, fontScale = 1.15f),
                activeProfileId = manual.id
            )
        }

        val backup = BackupRepositoryHelper.exportBackup(sourceDb, dataStore, "backup passphrase", "1.2.3")
            .getOrThrow()
        dataStore.updateSettings { StabilitySettings() }
        // A fetched subscription node may already exist while a subscription is being restored.
        targetDb.serverProfileDao().upsert(
            subscribedNode.copy(id = "target-feed-node", canonicalFingerprint = subscribedNode.canonicalFingerprint)
        )
        val summary = BackupRepositoryHelper.restoreBackup(targetDb, dataStore, backup, "backup passphrase")
            .getOrThrow()

        assertEquals(1, summary.restoredProfiles)
        assertEquals(1, summary.restoredSubscriptions)
        val restoredManual = targetDb.serverProfileDao().getAll().single { it.id == manual.id }
        assertEquals(false, restoredManual.isEnabled)
        assertEquals(true, restoredManual.isPinned)
        assertEquals("DE", restoredManual.manualCountryCode)
        assertEquals(manual.disabledAt, restoredManual.disabledAt)
        assertEquals("UNTESTED", restoredManual.healthStatus)

        val restoredSubscription = targetDb.subscriptionDao().getAll().single()
        assertEquals(subscription.id, restoredSubscription.id)
        assertEquals(false, restoredSubscription.isEnabledForConnection)
        assertEquals(8, restoredSubscription.autoUpdateHours)
        assertEquals("", restoredSubscription.pendingNodePreferences)
        val restoredNode = targetDb.serverProfileDao().getById("target-feed-node")!!
        assertEquals(false, restoredNode.isEnabled)
        assertEquals(true, restoredNode.isPinned)
        assertEquals("JP", restoredNode.manualCountryCode)

        val settings = dataStore.settingsFlow.first()
        assertEquals("NL", settings.selectedCountryCode)
        assertEquals(false, settings.manualConfigsEnabledForConnection)
        assertEquals("en", settings.language)
        assertEquals(ThemeConfig(simpleMode = true, fontScale = 1.15f), settings.theme)
        assertEquals(manual.id, settings.activeProfileId)
    }

    @Test
    fun freshRestorePreservesSubscriptionPreferencesUntilRefreshAndReexport() = runBlocking {
        val sourceDb = newDatabase()
        val targetDb = newDatabase()
        val subscription = SubscriptionEntity(
            id = "pending-preference-subscription",
            name = "Feed",
            url = "https://feed.example.net/sub?token=private-feed-token"
        )
        val baseNode = realityProfile()
        val fingerprint = ConfigFingerprint.compute(baseNode)
        val sourceNode = baseNode.copy(
            id = "pending-source-node",
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id,
            canonicalFingerprint = fingerprint,
            isEnabled = false,
            isPinned = true,
            manualCountryCode = "JP"
        )
        sourceDb.subscriptionDao().upsert(subscription)
        sourceDb.serverProfileDao().upsert(sourceNode)
        val backup = BackupRepositoryHelper.exportBackup(
            sourceDb,
            dataStore,
            "backup passphrase",
            "test"
        ).getOrThrow()

        BackupRepositoryHelper.restoreBackup(targetDb, dataStore, backup, "backup passphrase").getOrThrow()

        val expectedPreference = SubscriptionNodePreference(
            subscriptionId = subscription.id,
            canonicalFingerprint = fingerprint,
            isEnabled = false,
            isPinned = true,
            manualCountryCode = "JP"
        )
        assertTrue(targetDb.serverProfileDao().getAll().isEmpty())
        val restoredSubscription = targetDb.subscriptionDao().getAll().single()
        assertEquals(listOf(expectedPreference), LuxyBackupManager.decodePendingNodePreferences(restoredSubscription))

        val reExported = BackupRepositoryHelper.exportBackup(
            targetDb,
            dataStore,
            "backup passphrase",
            "test"
        ).getOrThrow()
        val reExportedPreferences = LuxyBackupManager
            .importEncryptedBackup(reExported, "backup passphrase")
            .getOrThrow()
            .subscriptionNodePreferences
        assertEquals(listOf(expectedPreference), reExportedPreferences)

        val repository = LuxyVpnRepository(targetDb, dataStore)
        val refreshedNode = baseNode.copy(
            id = "refreshed-node",
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id,
            canonicalFingerprint = fingerprint,
            isEnabled = true,
            isPinned = false,
            manualCountryCode = null
        )
        assertEquals(1, repository.importSubscription(restoredSubscription, listOf(refreshedNode)))

        val importedNode = targetDb.serverProfileDao().getAll().single()
        assertEquals(false, importedNode.isEnabled)
        assertEquals(true, importedNode.isPinned)
        assertEquals("JP", importedNode.manualCountryCode)
        assertEquals("", targetDb.subscriptionDao().getAll().single().pendingNodePreferences)
    }

    @Test
    fun backupUsesCurrentSubscriptionProfileValuesOverStalePendingPreferences() {
        val subscription = SubscriptionEntity(
            id = "override-subscription",
            name = "Feed",
            url = "https://feed.example.net/sub",
            pendingNodePreferences = LuxyBackupManager.encodePendingNodePreferences(
                listOf(
                    SubscriptionNodePreference(
                        subscriptionId = "override-subscription",
                        canonicalFingerprint = ConfigFingerprint.compute(realityProfile()),
                        isEnabled = false,
                        isPinned = false,
                        manualCountryCode = "JP"
                    )
                )
            )
        )
        val currentProfile = realityProfile().copy(
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id,
            canonicalFingerprint = ConfigFingerprint.compute(realityProfile()),
            isEnabled = true,
            isPinned = true,
            manualCountryCode = "DE"
        )

        val backup = LuxyBackupManager.exportEncryptedBackup(
            passphrase = "backup passphrase",
            profiles = listOf(currentProfile),
            subscriptions = listOf(subscription),
            settings = StabilitySettings(),
            appVersion = "test"
        ).getOrThrow()
        val preference = LuxyBackupManager.importEncryptedBackup(backup, "backup passphrase")
            .getOrThrow()
            .subscriptionNodePreferences
            .single()

        assertEquals(true, preference.isEnabled)
        assertEquals(true, preference.isPinned)
        assertEquals("DE", preference.manualCountryCode)
    }

    @Test
    fun wrongPassphraseCiphertextAndPayloadTamperingFailWithoutDatabaseChanges() = runBlocking {
        val sourceDb = newDatabase()
        val targetDb = newDatabase()
        val profile = realityProfile().copy(id = "protected-profile")
        sourceDb.serverProfileDao().upsert(profile)
        sourceDb.subscriptionDao().upsert(
            SubscriptionEntity(id = "protected-sub", name = "Feed", url = "https://example.org/sub")
        )
        val backup = BackupRepositoryHelper.exportBackup(sourceDb, dataStore, "right passphrase", "test").getOrThrow()
        targetDb.serverProfileDao().upsert(realityProfile().copy(id = "keep-me"))
        val beforeProfiles = targetDb.serverProfileDao().getAll()
        val beforeSubscriptions = targetDb.subscriptionDao().getAll()

        assertTrue(BackupRepositoryHelper.restoreBackup(targetDb, dataStore, backup, "wrong passphrase").isFailure)
        val envelope = JSONObject(backup)
        val ciphertext = envelope.getString("ciphertext")
        val replacement = if (ciphertext.first() == 'A') 'B' else 'A'
        val tamperedCiphertext = JSONObject(backup).put("ciphertext", replacement + ciphertext.drop(1)).toString()
        assertTrue(BackupRepositoryHelper.restoreBackup(targetDb, dataStore, tamperedCiphertext, "right passphrase").isFailure)

        val plaintext = decryptPayload(envelope, "right passphrase")
        plaintext.getJSONArray("manualProfiles").getJSONObject(0).put("isPinned", true)
        val tamperedPayload = encryptPayload(envelope, plaintext, "right passphrase")
        assertTrue(BackupRepositoryHelper.restoreBackup(targetDb, dataStore, tamperedPayload, "right passphrase").isFailure)
        assertEquals(beforeProfiles, targetDb.serverProfileDao().getAll())
        assertEquals(beforeSubscriptions, targetDb.subscriptionDao().getAll())
    }

    @Test
    fun reimportDeduplicatesProfilesAndNormalizedSubscriptionUrlsPreservingExistingIds() = runBlocking {
        val sourceDb = newDatabase()
        val targetDb = newDatabase()
        val backupProfile = realityProfile().copy(
            id = "backup-profile-id",
            isEnabled = false,
            isPinned = true,
            manualCountryCode = "FR"
        )
        val backupSubscription = SubscriptionEntity(
            id = "backup-subscription-id",
            name = "Backup name",
            url = "https://FEED.example.org:443/config?token=abc",
            autoUpdateHours = 4,
            isEnabledForConnection = false
        )
        sourceDb.serverProfileDao().upsert(backupProfile)
        sourceDb.subscriptionDao().upsert(backupSubscription)
        val backup = BackupRepositoryHelper.exportBackup(sourceDb, dataStore, "stable passphrase", "test").getOrThrow()

        val existingProfile = backupProfile.copy(id = "existing-profile-id", isEnabled = true, isPinned = false)
        val existingSubscription = SubscriptionEntity(
            id = "existing-subscription-id",
            name = "Old name",
            url = "https://feed.example.org/config?token=abc",
            isEnabledForConnection = true
        )
        targetDb.serverProfileDao().upsert(existingProfile)
        targetDb.subscriptionDao().upsert(existingSubscription)

        val first = BackupRepositoryHelper.restoreBackup(targetDb, dataStore, backup, "stable passphrase").getOrThrow()
        val second = BackupRepositoryHelper.restoreBackup(targetDb, dataStore, backup, "stable passphrase").getOrThrow()
        assertEquals(0, first.restoredProfiles)
        assertEquals(1, first.skippedDuplicates)
        assertEquals(1, first.restoredSubscriptions)
        assertEquals(1, targetDb.serverProfileDao().getAll().count { it.canonicalFingerprint == existingProfile.canonicalFingerprint })
        assertEquals("existing-profile-id", targetDb.serverProfileDao().getAll().single().id)
        assertEquals("existing-subscription-id", targetDb.subscriptionDao().getAll().single().id)
        assertEquals("Backup name", targetDb.subscriptionDao().getAll().single().name)
        assertEquals(false, targetDb.subscriptionDao().getAll().single().isEnabledForConnection)
        assertEquals(false, targetDb.serverProfileDao().getById("existing-profile-id")!!.isEnabled)
        assertEquals(true, targetDb.serverProfileDao().getById("existing-profile-id")!!.isPinned)
        assertEquals("FR", targetDb.serverProfileDao().getById("existing-profile-id")!!.manualCountryCode)
        assertEquals(1, second.skippedDuplicates)
        assertEquals(1, targetDb.serverProfileDao().getAll().size)
        assertEquals(1, targetDb.subscriptionDao().getAll().size)
    }

    @Test
    fun importRejectsBackupTextAboveTheSharedSizeLimit() {
        val oversized = "x".repeat(com.example.core.ImportInput.MAX_BYTES + 1)

        val result = LuxyBackupManager.importEncryptedBackup(oversized, "valid passphrase")

        assertEquals(com.example.core.ImportInput.TOO_LARGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun exportRejectsBackupsThatWouldExceedTheSharedSizeLimit() {
        val oversizedProfile = realityProfile().copy(
            name = "x".repeat(com.example.core.ImportInput.MAX_BYTES * 3 / 4)
        )

        val result = LuxyBackupManager.exportEncryptedBackup(
            passphrase = "valid passphrase",
            profiles = listOf(oversizedProfile),
            subscriptions = emptyList(),
            settings = StabilitySettings(),
            appVersion = "test"
        )

        assertEquals(com.example.core.ImportInput.TOO_LARGE, result.exceptionOrNull()?.message)
    }

    private fun newDatabase(): LuxyVpnDatabase = Room.inMemoryDatabaseBuilder(
        context,
        LuxyVpnDatabase::class.java
    ).build().also(databases::add)

    private fun realityProfile(): ServerProfileEntity = ServerProfileEntity(
        id = "profile-default",
        name = "Germany node",
        protocol = "VLESS",
        address = "vpn.example.net",
        port = 443,
        uuidOrPassword = "5d2bb0d6-fddb-4fc8-88d7-21c07d4a3b2f",
        transport = "TCP",
        security = "REALITY",
        sni = "www.example.org",
        fingerprint = "chrome",
        publicKey = "valid-reality-public-key",
        shortId = "1234abcd",
        sourceType = SourceType.MANUAL.name
    )

    private fun decryptPayload(envelope: JSONObject, passphrase: String): JSONObject {
        val salt = Base64Compat.decode(envelope.getString("salt"))
        val iv = Base64Compat.decode(envelope.getString("iv"))
        val ciphertext = Base64Compat.decode(envelope.getString("ciphertext"))
        val key = deriveKey(passphrase, salt)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD("luxynet-backup|v1|PBKDF2WithHmacSHA256|100000".toByteArray(Charsets.UTF_8))
            doFinal(ciphertext)
        }
        return JSONObject(String(plaintext, Charsets.UTF_8))
    }

    private fun encryptPayload(envelope: JSONObject, payload: JSONObject, passphrase: String): String {
        val salt = Base64Compat.decode(envelope.getString("salt"))
        val iv = Base64Compat.decode(envelope.getString("iv"))
        val key = deriveKey(passphrase, salt)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD("luxynet-backup|v1|PBKDF2WithHmacSHA256|100000".toByteArray(Charsets.UTF_8))
            doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        }
        return JSONObject(envelope.toString())
            .put("ciphertext", Base64Compat.encodeToString(ciphertext, urlSafe = true, noPadding = true))
            .toString()
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(bytes, "AES")
    }
}
