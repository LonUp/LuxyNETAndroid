package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.ConfigFingerprint
import com.example.core.CountryDetector
import com.example.core.LuxyBackupManager
import com.example.core.ServerCountryResolver
import com.example.core.SubscriptionNodePreference
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SubscriptionEntity
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import com.example.data.model.ThemeCodec
import com.example.i18n.LocaleManager
import kotlinx.coroutines.CancellationException
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
    ): Result<String> = captureCancellable {
        val settings = dataStore.settingsFlow.first()
        val (profiles, subscriptions) = db.withTransaction {
            db.serverProfileDao().getAll() to db.subscriptionDao().getAll()
        }
        LuxyBackupManager.exportEncryptedBackup(
            passphrase = passphrase,
            profiles = profiles,
            subscriptions = subscriptions,
            settings = settings,
            appVersion = appVersion
        ).getOrThrow()
    }

    suspend fun restoreBackup(
        db: LuxyVpnDatabase,
        dataStore: SettingsDataStore,
        rawBackup: String,
        passphrase: String,
        countryDetector: CountryDetector = CountryDetector()
    ): Result<BackupRestoreSummary> = captureCancellable {
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

            // Keep restored preferences for nodes not fetched yet, and consume them as soon as a
            // matching subscription node is already present.
            val profilesAfterManualRestore = profileDao.getAll()
            val incomingPreferencesBySubscription =
                LinkedHashMap<String, LinkedHashMap<String, SubscriptionNodePreference>>()
            for (preference in payload.subscriptionNodePreferences) {
                val resolvedSubscriptionId = resolvedSubscriptionIds[preference.subscriptionId] ?: continue
                incomingPreferencesBySubscription
                    .getOrPut(resolvedSubscriptionId) { LinkedHashMap() }[preference.canonicalFingerprint] =
                    preference.copy(subscriptionId = resolvedSubscriptionId)
            }

            val profilesBySubscriptionAndFingerprint = profilesAfterManualRestore
                .asSequence()
                .filter { it.subscriptionId != null }
                .groupBy { it.subscriptionId!! to fingerprint(it) }
            for (resolvedSubscriptionId in resolvedSubscriptionIds.values.toSet()) {
                val subscription = subscriptionsById[resolvedSubscriptionId] ?: continue
                val preferencesByFingerprint = LinkedHashMap<String, SubscriptionNodePreference>()
                LuxyBackupManager.decodePendingNodePreferences(subscription).forEach { preference ->
                    preferencesByFingerprint[preference.canonicalFingerprint] = preference
                }
                incomingPreferencesBySubscription[resolvedSubscriptionId]?.values?.forEach { preference ->
                    preferencesByFingerprint[preference.canonicalFingerprint] = preference
                }

                val stillPending = ArrayList<SubscriptionNodePreference>()
                preferencesByFingerprint.values.forEach { preference ->
                    val matchingProfiles = profilesBySubscriptionAndFingerprint[
                        resolvedSubscriptionId to preference.canonicalFingerprint
                    ].orEmpty()
                    if (matchingProfiles.isEmpty()) {
                        stillPending += preference
                    } else {
                        matchingProfiles.forEach { existing ->
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
                }

                val encodedPending = LuxyBackupManager.encodePendingNodePreferences(stillPending)
                if (encodedPending != subscription.pendingNodePreferences) {
                    val updated = subscription.copy(pendingNodePreferences = encodedPending)
                    subscriptionDao.upsert(updated)
                    subscriptionsById[resolvedSubscriptionId] = updated
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

    private suspend fun <T> captureCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
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
