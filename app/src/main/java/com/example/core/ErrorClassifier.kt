package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ErrorCategory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

data class ClassifiedError(
    val category: ErrorCategory,
    val userMessage: String,
    val technicalDetail: String?
)

/** Converts explicit network/configuration evidence into concise UI errors without guessing. */
object ErrorClassifier {
    private val authStatusPattern = Regex("\\bhttp\\s*(?:401|403)\\b")

    fun classify(
        message: String?,
        throwable: Throwable? = null,
        activeProfile: ServerProfileEntity? = null
    ): ClassifiedError {
        val causes = generateSequence(throwable) { it.cause }.take(12).toList()
        val evidence = buildList {
            message?.takeIf(String::isNotBlank)?.let(::add)
            causes.forEach { cause ->
                cause.message?.takeIf(String::isNotBlank)?.let(::add)
                add(cause.javaClass.simpleName)
            }
        }
        val lowerEvidence = evidence.joinToString(" ").lowercase(Locale.ROOT)
        val category = classifyEvidence(lowerEvidence, causes)
        val detail = (message?.takeIf(String::isNotBlank) ?: throwable?.message)
            ?.let { SensitiveDataRedactor.redact(it, activeProfile).take(240) }
        return ClassifiedError(category, userMessageFor(category), detail)
    }

    fun userMessageFor(category: ErrorCategory): String = when (category) {
        ErrorCategory.NO_INTERNET -> "Network unavailable"
        ErrorCategory.DNS_FAILURE -> "DNS resolution failed"
        ErrorCategory.TIMEOUT -> "Connection timed out"
        ErrorCategory.TLS_OR_HANDSHAKE -> "TLS handshake failed"
        ErrorCategory.AUTH_OR_PROTOCOL -> "Server authentication failed"
        ErrorCategory.CONNECTION_REFUSED -> "Remote server refused connection"
        ErrorCategory.INVALID_CONFIG -> "Configuration is invalid or unsupported"
        ErrorCategory.EXIT_VERIFICATION_FAILED,
        ErrorCategory.EXIT_COUNTRY_MISMATCH -> "Exit verification failed"
        ErrorCategory.NONE,
        ErrorCategory.UNKNOWN -> "Connection failed"
    }

    private fun classifyEvidence(lower: String, causes: List<Throwable>): ErrorCategory {
        when {
            ("country" in lower || "exit" in lower) &&
                ("mismatch" in lower || "does not match" in lower) ->
                return ErrorCategory.EXIT_COUNTRY_MISMATCH
            "exit verification" in lower || "verify exit" in lower || "exit country verification" in lower ->
                return ErrorCategory.EXIT_VERIFICATION_FAILED
            "invalid credential" in lower || "authentication failed" in lower ||
                "authentication failure" in lower || "authentication required" in lower ||
                "auth failed" in lower || "auth failure" in lower ||
                "authentication rejected" in lower || "auth rejected" in lower ||
                "credentials rejected" in lower || "rejected credentials" in lower ||
                "bad credentials" in lower || "invalid user" in lower ||
                "unauthorized" in lower || "forbidden" in lower || "access denied" in lower ||
                authStatusPattern.containsMatchIn(lower) ||
                "invalid uuid" in lower || "invalid password" in lower ||
                "protocol violation" in lower || "protocol error" in lower ->
                return ErrorCategory.AUTH_OR_PROTOCOL
            "invalid config" in lower || "invalid configuration" in lower ||
                "malformed config" in lower || "malformed configuration" in lower ||
                "configuration is invalid" in lower || "unsupported configuration" in lower ||
                "not supported" in lower || "failed to compile" in lower || "config compile" in lower ->
                return ErrorCategory.INVALID_CONFIG
            "no internet" in lower || "network is unreachable" in lower ||
                "network unavailable" in lower || "enetunreach" in lower || "no route to host" in lower ->
                return ErrorCategory.NO_INTERNET
            "unknown host" in lower || "dns" in lower || "name or service not known" in lower ||
                "temporary failure in name resolution" in lower || "failed to resolve" in lower ||
                "could not resolve" in lower ->
                return ErrorCategory.DNS_FAILURE
            "timed out" in lower || "timeout" in lower -> return ErrorCategory.TIMEOUT
            "connection refused" in lower || "econnrefused" in lower ->
                return ErrorCategory.CONNECTION_REFUSED
            "tls" in lower || "ssl" in lower || "certificate" in lower || "handshake" in lower ->
                return ErrorCategory.TLS_OR_HANDSHAKE
        }
        when {
            causes.any { it is UnknownHostException } -> return ErrorCategory.DNS_FAILURE
            causes.any { it is SocketTimeoutException || it is TimeoutException } -> return ErrorCategory.TIMEOUT
            causes.any { it is NoRouteToHostException } -> return ErrorCategory.NO_INTERNET
            causes.any { it is SSLException } -> return ErrorCategory.TLS_OR_HANDSHAKE
            causes.any { it is ConnectException } && "refused" in lower ->
                return ErrorCategory.CONNECTION_REFUSED
        }
        return ErrorCategory.UNKNOWN
    }
}
