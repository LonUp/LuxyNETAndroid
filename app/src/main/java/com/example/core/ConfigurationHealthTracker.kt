package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import com.example.data.model.MeasurementType

/**
 * Pure state transitions for configuration health tracking, error categorization,
 * cooldown/backoff, and invalidation on config edits. Never deletes failed configs.
 */
object ConfigurationHealthTracker {

    fun classifyError(message: String?, throwable: Throwable? = null): ErrorCategory =
        ErrorClassifier.classify(message, throwable).category

    /**
     * A null exit IP/country indicates a connectivity-only check and preserves existing metadata.
     */
    fun recordSuccess(
        profile: ServerProfileEntity,
        latencyMs: Int?,
        networkFingerprint: String,
        exitIp: String? = null,
        verifiedCountryCode: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        measurementType: MeasurementType = MeasurementType.NONE
    ): ServerProfileEntity {
        val normalizedCountry = ServerCountryResolver.normalizeCode(verifiedCountryCode)
        return profile.copy(
            healthStatus = HealthStatus.WORKING.name,
            lastSuccessAt = nowMs,
            lastAttemptAt = nowMs,
            lastTestedAt = nowMs,
            latencyMs = latencyMs ?: profile.latencyMs,
            consecutiveFailures = 0,
            lastErrorCategory = ErrorCategory.NONE.name,
            lastErrorMessage = null,
            lastNetworkFingerprint = networkFingerprint,
            cooldownUntilMs = 0L,
            countryCode = normalizedCountry ?: profile.countryCode,
            countryConfidence = if (normalizedCountry != null) CountryConfidence.VERIFIED.name else profile.countryConfidence,
            countrySource = if (normalizedCountry != null) CountrySource.EXIT_VERIFIED.name else profile.countrySource,
            countryVerifiedAt = if (normalizedCountry != null) nowMs else profile.countryVerifiedAt,
            exitIp = exitIp ?: profile.exitIp,
            lastMeasurementType = measurementType.name
        )
    }

    fun recordFailure(
        profile: ServerProfileEntity,
        category: ErrorCategory,
        safeMessage: String?,
        networkFingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
        measurementType: MeasurementType = MeasurementType.NONE
    ): ServerProfileEntity {
        val nextFailures = profile.consecutiveFailures + 1
        val nextStatus = if (category == ErrorCategory.INVALID_CONFIG) {
            HealthStatus.INVALID
        } else {
            HealthStatus.FAILED
        }
        val cooldownMs = HealthPolicy.cooldownDurationMs(nextFailures, category)
        return profile.copy(
            healthStatus = nextStatus.name,
            lastAttemptAt = nowMs,
            lastTestedAt = nowMs,
            latencyMs = null,
            consecutiveFailures = nextFailures,
            lastErrorCategory = category.name,
            lastErrorMessage = safeMessage?.let { SensitiveDataRedactor.redact(it, profile) }?.take(160),
            lastNetworkFingerprint = networkFingerprint,
            cooldownUntilMs = nowMs + cooldownMs,
            lastMeasurementType = measurementType.name
        )
    }

    fun invalidateOnConfigEdit(
        profile: ServerProfileEntity,
        countryDetector: CountryDetector? = null
    ): ServerProfileEntity {
        val newFingerprint = ConfigFingerprint.compute(profile)
        val supported = ClientPolicy.isSupported(profile)
        if (newFingerprint == profile.canonicalFingerprint && supported) {
            return profile
        }
        val reset = profile.copy(
            canonicalFingerprint = newFingerprint,
            healthStatus = if (supported) HealthStatus.UNTESTED.name else HealthStatus.INVALID.name,
            lastSuccessAt = null,
            lastAttemptAt = null,
            lastTestedAt = null,
            latencyMs = null,
            consecutiveFailures = 0,
            lastErrorCategory = if (supported) ErrorCategory.NONE.name else ErrorCategory.INVALID_CONFIG.name,
            lastErrorMessage = null,
            cooldownUntilMs = 0L,
            countryCode = if (CountryConfidence.parse(profile.countryConfidence) == CountryConfidence.VERIFIED) null else profile.countryCode,
            countryConfidence = if (CountryConfidence.parse(profile.countryConfidence) == CountryConfidence.VERIFIED) {
                CountryConfidence.UNKNOWN.name
            } else {
                profile.countryConfidence
            },
            countrySource = if (CountryConfidence.parse(profile.countryConfidence) == CountryConfidence.VERIFIED) {
                CountrySource.NONE.name
            } else {
                profile.countrySource
            },
            countryVerifiedAt = null,
            lastMeasurementType = MeasurementType.NONE.name,
            exitIp = null
        )
        return countryDetector?.detectOffline(reset) ?: reset
    }
}
