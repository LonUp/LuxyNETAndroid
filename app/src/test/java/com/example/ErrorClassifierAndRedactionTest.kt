package com.example

import com.example.core.ConfigurationHealthTracker
import com.example.core.ErrorClassifier
import com.example.core.SensitiveDataRedactor
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ErrorCategory
import com.example.data.model.MeasurementType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassifierAndRedactionTest {
    @Test
    fun classifiesExplicitFailureEvidenceAndUsesFriendlyMessages() {
        assertClassified("Network is unreachable", ErrorCategory.NO_INTERNET, "Network unavailable")
        assertEquals(ErrorCategory.NO_INTERNET, ErrorClassifier.classify(null, NoRouteToHostException()).category)
        assertClassified("DNS lookup failed", ErrorCategory.DNS_FAILURE, "DNS resolution failed")
        assertEquals(ErrorCategory.DNS_FAILURE, ErrorClassifier.classify(null, UnknownHostException()).category)
        assertEquals(ErrorCategory.DNS_FAILURE, ConfigurationHealthTracker.classifyError("DNS lookup failed"))
        assertClassified("Connection timed out", ErrorCategory.TIMEOUT, "Connection timed out")
        assertEquals(ErrorCategory.TIMEOUT, ErrorClassifier.classify(null, SocketTimeoutException()).category)
        assertClassified("TLS handshake failed", ErrorCategory.TLS_OR_HANDSHAKE, "TLS handshake failed")
        assertEquals(
            ErrorCategory.TLS_OR_HANDSHAKE,
            ErrorClassifier.classify(null, SSLHandshakeException("handshake failure")).category
        )
        assertClassified("Authentication failed", ErrorCategory.AUTH_OR_PROTOCOL, "Server authentication failed")
        assertClassified("Connection refused", ErrorCategory.CONNECTION_REFUSED, "Remote server refused connection")
        assertEquals(
            ErrorCategory.CONNECTION_REFUSED,
            ErrorClassifier.classify(null, ConnectException("Connection refused")).category
        )
        assertClassified(
            "Unsupported configuration",
            ErrorCategory.INVALID_CONFIG,
            "Configuration is invalid or unsupported"
        )
        assertClassified("Exit verification failed", ErrorCategory.EXIT_VERIFICATION_FAILED, "Exit verification failed")
        assertClassified("Exit country mismatch", ErrorCategory.EXIT_COUNTRY_MISMATCH, "Exit verification failed")
    }

    @Test
    fun insufficientEvidenceRemainsUnknown() {
        val classified = ErrorClassifier.classify(null, IOException("Connection failed"))
        assertEquals(ErrorCategory.UNKNOWN, classified.category)
        assertEquals("Connection failed", classified.userMessage)
        assertEquals(ErrorCategory.UNKNOWN, ErrorClassifier.classify("Something went wrong").category)
    }

    @Test
    fun redactsCredentialsTokensAndSubscriptionUrls() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val input = listOf(
            "uuid=$uuid",
            "password=plainPassword",
            "username=standaloneUser",
            "ss://aes-256-gcm:ssPassword@proxy.example:8388",
            "trojan://trojanPassword@proxy.example:443",
            "socks5://socksUser:socksPassword@proxy.example:1080",
            "publicKey=realityPublicKey shortId=realityShortId",
            "Authorization: Bearer eyJhbGciOi.example.signature",
            "https://subs.example.com/sub/privatePathToken?token=querySecret&user=privateUser"
        ).joinToString(" | ")

        val redacted = SensitiveDataRedactor.redact(input)
        listOf(
            uuid,
            "plainPassword",
            "standaloneUser",
            "ssPassword",
            "trojanPassword",
            "socksUser",
            "socksPassword",
            "realityPublicKey",
            "realityShortId",
            "eyJhbGciOi.example.signature",
            "privatePathToken",
            "querySecret",
            "privateUser"
        ).forEach { secret -> assertFalse("Secret remained in: $redacted", redacted.contains(secret)) }
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun redactsProfileAndExplicitExtraSecretsAndHealthMessages() {
        val profile = ServerProfileEntity(
            id = "redaction-test",
            address = "node.example.test",
            port = 443,
            uuidOrPassword = "profilePassword",
            publicKey = "profilePublicKey",
            shortId = "profileShortId",
            isLocked = true
        )
        val redacted = SensitiveDataRedactor.redact(
            "profilePassword profilePublicKey profileShortId externalSecret node.example.test",
            activeProfile = profile,
            extraSecrets = listOf("externalSecret")
        )
        listOf("profilePassword", "profilePublicKey", "profileShortId", "externalSecret", "node.example.test")
            .forEach { assertFalse(redacted.contains(it)) }
        val classified = ErrorClassifier.classify(
            "Authentication failed for password=profilePassword",
            activeProfile = profile
        )
        assertEquals(ErrorCategory.AUTH_OR_PROTOCOL, classified.category)
        assertFalse(classified.technicalDetail!!.contains("profilePassword"))

        val failed = ConfigurationHealthTracker.recordFailure(
            profile = profile,
            category = ErrorCategory.TIMEOUT,
            safeMessage = "password=profilePassword",
            networkFingerprint = "wifi",
            nowMs = 123L,
            measurementType = MeasurementType.PROXY_HTTP_204
        )
        assertNotNull(failed.lastErrorMessage)
        assertFalse(failed.lastErrorMessage!!.contains("profilePassword"))
        assertEquals(MeasurementType.PROXY_HTTP_204.name, failed.lastMeasurementType)

        val succeeded = ConfigurationHealthTracker.recordSuccess(
            profile = profile,
            latencyMs = 12,
            networkFingerprint = "wifi",
            nowMs = 124L,
            measurementType = MeasurementType.TUNNEL_CONNECT
        )
        assertEquals(MeasurementType.TUNNEL_CONNECT.name, succeeded.lastMeasurementType)

        val edited = ConfigurationHealthTracker.invalidateOnConfigEdit(
            profile.copy(canonicalFingerprint = "stale", lastMeasurementType = MeasurementType.TCP_CONNECT.name)
        )
        assertEquals(MeasurementType.NONE.name, edited.lastMeasurementType)
    }

    private fun assertClassified(message: String, category: ErrorCategory, userMessage: String) {
        val classified = ErrorClassifier.classify(message)
        assertEquals(category, classified.category)
        assertEquals(userMessage, classified.userMessage)
    }
}
