package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Pure state transitions for configuration health tracking, error categorization,
 * cooldown/backoff, and invalidation on config edits. Never deletes failed configs.
 */
object ConfigurationHealthTracker {

    fun classifyError(message: String?, throwable: Throwable? = null): ErrorCategory {
        when (throwable) {
            is UnknownHostException -> return ErrorCategory.DNS_FAILURE
            is SocketTimeoutException -> return ErrorCategory.TIMEOUT
            is ConnectException -> return ErrorCategory.CONNECTION_REFUSED
            is SSLException -> return ErrorCategory.TLS_OR_HANDSHAKE
            is IllegalArgumentException -> return ErrorCategory.INVALID_CONFIG
        }
        val lower = message?.lowercase(Locale.ROOT).orEmpty()
        return when {
            lower.isEmpty() -> ErrorCategory.UNKNOWN
            "not supported" in lower || "compile" in lower || "invalid" in lower || "malformed" in lower ->
                ErrorCategory.INVALID_CONFIG
            "country" in lower && "mismatch" in lower -> ErrorCategory.EXIT_COUNTRY_MISMATCH
            "verify exit" in lower || "exit country" in lower -> ErrorCategory.EXIT_VERIFICATION_FAILED
            "no internet" in lower || "network is unreachable" in lower || "enetunreach" in lower ->
                ErrorCategory.NO_INTERNET
            "timed out" in lower || "timeout" in lower -> ErrorCategory.TIMEOUT
            "dns" in lower || "unknown host" in lower || "resolve" in lower -> ErrorCategory.DNS_FAILURE
            "refused" in lower || "econnrefused" in lower -> ErrorCategory.CONNECTION_REFUSED
            "tls" in lower || "ssl" in lower || "certificate" in lower || "handshake" in lower ->
                ErrorCategory.TLS_OR_HANDSHAKE
            "auth" in lower || "password" in lower || "uuid" in lower || "rejected" in lower ->
                ErrorCategory.AUTH_OR_PROTOCOL
            else -> ErrorCategory.UNKNOWN
        }
    }

    fun recordSuccess(
        profile: ServerProfileEntity,
        latencyMs: Int?,
        networkFingerprint: String,
        exitIp: String? = null,
        verifiedCountryCode: String? = null,
        nowMs: Long = System.currentTimeMillis()
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
            exitIp = exitIp ?: profile.exitIp
        )
    }

    fun recordFailure(
        profile: ServerProfileEntity,
        category: ErrorCategory,
        safeMessage: String?,
        networkFingerprint: String,
        nowMs: Long = System.currentTimeMillis()
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
            lastErrorMessage = safeMessage?.take(160),
            lastNetworkFingerprint = networkFingerprint,
            cooldownUntilMs = nowMs + cooldownMs
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
            exitIp = null
        )
        return countryDetector?.detectOffline(reset) ?: reset
    }
}
