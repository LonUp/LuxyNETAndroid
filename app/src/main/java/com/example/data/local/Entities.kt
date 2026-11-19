package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthStatus
import com.example.data.model.MeasurementType
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.SourceType
import com.example.data.model.TransportType
import java.util.UUID

@Entity(
    tableName = "server_profiles",
    indices = [
        Index(value = ["subscriptionId"], name = "index_server_profiles_subscriptionId"),
        Index(value = ["sourceType"], name = "index_server_profiles_sourceType"),
        Index(value = ["canonicalFingerprint"], name = "index_server_profiles_canonicalFingerprint"),
        Index(value = ["isEnabled"], name = "index_server_profiles_isEnabled")
    ]
)
data class ServerProfileEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = ProxyProtocol.VLESS.name,
    val address: String = "",
    val port: Int = 443,
    val uuidOrPassword: String = "",
    val encryption: String = "none",
    val transport: String = TransportType.TCP.name,
    val security: String = SecurityType.TLS.name,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val flow: String = "",
    val allowInsecure: Boolean = false,
    val cipherSuites: String = "",
    val finalMask: String = "",
    val echConfigList: String = "",
    val echForceQuery: String = "none",
    val mldsa65Verify: String = "",
    val subscriptionId: String? = null,
    val groupName: String = "Local",
    val latencyMs: Int? = null,
    val lastJitterMs: Int = 0,
    val lastTestedAt: Long? = null,
    val isPinned: Boolean = false,
    val rawUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isLocked: Boolean = false,
    val vaultExpiresAtMs: Long? = null,
    val vaultKeyScheme: String = "",
    /** JSON [com.example.data.model.NodeFeatureOverrides]. Empty means inherit globals, still subject to the compatibility matrix. */
    val featureOverrides: String = "",
    val sourceType: String = SourceType.MANUAL.name,
    val canonicalFingerprint: String = "",
    val isEnabled: Boolean = true,
    val healthStatus: String = HealthStatus.UNTESTED.name,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastErrorCategory: String = ErrorCategory.NONE.name,
    val lastErrorMessage: String? = null,
    val lastNetworkFingerprint: String = "",
    val cooldownUntilMs: Long = 0L,
    val countryCode: String? = null,
    val countryConfidence: String = CountryConfidence.UNKNOWN.name,
    val countrySource: String = CountrySource.NONE.name,
    val countryVerifiedAt: Long? = null,
    val exitIp: String? = null,
    val manualCountryCode: String? = null
    ,
    val disabledAt: Long? = null,
    val lastMeasurementType: String = MeasurementType.NONE.name
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val autoUpdateHours: Int = 12,
    val lastUpdatedAt: Long? = null,
    val nodeCount: Int = 0,
    val lastStatus: String = "Idle",
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Quota snapshot from the `subscription-userinfo` header; 0 means "not reported". */
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireAtEpochSec: Long = 0L,
    /** Panel-supplied title (`profile-title` header), falls back to [name]. */
    val profileTitle: String? = null,
    val isEnabledForConnection: Boolean = true
)

@Entity(
    tableName = "deleted_profile_backups",
    indices = [
        Index(value = ["batchId"], name = "index_deleted_profile_backups_batchId"),
        Index(value = ["deletedAt"], name = "index_deleted_profile_backups_deletedAt"),
        Index(value = ["expiresAt"], name = "index_deleted_profile_backups_expiresAt")
    ]
)
data class DeletedProfileBackupEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val deletedAt: Long,
    val label: String,
    val profileId: String,
    val payloadJson: String
    ,
    val category: String = "",
    val reason: String = "",
    val expiresAt: Long = 0L,
    val sourceType: String = SourceType.MANUAL.name,
    val subscriptionId: String? = null
)

@Entity(
    tableName = "routing_rules",
    indices = [Index(value = ["managedKey"], name = "index_routing_rules_managedKey")]
)
data class RoutingRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val matchType: String,
    val pattern: String,
    val action: String,
    val isEnabled: Boolean = true,
    val priority: Int = 100,
    val managedKey: String? = null
)

@Entity(
    tableName = "stability_events",
    indices = [Index(value = ["timestamp"], name = "index_stability_events_timestamp")]
)
data class StabilityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val category: String,
    val message: String,
    val latencyMs: Int? = null
)

@Entity(tableName = "network_strategies")
data class NetworkStrategyEntity(
    @PrimaryKey val networkFingerprint: String,
    val transportType: String,
    val preferredEscalationLevel: Int,
    val preferredPreset: String,
    val lastCleanEndpoint: String = "",
    val lastSuccessAt: Long = System.currentTimeMillis(),
    val successCount: Int = 1,
    val stallCount: Int = 0
)
