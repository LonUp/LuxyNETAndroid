package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.ClientPolicy
import com.example.core.ConfigFingerprint
import com.example.core.CountryDetector
import com.example.core.GeoRulesEngine
import com.example.core.LuxyBackupManager
import com.example.core.ProtocolGuardrails
import com.example.core.ServerCountryResolver
import com.example.core.SubscriptionUrlPolicy
import com.example.data.local.DeletedProfileBackupEntity
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.ServerProfileCodec
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.model.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

data class ManualImportResult(
    val imported: Int,
    val duplicates: Int,
    val invalid: Int
)

class LuxyVpnRepository(
    private val db: LuxyVpnDatabase,
    private val dataStore: SettingsDataStore
) {
    private val migrationMutex = Mutex()
    @Volatile
    private var migrationCompleted = false
    val profilesFlow: Flow<List<ServerProfileEntity>> = db.serverProfileDao().observeAll()
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = db.subscriptionDao().observeAll()
    val routingRulesFlow: Flow<List<RoutingRuleEntity>> = db.routingRuleDao().observeAll()
    val stabilityEventsFlow: Flow<List<StabilityEventEntity>> = db.stabilityEventDao().observeRecent()
    val networkStrategiesFlow: Flow<List<NetworkStrategyEntity>> = db.networkStrategyDao().observeAll()
    val settingsFlow: Flow<StabilitySettings> = dataStore.settingsFlow
    val deletedBackupsFlow: Flow<List<DeletedBackupBatch>> = db.deletedProfileBackupDao().observeAll()
        .map { rows ->
            rows.groupBy { it.batchId }
                .map { (batchId, items) ->
                    val first = items.first()
                    DeletedBackupBatch(
                        batchId = batchId,
                        deletedAt = first.deletedAt,
                        label = first.label,
                        count = items.size
                    )
                }
                .sortedByDescending { it.deletedAt }
        }

    val trashEntriesFlow: Flow<List<TrashEntry>> = db.deletedProfileBackupDao().observeAll()
        .map { rows -> rows.map { it.toTrashEntry() } }

    suspend fun getSettings(): StabilitySettings = settingsFlow.first()
    suspend fun getAllProfiles(): List<ServerProfileEntity> = db.serverProfileDao().getAll()
    suspend fun getAllRoutingRules(): List<RoutingRuleEntity> = db.routingRuleDao().getAll()
    suspend fun getAllSubscriptions(): List<SubscriptionEntity> = db.subscriptionDao().getAll()

    /**
     * Idempotent post-migration backfill: computes missing canonical fingerprints, runs offline
     * Stage 1/2a country detection for unlabeled rows, and marks structurally invalid rows
     * without ever deleting or overwriting existing user data or uncertain ownership.
     */
    suspend fun ensureMigrated(countryDetector: CountryDetector = CountryDetector()) {
        if (migrationCompleted) {
            // Retry retention cleanup on later entry points even after the data backfill succeeds.
            runCatching { purgeExpiredTrash() }
            return
        }
        migrationMutex.withLock {
            if (migrationCompleted) {
                runCatching { purgeExpiredTrash() }
                return
            }
            withContext(Dispatchers.IO) {
                val subs = db.subscriptionDao().getAll()
                val subIds = subs.mapTo(HashSet()) { it.id }
                val profiles = db.serverProfileDao().getAll()
                val updates = ArrayList<ServerProfileEntity>()
                for (p in profiles) {
                    var next = p
                    if (next.canonicalFingerprint.isBlank()) {
                        val fp = ConfigFingerprint.compute(next)
                        val resolvedSource = when {
                            next.sourceType == SourceType.LEGACY_UNCLASSIFIED.name -> SourceType.LEGACY_UNCLASSIFIED.name
                            next.subscriptionId != null && next.subscriptionId in subIds -> SourceType.SUBSCRIPTION.name
                            next.subscriptionId == null && next.groupName in setOf("Local", "Imported", "") -> SourceType.MANUAL.name
                            else -> SourceType.LEGACY_UNCLASSIFIED.name
                        }
                        next = next.copy(
                            canonicalFingerprint = fp,
                            sourceType = resolvedSource
                        )
                    }
                    if (!ClientPolicy.isSupported(next) &&
                        HealthStatus.parse(next.healthStatus) == HealthStatus.UNTESTED
                    ) {
                        next = next.copy(
                            healthStatus = HealthStatus.INVALID.name,
                            lastErrorCategory = ErrorCategory.INVALID_CONFIG.name
                        )
                    }
                    if (CountryConfidence.parse(next.countryConfidence) == CountryConfidence.UNKNOWN &&
                        next.countryCode == null
                    ) {
                        next = countryDetector.detectOffline(next)
                    }
                    if (next != p) updates += next
                }
                if (updates.isNotEmpty()) {
                    db.withTransaction {
                        updates.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                            db.serverProfileDao().upsertAll(chunk)
                        }
                    }
                }
                migrationCompleted = true
                // Best effort for startup, retried on every later ensureMigrated call if it fails.
                runCatching { purgeExpiredTrash() }
            }
        }
    }
    suspend fun updateSettings(transform: (StabilitySettings) -> StabilitySettings) {
        dataStore.updateSettings(transform)
    }

    suspend fun syncManagedGeoRules(settings: StabilitySettings) {
        val existing = db.routingRuleDao().getAll()
        val rebuilt = GeoRulesEngine.buildManagedGeoRules(settings, existing)
        db.routingRuleDao().deleteManagedGeoRules()
        val managedOnly = rebuilt.filter { it.managedKey?.startsWith("geo-") == true }
        if (managedOnly.isNotEmpty()) {
            db.routingRuleDao().upsertAll(managedOnly)
        }
    }

    suspend fun saveProfile(profile: ServerProfileEntity, countryDetector: CountryDetector = CountryDetector()) {
        val prepared = prepareProfileForSave(profile, countryDetector)
        db.serverProfileDao().upsert(prepared)
        val settings = getSettings()
        if (settings.activeProfileId == null) {
            updateSettings { it.copy(activeProfileId = prepared.id) }
        }
    }

    suspend fun saveProfiles(profiles: List<ServerProfileEntity>, countryDetector: CountryDetector = CountryDetector()) {
        if (profiles.isEmpty()) return
        val prepared = profiles.map { prepareProfileForSave(it, countryDetector) }
        db.withTransaction {
            prepared.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().upsertAll(chunk)
            }
        }
        val settings = getSettings()
        if (settings.activeProfileId == null) {
            updateSettings { it.copy(activeProfileId = prepared.first().id) }
        }
    }

    suspend fun importManualProfiles(
        nodes: List<ServerProfileEntity>,
        countryDetector: CountryDetector = CountryDetector()
    ): ManualImportResult {
        if (nodes.isEmpty()) return ManualImportResult(0, 0, 0)
        ensureMigrated(countryDetector)
        return withContext(Dispatchers.IO) {
            val existingAll = db.serverProfileDao().getAll()
            val existingIds = existingAll.mapTo(HashSet(existingAll.size)) { it.id }
            val existingManualFingerprints = existingAll.asSequence()
                .filter { SourceType.parse(it.sourceType) == SourceType.MANUAL }
                .mapTo(HashSet()) { it.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(it) } }

            val toInsert = ArrayList<ServerProfileEntity>(nodes.size)
            var duplicates = 0
            var invalid = 0
            for (rawNode in nodes) {
                if (!ClientPolicy.isSupported(rawNode)) {
                    invalid++
                    continue
                }
                val sanitized = ProtocolGuardrails.sanitizeProfileForSave(rawNode)
                val fp = ConfigFingerprint.compute(sanitized)
                if (!existingManualFingerprints.add(fp)) {
                    duplicates++
                    continue
                }
                val candidateId = ConfigFingerprint.stableId(SourceType.MANUAL, null, fp)
                val finalId = if (existingIds.add(candidateId)) candidateId else UUID.randomUUID().toString().also { existingIds.add(it) }
                val detected = countryDetector.detectOffline(
                    sanitized.copy(
                        id = finalId,
                        subscriptionId = null,
                        sourceType = SourceType.MANUAL.name,
                        groupName = sanitized.groupName.ifBlank { "Imported" },
                        canonicalFingerprint = fp,
                        isEnabled = true,
                        healthStatus = HealthStatus.UNTESTED.name
                    )
                )
                toInsert += detected
            }
            if (toInsert.isNotEmpty()) {
                db.withTransaction {
                    toInsert.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                        db.serverProfileDao().upsertAll(chunk)
                    }
                }
                val settings = getSettings()
                if (settings.activeProfileId == null) {
                    updateSettings { it.copy(activeProfileId = toInsert.first().id) }
                }
            }
            ManualImportResult(imported = toInsert.size, duplicates = duplicates, invalid = invalid)
        }
    }

    suspend fun deleteProfile(id: String) {
        db.serverProfileDao().deleteById(id)
        val remaining = db.serverProfileDao().getAll()
        val settings = getSettings()
        if (settings.activeProfileId == id) {
            updateSettings { it.copy(activeProfileId = remaining.firstOrNull()?.id) }
        }
    }

    suspend fun setSubscriptionEnabledForConnection(id: String, enabled: Boolean) {
        db.subscriptionDao().setEnabledForConnection(id, enabled)
    }

    suspend fun setManualConfigsEnabledForConnection(enabled: Boolean) {
        updateSettings { it.copy(manualConfigsEnabledForConnection = enabled) }
    }

    suspend fun setProfileEnabled(id: String, enabled: Boolean) {
        db.withTransaction {
            db.serverProfileDao().setEnabledWithTimestamp(
                id = id,
                enabled = enabled,
                disabledAt = if (enabled) null else System.currentTimeMillis()
            )
        }
    }

    suspend fun setProfilesEnabled(ids: List<String>, enabled: Boolean) {
        if (ids.isEmpty()) return
        val disabledAt = if (enabled) null else System.currentTimeMillis()
        db.withTransaction {
            ids.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().setEnabledByIdsWithTimestamp(chunk, enabled, disabledAt)
            }
        }
    }

    suspend fun deleteProfilesWithBackup(ids: List<String>, label: String): DeletedBackupBatch? {
        if (ids.isEmpty()) return null
        val idSet = ids.toHashSet()
        val now = System.currentTimeMillis()
        val batchId = UUID.randomUUID().toString()
        var createdBatch: DeletedBackupBatch? = null
        val remaining = db.withTransaction {
            val all = db.serverProfileDao().getAll()
            val targets = all.filter { it.id in idSet }
            if (targets.isEmpty()) return@withTransaction all
            val backups = targets.map { profile ->
                DeletedProfileBackupEntity(
                    batchId = batchId,
                    deletedAt = now,
                    label = label,
                    profileId = profile.id,
                    payloadJson = ServerProfileCodec.encode(profile),
                    category = MANUAL_DELETE_CATEGORY,
                    reason = label,
                    expiresAt = now + HealthPolicy.TRASH_RETENTION_MS,
                    sourceType = profile.sourceType,
                    subscriptionId = profile.subscriptionId
                )
            }
            backups.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.deletedProfileBackupDao().insertAll(chunk)
            }
            targets.map { it.id }.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().deleteByIds(chunk)
            }
            val affectedSubscriptionIds = targets.asSequence()
                .filter { SourceType.parse(it.sourceType) == SourceType.SUBSCRIPTION }
                .mapNotNull { it.subscriptionId }
                .toSet()
            syncSubscriptionNodeCounts(affectedSubscriptionIds)
            createdBatch = DeletedBackupBatch(
                batchId = batchId,
                deletedAt = now,
                label = label,
                count = targets.size
            )
            db.serverProfileDao().getAll()
        }
        val settings = getSettings()
        if (settings.activeProfileId != null && remaining.none { it.id == settings.activeProfileId }) {
            updateSettings { it.copy(activeProfileId = remaining.firstOrNull()?.id) }
        }
        return createdBatch
    }

    suspend fun restoreBackupBatch(batchId: String): Int =
        db.withTransaction {
            val rows = db.deletedProfileBackupDao().getByBatchId(batchId)
            restoreTrashRows(rows)
        }

    /**
     * Moves reviewed cleanup suggestions into Trash in a single transaction. The active
     * connection is always excluded so a cleanup can never tear down the live tunnel.
     */
    suspend fun moveProfilesToTrash(
        items: List<SmartCleanupItem>,
        label: String,
        activeNodeId: String? = null
    ): DeletedBackupBatch? {
        if (items.isEmpty()) return null
        val requested = items.associateBy { it.profile.id }
        if (requested.isEmpty()) return null
        val now = System.currentTimeMillis()
        val batchId = UUID.randomUUID().toString()
        var createdBatch: DeletedBackupBatch? = null
        var movedIds: Set<String> = emptySet()
        val remaining = db.withTransaction {
            val targets = db.serverProfileDao().getAll()
                .filter { it.id in requested && it.id != activeNodeId }
            if (targets.isEmpty()) return@withTransaction db.serverProfileDao().getAll()
            movedIds = targets.mapTo(HashSet()) { it.id }
            val rows = targets.map { profile ->
                val item = requested.getValue(profile.id)
                DeletedProfileBackupEntity(
                    batchId = batchId,
                    deletedAt = now,
                    label = label,
                    profileId = profile.id,
                    payloadJson = ServerProfileCodec.encode(profile),
                    category = item.category.name,
                    reason = item.reason,
                    expiresAt = now + HealthPolicy.TRASH_RETENTION_MS,
                    sourceType = profile.sourceType,
                    subscriptionId = profile.subscriptionId
                )
            }
            rows.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.deletedProfileBackupDao().insertAll(chunk)
            }
            targets.map { it.id }.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().deleteByIds(chunk)
            }
            val affectedSubscriptionIds = targets.asSequence()
                .filter { SourceType.parse(it.sourceType) == SourceType.SUBSCRIPTION }
                .mapNotNull { it.subscriptionId }
                .toSet()
            syncSubscriptionNodeCounts(affectedSubscriptionIds)
            createdBatch = DeletedBackupBatch(
                batchId = batchId,
                deletedAt = now,
                label = label,
                count = rows.size
            )
            db.serverProfileDao().getAll()
        }
        if (movedIds.isNotEmpty()) {
            val settings = getSettings()
            val activeId = settings.activeProfileId
            if (activeId != null && activeId in movedIds) {
                updateSettings { it.copy(activeProfileId = remaining.firstOrNull()?.id) }
            }
        }
        return createdBatch
    }

    /**
     * Restores Trash rows preserving their original IDs, source ownership, and preferences.
     * A configuration whose fingerprint already exists in the same manual/subscription scope is
     * not inserted twice; its Trash row is still cleared.
     */
    suspend fun restoreTrashEntries(entryIds: List<String>): Int {
        if (entryIds.isEmpty()) return 0
        return db.withTransaction {
            val rows = entryIds.chunked(SQLITE_CHUNK_SIZE)
                .flatMap { chunk -> db.deletedProfileBackupDao().getByIds(chunk) }
            restoreTrashRows(rows)
        }
    }

    suspend fun permanentlyDeleteTrashEntries(entryIds: List<String>): Int {
        if (entryIds.isEmpty()) return 0
        return db.withTransaction {
            val count = entryIds.chunked(SQLITE_CHUNK_SIZE)
                .sumOf { chunk -> db.deletedProfileBackupDao().getByIds(chunk).size }
            if (count == 0) return@withTransaction 0
            entryIds.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.deletedProfileBackupDao().deleteByIds(chunk)
            }
            count
        }
    }

    suspend fun purgeExpiredTrash(nowMs: Long = System.currentTimeMillis()): Int =
        db.deletedProfileBackupDao().deleteExpired(nowMs)

    suspend fun removeDuplicateManualProfiles(activeNodeId: String? = null): DeletedBackupBatch? {
        ensureMigrated()
        val manual = db.serverProfileDao().getBySourceType(SourceType.MANUAL.name)
        if (manual.size <= 1) return null
        val grouped = manual.groupBy { it.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(it) } }
        val duplicateIds = mutableListOf<String>()
        for ((_, group) in grouped) {
            if (group.size <= 1) continue
            val keeper = group.sortedWith(
                compareByDescending<ServerProfileEntity> { it.id == activeNodeId }
                    .thenByDescending { HealthStatus.parse(it.healthStatus) == HealthStatus.WORKING }
                    .thenByDescending { CountryConfidence.parse(it.countryConfidence) == CountryConfidence.VERIFIED }
                    .thenByDescending { it.isPinned }
                    .thenByDescending { it.isEnabled }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                    .thenBy { it.createdAt }
            ).first()
            group.filter { it.id != keeper.id }.forEach { duplicateIds += it.id }
        }
        if (duplicateIds.isEmpty()) return null
        return deleteProfilesWithBackup(duplicateIds, "Duplicate cleanup")
    }

    suspend fun reclassifyLegacyToManual(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().updateSourceOwnership(
                    ids = chunk,
                    sourceType = SourceType.MANUAL.name,
                    subscriptionId = null,
                    groupName = "Imported"
                )
            }
        }
    }

    suspend fun setManualCountry(
        id: String,
        countryCode: String?,
        countryDetector: CountryDetector = CountryDetector()
    ) {
        val normalized = ServerCountryResolver.normalizeCode(countryCode)
        db.withTransaction {
            val existing = db.serverProfileDao().getById(id) ?: return@withTransaction
            val updated = if (normalized != null) {
                existing.copy(
                    manualCountryCode = normalized,
                    countryCode = null,
                    countryConfidence = CountryConfidence.ESTIMATED.name,
                    countrySource = CountrySource.MANUAL.name,
                    countryVerifiedAt = null,
                    exitIp = null
                )
            } else {
                countryDetector.detectOffline(
                    existing.copy(
                        manualCountryCode = null,
                        countryCode = null,
                        countryConfidence = CountryConfidence.UNKNOWN.name,
                        countrySource = CountrySource.NONE.name,
                        countryVerifiedAt = null,
                        exitIp = null
                    )
                )
            }
            db.serverProfileDao().upsert(updated)
        }
    }

    suspend fun updateProfileHealth(updated: ServerProfileEntity) = db.withTransaction {
        mergeProfileHealth(updated)
    }

    internal suspend fun updateProfileHealthBatch(results: List<ServerProfileEntity>) {
        if (results.isEmpty()) return
        db.withTransaction { results.forEach { mergeProfileHealth(it) } }
    }

    private suspend fun mergeProfileHealth(result: ServerProfileEntity) {
        val dao = db.serverProfileDao()
        val current = dao.getById(result.id) ?: return
        if (current.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(current) } !=
            result.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(result) } ||
            (current.lastAttemptAt ?: Long.MIN_VALUE) > (result.lastAttemptAt ?: Long.MIN_VALUE)
        ) return
        val newerExit = (result.countryVerifiedAt ?: Long.MIN_VALUE) >
            (current.countryVerifiedAt ?: Long.MIN_VALUE)
        dao.upsert(current.copy(
            healthStatus = result.healthStatus,
            lastSuccessAt = result.lastSuccessAt,
            lastAttemptAt = result.lastAttemptAt,
            lastTestedAt = result.lastTestedAt,
            latencyMs = result.latencyMs,
            consecutiveFailures = result.consecutiveFailures,
            lastErrorCategory = result.lastErrorCategory,
            lastErrorMessage = result.lastErrorMessage,
            lastNetworkFingerprint = result.lastNetworkFingerprint,
            cooldownUntilMs = result.cooldownUntilMs,
            lastMeasurementType = result.lastMeasurementType,
            countryCode = if (newerExit) result.countryCode else current.countryCode,
            countryConfidence = if (newerExit) result.countryConfidence else current.countryConfidence,
            countrySource = if (newerExit) result.countrySource else current.countrySource,
            countryVerifiedAt = if (newerExit) result.countryVerifiedAt else current.countryVerifiedAt,
            exitIp = if (newerExit || current.countryVerifiedAt == null) result.exitIp else current.exitIp
        ))
    }

    suspend fun enrichCountriesWithGeoIp(
        ids: List<String>,
        countryDetector: CountryDetector
    ): Int {
        if (ids.isEmpty()) return 0
        val idSet = ids.toHashSet()
        val targets = db.serverProfileDao().getAll().filter { it.id in idSet }
        val updates = targets.mapNotNull { original ->
            val detected = countryDetector.detectWithEndpointGeoIp(original)
            if (detected == original) null else original to detected
        }
        if (updates.isEmpty()) return 0
        return db.withTransaction {
            val dao = db.serverProfileDao()
            var count = 0
            for ((original, detected) in updates) {
                // Do not overwrite changes made during the DNS lookup or recreate deleted rows.
                if (dao.getById(original.id) != original) continue
                dao.upsert(detected)
                count++
            }
            count
        }
    }

    private fun prepareProfileForSave(
        profile: ServerProfileEntity,
        countryDetector: CountryDetector
    ): ServerProfileEntity {
        val clean = ProtocolGuardrails.sanitizeProfileForSave(profile)
        val fp = clean.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(clean) }
        val source = if (clean.subscriptionId != null && SourceType.parse(clean.sourceType) == SourceType.MANUAL) {
            SourceType.SUBSCRIPTION.name
        } else {
            clean.sourceType
        }
        val withMeta = clean.copy(canonicalFingerprint = fp, sourceType = source)
        return if (CountryConfidence.parse(withMeta.countryConfidence) == CountryConfidence.UNKNOWN &&
            withMeta.countryCode == null
        ) {
            countryDetector.detectOffline(withMeta)
        } else {
            withMeta
        }
    }

    suspend fun setActiveProfile(id: String?) {
        updateSettings { it.copy(activeProfileId = id) }
    }

    suspend fun setSelectedCountry(code: String?) {
        val normalized = ServerCountryResolver.normalizeCode(code)
        updateSettings { it.copy(selectedCountryCode = normalized) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        db.serverProfileDao().updatePinned(id, pinned)
    }

    suspend fun setPreferred(id: String, preferred: Boolean) {
        setPinned(id, preferred)
    }

    suspend fun updateLatency(id: String, latencyMs: Int?, jitterMs: Int) {
        db.serverProfileDao().updateLatency(id, latencyMs, jitterMs, System.currentTimeMillis())
    }

    suspend fun saveSubscription(sub: SubscriptionEntity) {
        db.subscriptionDao().upsert(sub)
    }

    /** Keep identity and working nodes; a different provider must not inherit old metadata. */
    suspend fun editSubscription(id: String, name: String, url: String) {
        val normalized = url.trim().toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
        require(normalized != null && name.isNotBlank()) { "Invalid subscription details" }
        SubscriptionUrlPolicy.rejection(normalized)?.let { throw IllegalArgumentException(it) }
        db.withTransaction {
            val subscriptions = db.subscriptionDao().getAll()
            val existing = subscriptions.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Subscription not found")
            require(subscriptions.none {
                it.id != id && it.url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() == normalized
            }) { "Subscription URL already exists" }
            db.subscriptionDao().upsert(if (existing.url == normalized) {
                existing.copy(name = name.trim())
            } else {
                existing.copy(name = name.trim(), url = normalized, lastUpdatedAt = null,
                    lastStatus = "Idle", lastError = null, uploadBytes = 0, downloadBytes = 0,
                    totalBytes = 0, expireAtEpochSec = 0, profileTitle = null)
            })
        }
    }

    suspend fun deleteSubscriptionAndNodes(subId: String) {
        val remaining = db.withTransaction {
            db.serverProfileDao().deleteBySubscriptionId(subId)
            db.subscriptionDao().deleteById(subId)
            db.serverProfileDao().getAll()
        }
        val settings = getSettings()
        if (settings.activeProfileId != null && remaining.none { it.id == settings.activeProfileId }) {
            updateSettings { it.copy(activeProfileId = remaining.firstOrNull()?.id) }
        }
    }

    suspend fun recordSubscriptionRefreshFailure(id: String, url: String) =
        db.subscriptionDao().recordRefreshFailure(id, url, "Could not update subscription")


    /** Validate first: a failed fetch or invalid replacement must never delete working nodes. */
    suspend fun importSubscription(
        subscription: SubscriptionEntity,
        nodes: List<ServerProfileEntity>,
        countryDetector: CountryDetector = CountryDetector()
    ): Int {
        require(nodes.isNotEmpty() && nodes.all {
            it.subscriptionId == subscription.id && ClientPolicy.isSupported(it)
        }) { "Subscription has no valid stable Xray replacement" }
        val settings = getSettings()
        val (firstMergedId, mergedNodeCount) = db.withTransaction {
            val subscriptionDao = db.subscriptionDao()
            val storedSubscription = subscriptionDao.getById(subscription.id)
            check(storedSubscription == null || storedSubscription.url == subscription.url) {
                "Subscription URL changed while refreshing"
            }
            val pendingPreferences = LuxyBackupManager
                .decodePendingNodePreferences(storedSubscription ?: subscription)
                .associateByTo(HashMap()) { it.canonicalFingerprint }
            val existing = db.serverProfileDao().getBySubscriptionId(subscription.id)
            // Older unsupported profiles remain stored, but are never eligible to connect.
            val preserved = existing.filter { !ClientPolicy.isSupported(it) }
            val preservedIds = preserved.mapTo(HashSet()) { it.id }
            val existingByFp = existing
                .filter(ClientPolicy::isSupported)
                .associateBy { it.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(it) } }

            // Suppress nodes only while their backup remains in Trash. Permanently deleting a
            // Trash row allows a later subscription refresh to import that node again.
            val trashedFingerprints = db.deletedProfileBackupDao()
                .getBySubscriptionId(subscription.id)
                .mapNotNull { row -> ServerProfileCodec.decode(row.payloadJson) }
                .mapTo(HashSet()) { profile ->
                    profile.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(profile) }
                }
            val seenFp = HashSet<String>(nodes.size)
            val mergedNodes = ArrayList<ServerProfileEntity>(nodes.size)
            for (rawNode in nodes) {
                val clean = ProtocolGuardrails.sanitizeProfileForSave(rawNode)
                val fp = clean.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(clean) }
                if (!seenFp.add(fp) || fp in trashedFingerprints) continue
                val prev = existingByFp[fp]
                val merged = if (prev != null) {
                    val base = clean.copy(
                        id = prev.id,
                        sourceType = SourceType.SUBSCRIPTION.name,
                        canonicalFingerprint = fp,
                        isEnabled = prev.isEnabled,
                        isPinned = prev.isPinned,
                        disabledAt = prev.disabledAt,
                        createdAt = prev.createdAt,
                        latencyMs = prev.latencyMs,
                        lastJitterMs = prev.lastJitterMs,
                        lastTestedAt = prev.lastTestedAt,
                        healthStatus = prev.healthStatus,
                        lastSuccessAt = prev.lastSuccessAt,
                        lastAttemptAt = prev.lastAttemptAt,
                        consecutiveFailures = prev.consecutiveFailures,
                        lastErrorCategory = prev.lastErrorCategory,
                        lastErrorMessage = prev.lastErrorMessage,
                        lastNetworkFingerprint = prev.lastNetworkFingerprint,
                        lastMeasurementType = prev.lastMeasurementType,
                        cooldownUntilMs = prev.cooldownUntilMs,
                        manualCountryCode = prev.manualCountryCode,
                        countryCode = if (CountryConfidence.parse(prev.countryConfidence) == CountryConfidence.VERIFIED) prev.countryCode else null,
                        countryConfidence = if (CountryConfidence.parse(prev.countryConfidence) == CountryConfidence.VERIFIED) prev.countryConfidence else CountryConfidence.UNKNOWN.name,
                        countrySource = if (CountryConfidence.parse(prev.countryConfidence) == CountryConfidence.VERIFIED) prev.countrySource else CountrySource.NONE.name,
                        countryVerifiedAt = if (CountryConfidence.parse(prev.countryConfidence) == CountryConfidence.VERIFIED) prev.countryVerifiedAt else null,
                        exitIp = if (CountryConfidence.parse(prev.countryConfidence) == CountryConfidence.VERIFIED) prev.exitIp else null
                    )
                    if (CountryConfidence.parse(base.countryConfidence) == CountryConfidence.VERIFIED) base
                    else countryDetector.detectOffline(base)
                } else {
                    val candidateId = ConfigFingerprint.stableId(SourceType.SUBSCRIPTION, subscription.id, fp)
                    val finalId = if (candidateId !in preservedIds) candidateId else UUID.randomUUID().toString()
                    countryDetector.detectOffline(
                        clean.copy(
                            id = finalId,
                            sourceType = SourceType.SUBSCRIPTION.name,
                            canonicalFingerprint = fp
                        )
                    )
                }
                val restored = pendingPreferences.remove(fp)
                mergedNodes += if (restored == null) merged else merged.copy(
                    isEnabled = restored.isEnabled,
                    isPinned = restored.isPinned,
                    manualCountryCode = restored.manualCountryCode,
                    disabledAt = if (restored.isEnabled) null else merged.disabledAt ?: System.currentTimeMillis()
                )
            }

            db.serverProfileDao().deleteBySubscriptionId(subscription.id)
            (preserved + mergedNodes).chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().upsertAll(chunk)
            }
            subscriptionDao.upsert(
                subscription.copy(
                    nodeCount = mergedNodes.size,
                    isEnabledForConnection = storedSubscription?.isEnabledForConnection ?: subscription.isEnabledForConnection,
                    pendingNodePreferences = LuxyBackupManager.encodePendingNodePreferences(pendingPreferences.values)
                )
            )
            syncSubscriptionNodeCounts(setOf(subscription.id))
            mergedNodes.firstOrNull()?.id to mergedNodes.size
        }
        val current = db.serverProfileDao().getAll()
        if (settings.activeProfileId == null || current.none { it.id == settings.activeProfileId }) {
            setActiveProfile(firstMergedId ?: current.firstOrNull()?.id)
        }
        return mergedNodeCount
    }

    suspend fun saveRoutingRule(rule: RoutingRuleEntity) {
        db.routingRuleDao().upsert(rule)
    }

    suspend fun setRoutingRuleEnabled(id: String, enabled: Boolean) {
        db.routingRuleDao().setEnabled(id, enabled)
    }

    suspend fun deleteRoutingRule(id: String) {
        db.routingRuleDao().deleteById(id)
    }

    suspend fun logEvent(
        level: LogLevel,
        category: LogCategory,
        message: String,
        latencyMs: Int? = null
    ) {
        db.stabilityEventDao().insert(
            StabilityEventEntity(
                level = level.name,
                category = category.name,
                message = message,
                latencyMs = latencyMs
            )
        )
    }

    suspend fun clearEvents() {
        db.stabilityEventDao().clearAll()
    }

    suspend fun getStrategy(fingerprint: String): NetworkStrategyEntity? =
        db.networkStrategyDao().getByFingerprint(fingerprint)

    suspend fun upsertStrategy(strategy: NetworkStrategyEntity) {
        db.networkStrategyDao().upsert(strategy)
    }

    private suspend fun restoreTrashRows(rows: List<DeletedProfileBackupEntity>): Int {
        if (rows.isEmpty()) return 0
        val decodedRows = rows.mapNotNull { row ->
            ServerProfileCodec.decode(row.payloadJson)?.let { row to it }
        }
        if (decodedRows.isEmpty()) return 0

        val profilesById = db.serverProfileDao().getAll().associateByTo(HashMap()) { it.id }
        val existingIds = profilesById.keys.toMutableSet()
        val existingKeys = profilesById.values.mapTo(HashSet()) { it.scopeFingerprintKey() }
        val subscriptionIds = db.subscriptionDao().getAll().mapTo(HashSet()) { it.id }
        val toInsert = ArrayList<ServerProfileEntity>(decodedRows.size)
        val handledTrashIds = ArrayList<String>(decodedRows.size)
        val affectedSubscriptionIds = HashSet<String>()

        for ((row, decoded) in decodedRows) {
            var profile = decoded
            if (SourceType.parse(profile.sourceType) == SourceType.SUBSCRIPTION &&
                (profile.subscriptionId == null || profile.subscriptionId !in subscriptionIds)
            ) {
                profile = profile.copy(
                    sourceType = SourceType.MANUAL.name,
                    subscriptionId = null,
                    groupName = "Restored"
                )
            }

            val existingWithId = profilesById[profile.id]
            if (existingWithId != null) {
                if (ConfigFingerprint.compute(existingWithId) == ConfigFingerprint.compute(profile)) {
                    handledTrashIds += row.id
                    continue
                }
                var freshId: String
                do {
                    freshId = UUID.randomUUID().toString()
                } while (freshId in existingIds)
                profile = profile.copy(id = freshId)
            }

            val key = profile.scopeFingerprintKey()
            handledTrashIds += row.id
            if (key in existingKeys) continue

            toInsert += profile
            existingIds += profile.id
            profilesById[profile.id] = profile
            existingKeys += key
            if (SourceType.parse(profile.sourceType) == SourceType.SUBSCRIPTION) {
                profile.subscriptionId?.let { affectedSubscriptionIds += it }
            }
        }

        if (toInsert.isNotEmpty()) {
            toInsert.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
                db.serverProfileDao().upsertAll(chunk)
            }
        }
        syncSubscriptionNodeCounts(affectedSubscriptionIds)
        handledTrashIds.chunked(SQLITE_CHUNK_SIZE).forEach { chunk ->
            db.deletedProfileBackupDao().deleteByIds(chunk)
        }
        return toInsert.size
    }

    private suspend fun syncSubscriptionNodeCounts(subscriptionIds: Set<String>) {
        for (subscriptionId in subscriptionIds) {
            val count = db.serverProfileDao().countBySubscriptionId(subscriptionId)
            db.subscriptionDao().updateNodeCount(subscriptionId, count)
        }
    }

    private fun DeletedProfileBackupEntity.toTrashEntry(): TrashEntry {
        val profile = ServerProfileCodec.decode(payloadJson)
        return TrashEntry(
            id = id,
            batchId = batchId,
            deletedAt = deletedAt,
            expiresAt = expiresAt,
            label = label,
            category = category,
            reason = reason,
            profileId = profileId,
            profileName = profile?.name.orEmpty(),
            sourceType = sourceType,
            subscriptionId = subscriptionId,
            profile = profile
        )
    }

    /**
     * Identity used to reject duplicate restores: fingerprint within the same ownership scope.
     * Legacy rows have no reliable scope, so their own ID is used to keep the restore lossless.
     */
    private fun ServerProfileEntity.scopeFingerprintKey(): String {
        val scope = when (SourceType.parse(sourceType)) {
            SourceType.SUBSCRIPTION -> subscriptionId?.let { "sub:$it" } ?: "sub-unowned:$id"
            SourceType.MANUAL -> "manual"
            SourceType.LEGACY_UNCLASSIFIED -> "legacy:$id"
        }
        return "$scope:${ConfigFingerprint.compute(this)}"
    }

    companion object {
        private const val SQLITE_CHUNK_SIZE = 450
        private const val MANUAL_DELETE_CATEGORY = "MANUAL_DELETE"
    }
}
