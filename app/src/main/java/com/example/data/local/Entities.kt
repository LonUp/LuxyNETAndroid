package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import java.util.UUID

@Entity(tableName = "server_profiles")
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
    val vaultKeyScheme: String = ""
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
    val createdAt: Long = System.currentTimeMillis()
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
