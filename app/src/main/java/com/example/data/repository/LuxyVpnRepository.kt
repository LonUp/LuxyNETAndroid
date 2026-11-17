package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.ClientPolicy
import com.example.core.GeoRulesEngine
import com.example.core.ProtocolGuardrails
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.StabilitySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class LuxyVpnRepository(
    private val db: LuxyVpnDatabase,
    private val dataStore: SettingsDataStore
) {
    val profilesFlow: Flow<List<ServerProfileEntity>> = db.serverProfileDao().observeAll()
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = db.subscriptionDao().observeAll()
    val routingRulesFlow: Flow<List<RoutingRuleEntity>> = db.routingRuleDao().observeAll()
    val stabilityEventsFlow: Flow<List<StabilityEventEntity>> = db.stabilityEventDao().observeRecent()
    val networkStrategiesFlow: Flow<List<NetworkStrategyEntity>> = db.networkStrategyDao().observeAll()
    val settingsFlow: Flow<StabilitySettings> = dataStore.settingsFlow

    suspend fun getSettings(): StabilitySettings = settingsFlow.first()
    suspend fun getAllProfiles(): List<ServerProfileEntity> = db.serverProfileDao().getAll()
    suspend fun getAllRoutingRules(): List<RoutingRuleEntity> = db.routingRuleDao().getAll()
    suspend fun getAllSubscriptions(): List<SubscriptionEntity> = db.subscriptionDao().getAll()

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

    suspend fun saveProfile(profile: ServerProfileEntity) {
        val clean = ProtocolGuardrails.sanitizeProfileForSave(profile)
        db.serverProfileDao().upsert(clean)
        val settings = getSettings()
        if (settings.activeProfileId == null) {
            updateSettings { it.copy(activeProfileId = clean.id) }
        }
    }

    suspend fun saveProfiles(profiles: List<ServerProfileEntity>) {
        if (profiles.isEmpty()) return
        val sanitized = profiles.map { ProtocolGuardrails.sanitizeProfileForSave(it) }
        db.serverProfileDao().upsertAll(sanitized)
        val settings = getSettings()
        if (settings.activeProfileId == null) {
            updateSettings { it.copy(activeProfileId = sanitized.first().id) }
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

    suspend fun setActiveProfile(id: String?) {
        updateSettings { it.copy(activeProfileId = id) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        db.serverProfileDao().updatePinned(id, pinned)
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


    /** Validate first: a failed fetch or invalid replacement must never delete working nodes. */
    suspend fun importSubscription(subscription: SubscriptionEntity, nodes: List<ServerProfileEntity>) {
        require(nodes.isNotEmpty() && nodes.all {
            it.subscriptionId == subscription.id && ClientPolicy.isSupported(it)
        }) { "Subscription has no valid stable Xray replacement" }
        val settings = getSettings()
        db.withTransaction {
            // Older unsupported profiles remain stored, but are never eligible to connect.
            val preserved = db.serverProfileDao().getAll().filter {
                it.subscriptionId == subscription.id && !ClientPolicy.isSupported(it)
            }
            db.serverProfileDao().deleteBySubscriptionId(subscription.id)
            db.serverProfileDao().upsertAll(preserved + nodes)
            db.subscriptionDao().upsert(subscription)
        }
        val current = db.serverProfileDao().getAll()
        if (settings.activeProfileId == null || current.none { it.id == settings.activeProfileId }) {
            setActiveProfile(nodes.first().id)
        }
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
}
