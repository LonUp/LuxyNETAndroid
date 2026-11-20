package com.example.data.model

import com.example.core.ClientPolicy
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import org.json.JSONObject
import java.util.Locale

enum class SourceType {
    SUBSCRIPTION,
    MANUAL,
    LEGACY_UNCLASSIFIED;

    companion object {
        fun parse(raw: String?): SourceType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

enum class HealthStatus {
    WORKING,
    FAILED,
    UNTESTED,
    INVALID;

    companion object {
        fun parse(raw: String?): HealthStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNTESTED
    }
}

enum class EffectiveHealthState {
    WORKING,
    FAILED,
    UNTESTED,
    DISABLED,
    INVALID
}

enum class ErrorCategory {
    NONE,
    INVALID_CONFIG,
    DNS_FAILURE,
    TIMEOUT,
    CONNECTION_REFUSED,
    TLS_OR_HANDSHAKE,
    AUTH_OR_PROTOCOL,
    NO_INTERNET,
    EXIT_COUNTRY_MISMATCH,
    EXIT_VERIFICATION_FAILED,
    UNKNOWN;

    companion object {
        fun parse(raw: String?): ErrorCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class CountryConfidence {
    VERIFIED,
    ESTIMATED,
    UNKNOWN;

    companion object {
        fun parse(raw: String?): CountryConfidence =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class CountrySource {
    NONE,
    METADATA,
    GEOIP,
    EXIT_VERIFIED,
    MANUAL;

    companion object {
        fun parse(raw: String?): CountrySource =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class MeasurementType {
    NONE,
    TCP_CONNECT,
    PROXY_HTTP_204,
    TUNNEL_CONNECT;

    companion object {
        fun parse(raw: String?): MeasurementType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

enum class VerificationState {
    IDLE,
    CHECKING,
    VERIFIED,
    PARTIAL,
    FAILED
}

enum class ManualConfigFilter {
    ALL,
    WORKING,
    FAILED,
    UNTESTED,
    DISABLED,
    PREFERRED,
    INVALID,
    UNCLASSIFIED
}

enum class CountryVerificationFilter {
    ALL,
    VERIFIED,
    UNVERIFIED
}

enum class SmartCleanupCategory {
    DUPLICATES,
    INVALID,
    REPEATED_FAILURES,
    EXTENDED_DISABLED,
    STALE_HEALTH,
    ORPHANED
}

data class SmartCleanupItem(
    val profile: ServerProfileEntity,
    val category: SmartCleanupCategory,
    val reason: String,
    val keeperId: String? = null
)

data class SmartCleanupGroup(
    val category: SmartCleanupCategory,
    val items: List<SmartCleanupItem>,
    val defaultSelected: Boolean
)

data class SmartCleanupPlan(
    val groups: List<SmartCleanupGroup>,
    val totalSuggested: Int
)

data class TrashEntry(
    val id: String,
    val batchId: String,
    val deletedAt: Long,
    val expiresAt: Long,
    val label: String,
    val category: String,
    val reason: String,
    val profileId: String,
    val profileName: String,
    val sourceType: String,
    val subscriptionId: String?,
    val profile: ServerProfileEntity?
)

data class ConnectionSourceSummary(
    val id: String,
    val isManual: Boolean,
    val name: String,
    val totalCount: Int,
    val eligibleCount: Int,
    val isEnabledForConnection: Boolean,
    val isConnected: Boolean,
    val hasError: Boolean,
    val subscription: SubscriptionEntity? = null
)

data class ManualConfigCounts(
    val total: Int = 0,
    val working: Int = 0,
    val failed: Int = 0,
    val untested: Int = 0,
    val disabled: Int = 0,
    val preferred: Int = 0,
    val invalid: Int = 0,
    val unclassified: Int = 0,
    val duplicateCount: Int = 0,
    val cleanupSuggestionCount: Int = 0
)

data class DeletedBackupBatch(
    val batchId: String,
    val deletedAt: Long,
    val label: String,
    val count: Int,
    val noticeMessage: String? = null
)
object HealthPolicy {
    const val WORKING_STALE_MS: Long = 24L * 60L * 60L * 1000L
    const val FAILURE_STALE_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val EXIT_VERIFICATION_STALE_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val STALE_HEALTH_CLEANUP_MS: Long = 14L * 24L * 60L * 60L * 1000L
    const val EXTENDED_DISABLED_MS: Long = 30L * 24L * 60L * 60L * 1000L
    const val TRASH_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L

    fun isHealthStale(
        profile: ServerProfileEntity,
        nowMs: Long = System.currentTimeMillis(),
        currentNetworkFingerprint: String? = null
    ): Boolean {
        val stored = HealthStatus.parse(profile.healthStatus)
        return when (stored) {
            HealthStatus.WORKING -> {
                val successAt = profile.lastSuccessAt
                val ageExpired = successAt == null || (nowMs - successAt) > WORKING_STALE_MS
                val networkChanged = !currentNetworkFingerprint.isNullOrBlank() &&
                    profile.lastNetworkFingerprint.isNotBlank() &&
                    profile.lastNetworkFingerprint != currentNetworkFingerprint
                ageExpired || networkChanged
            }
            HealthStatus.FAILED -> {
                val attemptAt = profile.lastAttemptAt
                attemptAt != null && (nowMs - attemptAt) > FAILURE_STALE_MS
            }
            HealthStatus.UNTESTED, HealthStatus.INVALID -> false
        }
    }
    fun effectiveHealthState(
        profile: ServerProfileEntity,
        nowMs: Long = System.currentTimeMillis(),
        currentNetworkFingerprint: String? = null
    ): EffectiveHealthState {
        if (!profile.isEnabled) return EffectiveHealthState.DISABLED
        val stored = HealthStatus.parse(profile.healthStatus)
        if (stored == HealthStatus.INVALID || !ClientPolicy.isSupported(profile)) {
            return EffectiveHealthState.INVALID
        }
        return when (stored) {
            HealthStatus.WORKING -> {
                val successAt = profile.lastSuccessAt
                val ageExpired = successAt == null || (nowMs - successAt) > WORKING_STALE_MS
                val networkChanged = !currentNetworkFingerprint.isNullOrBlank() &&
                    profile.lastNetworkFingerprint.isNotBlank() &&
                    profile.lastNetworkFingerprint != currentNetworkFingerprint
                if (ageExpired || networkChanged) EffectiveHealthState.UNTESTED
                else EffectiveHealthState.WORKING
            }
            HealthStatus.FAILED -> {
                val attemptAt = profile.lastAttemptAt
                val ageExpired = attemptAt != null && (nowMs - attemptAt) > FAILURE_STALE_MS
                val category = ErrorCategory.parse(profile.lastErrorCategory)
                val transientNetworkChanged = !currentNetworkFingerprint.isNullOrBlank() &&
                    profile.lastNetworkFingerprint.isNotBlank() &&
                    profile.lastNetworkFingerprint != currentNetworkFingerprint &&
                    category in setOf(
                        ErrorCategory.NO_INTERNET,
                        ErrorCategory.TIMEOUT,
                        ErrorCategory.DNS_FAILURE
                    )
                if (ageExpired || transientNetworkChanged) EffectiveHealthState.UNTESTED
                else EffectiveHealthState.FAILED
            }
            HealthStatus.UNTESTED -> EffectiveHealthState.UNTESTED
            HealthStatus.INVALID -> EffectiveHealthState.INVALID
        }
    }

    fun isCooldownActive(
        profile: ServerProfileEntity,
        nowMs: Long = System.currentTimeMillis(),
        currentNetworkFingerprint: String? = null
    ): Boolean {
        if (profile.cooldownUntilMs <= nowMs) return false
        val category = ErrorCategory.parse(profile.lastErrorCategory)
        if (!currentNetworkFingerprint.isNullOrBlank() &&
            profile.lastNetworkFingerprint.isNotBlank() &&
            profile.lastNetworkFingerprint != currentNetworkFingerprint &&
            category in setOf(
                ErrorCategory.NO_INTERNET,
                ErrorCategory.TIMEOUT,
                ErrorCategory.DNS_FAILURE,
                ErrorCategory.CONNECTION_REFUSED
            )
        ) {
            return false
        }
        return true
    }

    fun cooldownDurationMs(consecutiveFailures: Int, category: ErrorCategory): Long {
        if (category == ErrorCategory.INVALID_CONFIG) return 24L * 60L * 60L * 1000L
        if (category == ErrorCategory.NO_INTERNET) return 15_000L
        val step = (consecutiveFailures - 1).coerceIn(0, 6)
        val baseMs = when (category) {
            ErrorCategory.AUTH_OR_PROTOCOL -> 10L * 60L * 1000L
            ErrorCategory.EXIT_COUNTRY_MISMATCH -> 5L * 60L * 1000L
            else -> 60_000L
        }
        return (baseMs shl step).coerceAtMost(6L * 60L * 60L * 1000L)
    }
}

object ServerProfileCodec {
    fun encode(profile: ServerProfileEntity): String = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("protocol", profile.protocol)
        put("address", profile.address)
        put("port", profile.port)
        put("uuidOrPassword", profile.uuidOrPassword)
        put("encryption", profile.encryption)
        put("transport", profile.transport)
        put("security", profile.security)
        put("sni", profile.sni)
        put("alpn", profile.alpn)
        put("fingerprint", profile.fingerprint)
        put("publicKey", profile.publicKey)
        put("shortId", profile.shortId)
        put("path", profile.path)
        put("hostHeader", profile.hostHeader)
        put("serviceName", profile.serviceName)
        put("flow", profile.flow)
        put("allowInsecure", profile.allowInsecure)
        put("cipherSuites", profile.cipherSuites)
        put("finalMask", profile.finalMask)
        put("echConfigList", profile.echConfigList)
        put("echForceQuery", profile.echForceQuery)
        put("mldsa65Verify", profile.mldsa65Verify)
        if (profile.subscriptionId != null) put("subscriptionId", profile.subscriptionId)
        put("groupName", profile.groupName)
        if (profile.latencyMs != null) put("latencyMs", profile.latencyMs)
        put("lastJitterMs", profile.lastJitterMs)
        if (profile.lastTestedAt != null) put("lastTestedAt", profile.lastTestedAt)
        put("isPinned", profile.isPinned)
        put("rawUri", profile.rawUri)
        put("createdAt", profile.createdAt)
        put("isLocked", profile.isLocked)
        if (profile.vaultExpiresAtMs != null) put("vaultExpiresAtMs", profile.vaultExpiresAtMs)
        put("vaultKeyScheme", profile.vaultKeyScheme)
        put("featureOverrides", profile.featureOverrides)
        put("sourceType", profile.sourceType)
        put("canonicalFingerprint", profile.canonicalFingerprint)
        put("isEnabled", profile.isEnabled)
        put("healthStatus", profile.healthStatus)
        if (profile.lastSuccessAt != null) put("lastSuccessAt", profile.lastSuccessAt)
        if (profile.lastAttemptAt != null) put("lastAttemptAt", profile.lastAttemptAt)
        put("consecutiveFailures", profile.consecutiveFailures)
        put("lastErrorCategory", profile.lastErrorCategory)
        if (profile.lastErrorMessage != null) put("lastErrorMessage", profile.lastErrorMessage)
        put("lastNetworkFingerprint", profile.lastNetworkFingerprint)
        put("cooldownUntilMs", profile.cooldownUntilMs)
        if (profile.countryCode != null) put("countryCode", profile.countryCode)
        put("countryConfidence", profile.countryConfidence)
        put("countrySource", profile.countrySource)
        if (profile.countryVerifiedAt != null) put("countryVerifiedAt", profile.countryVerifiedAt)
        if (profile.exitIp != null) put("exitIp", profile.exitIp)
        if (profile.manualCountryCode != null) put("manualCountryCode", profile.manualCountryCode)
        if (profile.disabledAt != null) put("disabledAt", profile.disabledAt)
        put("lastMeasurementType", profile.lastMeasurementType)
    }.toString()

    fun decode(raw: String): ServerProfileEntity? = try {
        val j = JSONObject(raw)
        ServerProfileEntity(
            id = j.getString("id"),
            name = j.optString("name", ""),
            protocol = j.optString("protocol", ProxyProtocol.VLESS.name),
            address = j.optString("address", ""),
            port = j.optInt("port", 443),
            uuidOrPassword = j.optString("uuidOrPassword", ""),
            encryption = j.optString("encryption", "none"),
            transport = j.optString("transport", TransportType.TCP.name),
            security = j.optString("security", SecurityType.TLS.name),
            sni = j.optString("sni", ""),
            alpn = j.optString("alpn", ""),
            fingerprint = j.optString("fingerprint", "chrome"),
            publicKey = j.optString("publicKey", ""),
            shortId = j.optString("shortId", ""),
            path = j.optString("path", ""),
            hostHeader = j.optString("hostHeader", ""),
            serviceName = j.optString("serviceName", ""),
            flow = j.optString("flow", ""),
            allowInsecure = j.optBoolean("allowInsecure", false),
            cipherSuites = j.optString("cipherSuites", ""),
            finalMask = j.optString("finalMask", ""),
            echConfigList = j.optString("echConfigList", ""),
            echForceQuery = j.optString("echForceQuery", "none"),
            mldsa65Verify = j.optString("mldsa65Verify", ""),
            subscriptionId = if (j.has("subscriptionId") && !j.isNull("subscriptionId")) j.getString("subscriptionId") else null,
            groupName = j.optString("groupName", "Local"),
            latencyMs = if (j.has("latencyMs") && !j.isNull("latencyMs")) j.getInt("latencyMs") else null,
            lastJitterMs = j.optInt("lastJitterMs", 0),
            lastTestedAt = if (j.has("lastTestedAt") && !j.isNull("lastTestedAt")) j.getLong("lastTestedAt") else null,
            isPinned = j.optBoolean("isPinned", false),
            rawUri = j.optString("rawUri", ""),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()),
            isLocked = j.optBoolean("isLocked", false),
            vaultExpiresAtMs = if (j.has("vaultExpiresAtMs") && !j.isNull("vaultExpiresAtMs")) j.getLong("vaultExpiresAtMs") else null,
            vaultKeyScheme = j.optString("vaultKeyScheme", ""),
            featureOverrides = j.optString("featureOverrides", ""),
            sourceType = j.optString("sourceType", SourceType.MANUAL.name),
            canonicalFingerprint = j.optString("canonicalFingerprint", ""),
            isEnabled = j.optBoolean("isEnabled", true),
            healthStatus = j.optString("healthStatus", HealthStatus.UNTESTED.name),
            lastSuccessAt = if (j.has("lastSuccessAt") && !j.isNull("lastSuccessAt")) j.getLong("lastSuccessAt") else null,
            lastAttemptAt = if (j.has("lastAttemptAt") && !j.isNull("lastAttemptAt")) j.getLong("lastAttemptAt") else null,
            consecutiveFailures = j.optInt("consecutiveFailures", 0),
            lastErrorCategory = j.optString("lastErrorCategory", ErrorCategory.NONE.name),
            lastErrorMessage = if (j.has("lastErrorMessage") && !j.isNull("lastErrorMessage")) j.getString("lastErrorMessage") else null,
            lastNetworkFingerprint = j.optString("lastNetworkFingerprint", ""),
            cooldownUntilMs = j.optLong("cooldownUntilMs", 0L),
            countryCode = if (j.has("countryCode") && !j.isNull("countryCode")) j.getString("countryCode") else null,
            countryConfidence = j.optString("countryConfidence", CountryConfidence.UNKNOWN.name),
            countrySource = j.optString("countrySource", CountrySource.NONE.name),
            countryVerifiedAt = if (j.has("countryVerifiedAt") && !j.isNull("countryVerifiedAt")) j.getLong("countryVerifiedAt") else null,
            exitIp = if (j.has("exitIp") && !j.isNull("exitIp")) j.getString("exitIp") else null,
            manualCountryCode = if (j.has("manualCountryCode") && !j.isNull("manualCountryCode")) j.getString("manualCountryCode") else null
            ,
            disabledAt = if (j.has("disabledAt") && !j.isNull("disabledAt")) j.getLong("disabledAt") else null,
            lastMeasurementType = j.optString("lastMeasurementType", MeasurementType.NONE.name)
        )
    } catch (_: Exception) {
        null
    }
}
