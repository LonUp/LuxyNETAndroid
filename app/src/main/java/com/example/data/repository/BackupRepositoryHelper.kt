package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.ConfigFingerprint
import com.example.core.CountryDetector
import com.example.core.LuxyBackupManager
import com.example.core.ServerCountryResolver
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SubscriptionEntity
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import com.example.data.model.ThemeCodec
import com.example.i18n.LocaleManager
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

data class BackupRestoreSummary(
    val restoredProfiles: Int,
    val skippedDuplicates: Int,
    val restoredSubscriptions: Int,
    val updatedPreferences: Int
)

/** Coordinates encrypted backup serialization with Room and app settings persistence. */
object BackupRepositoryHelper {
    suspend fun exportBackup(
        db: LuxyVpnDatabase,
        dataStore: SettingsDataStore,
        passphrase: String,
        appVersion: String
    ): Result<String> = runCatching {
        LuxyBackupManager.exportEncryptedBackup(
            passphrase = passphrase,
            profiles = db.serverProfileDao().getAll(),
            subscriptions = db.subscriptionDao().getAll(),
            settings = dataStore.settingsFlow.first(),
            appVersion = appVersion
        ).getOrThrow()
    }

    suspend fun restoreBackup(
        db: LuxyVpnDatabase,
        dataStore: SettingsDataStore,
        rawBackup: String,
        passphrase: String,
        countryDetector: CountryDetector = CountryDetector()
    ): Result<BackupRestoreSummary> = runCatching {
        val payload = LuxyBackupManager.importEncryptedBackup(rawBackup, passphrase).getOrThrow()
        var restoredProfiles = 0
        var skippedDuplicates = 0
        var restoredSubscriptions = 0
        var updatedPreferences = 0

        val resolvedActiveProfileId = db.withTransaction {
            val subscriptionDao = db.subscriptionDao()
            val profileDao = db.serverProfileDao()
            val subscriptionsById = subscriptionDao.getAll().associateByTo(LinkedHashMap()) { it.id }
            val occupiedSubscriptionIds = subscriptionsById.keys.toMutableSet()
            val subscriptionIdByNormalizedUrl = LinkedHashMap<String, String>()
            subscriptionsById.values.forEach { subscription ->
                normalizeSubscriptionUrl(subscription.url)?.let { url ->
                    subscriptionIdByNormalizedUrl.putIfAbsent(url, subscription.id)
                }
            }
            val resolvedSubscriptionIds = HashMap<String, String>()
            val restoredSubscriptionUrls = HashSet<String>()

            for (backupSubscription in payload.subscriptions) {
                val normalizedUrl = normalizeSubscriptionUrl(backupSubscription.url)
                    ?: throw IllegalArgumentException("Backup contains an invalid subscription URL")
                val existingId = subscriptionIdByNormalizedUrl[normalizedUrl]
                val resolvedId = if (existingId != null) {
                    val existing = subscriptionsById.getValue(existingId)
                    val updated = existing.copy(
                        name = backupSubscription.name,
                        isEnabledForConnection = backupSubscription.isEnabledForConnection,
                        autoUpdateHours = backupSubscription.autoUpdateHours
                    )
                    if (updated != existing) {
                        subscriptionDao.upsert(updated)
                        subscriptionsById[existingId] = updated
                    }
                    existingId
                } else {
                    val id = backupSubscription.id.takeIf { it !in occupiedSubscriptionIds }
                        ?: freshId(occupiedSubscriptionIds)
                    val inserted = SubscriptionEntity(
                        id = id,
                        name = backupSubscription.name,
                        url = backupSubscription.url,
                        autoUpdateHours = backupSubscription.autoUpdateHours,
                        createdAt = backupSubscription.createdAt,
                        profileTitle = backupSubscription.profileTitle,
                        isEnabledForConnection = backupSubscription.isEnabledForConnection
                    )
                    subscriptionDao.upsert(inserted)
                    occupiedSubscriptionIds += id
                    subscriptionsById[id] = inserted
                    subscriptionIdByNormalizedUrl[normalizedUrl] = id
                    id
                }
                if (restoredSubscriptionUrls.add(normalizedUrl)) restoredSubscriptions++
                resolvedSubscriptionIds[backupSubscription.id] = resolvedId
            }

            val currentProfiles = profileDao.getAll()
            val occupiedProfileIds = currentProfiles.mapTo(HashSet(currentProfiles.size)) { it.id }
            val existingManualByFingerprint = LinkedHashMap<String, ServerProfileEntity>()
            currentProfiles.asSequence()
                .filter { it.sourceType == SourceType.MANUAL.name || it.sourceType == SourceType.LEGACY_UNCLASSIFIED.name }
                .forEach { profile ->
                    existingManualByFingerprint.putIfAbsent(fingerprint(profile), profile)
                }
            val restoredProfileIds = HashMap<String, String>()

            for (backupProfile in payload.manualProfiles) {
                val fingerprint = fingerprint(backupProfile)
                val existing = existingManualByFingerprint[fingerprint]
                if (existing != null) {
                    val updated = existing.copy(
                        isEnabled = backupProfile.isEnabled,
                        isPinned = backupProfile.isPinned,
                        manualCountryCode = ServerCountryResolver.normalizeCode(backupProfile.manualCountryCode),
                        disabledAt = backupProfile.disabledAt
                    )
                    if (updated != existing) {
                        profileDao.upsert(updated)
                        existingManualByFingerprint[fingerprint] = updated
                        updatedPreferences++
                    }
                    restoredProfileIds[backupProfile.id] = existing.id
                    skippedDuplicates++
                    continue
                }

                val id = backupProfile.id.takeIf { it !in occupiedProfileIds } ?: freshId(occupiedProfileIds)
                val prepared = countryDetector.detectOffline(
                    backupProfile.copy(
                        id = id,
                        subscriptionId = null,
                        sourceType = backupProfile.sourceType,
                        canonicalFingerprint = fingerprint,
                        healthStatus = HealthStatus.UNTESTED.name,
                        latencyMs = null,
                        lastJitterMs = 0,
                        lastTestedAt = null,
                        lastSuccessAt = null,
                        lastAttemptAt = null,
                        consecutiveFailures = 0,
                        lastErrorCategory = com.example.data.model.ErrorCategory.NONE.name,
                        lastErrorMessage = null,
                        lastNetworkFingerprint = "",
                        cooldownUntilMs = 0L,
                        countryCode = null,
                        countryVerifiedAt = null,
                        exitIp = null
                    )
                )
                profileDao.upsert(prepared)
                occupiedProfileIds += id
                existingManualByFingerprint[fingerprint] = prepared
                restoredProfileIds[backupProfile.id] = id
                restoredProfiles++
            }

            // Subscription nodes are usually populated by a subsequent refresh; preserve each
            // node's user preferences now and apply them to matching nodes already in this DB.
            val profilesAfterManualRestore = profileDao.getAll()
            for (preference in payload.subscriptionNodePreferences) {
                val resolvedSubscriptionId = resolvedSubscriptionIds[preference.subscriptionId] ?: continue
                profilesAfterManualRestore.asSequence()
                    .filter { it.subscriptionId == resolvedSubscriptionId }
                    .filter { fingerprint(it) == preference.canonicalFingerprint }
                    .forEach { existing ->
                        val updated = existing.copy(
                            isEnabled = preference.isEnabled,
                            isPinned = preference.isPinned,
                            manualCountryCode = ServerCountryResolver.normalizeCode(preference.manualCountryCode)
                        )
                        if (updated != existing) {
                            profileDao.upsert(updated)
                            updatedPreferences++
                        }
                    }
            }

            payload.settings.activeProfileId
                ?.let(restoredProfileIds::get)
                ?.takeIf { activeId -> profilesAfterManualRestore.any { it.id == activeId } }
        }

        dataStore.updateSettings { current ->
            current.copy(
                selectedCountryCode = ServerCountryResolver.normalizeCode(payload.settings.selectedCountryCode),
                manualConfigsEnabledForConnection = payload.settings.manualConfigsEnabledForConnection,
                language = payload.settings.language.takeIf { it in LocaleManager.available } ?: "fa",
                theme = ThemeCodec.normalize(payload.settings.theme),
                activeProfileId = resolvedActiveProfileId ?: current.activeProfileId
            )
        }

        BackupRestoreSummary(
            restoredProfiles = restoredProfiles,
            skippedDuplicates = skippedDuplicates,
            restoredSubscriptions = restoredSubscriptions,
            updatedPreferences = updatedPreferences
        )
    }

    private fun fingerprint(profile: ServerProfileEntity): String =
        profile.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(profile) }

    private fun normalizeSubscriptionUrl(url: String): String? =
        url.trim().toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()

    private fun freshId(occupied: Set<String>): String {
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (id in occupied)
        return id
    }
}
