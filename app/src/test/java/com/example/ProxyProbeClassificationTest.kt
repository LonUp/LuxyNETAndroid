package com.example

import com.example.core.connectivityFailureOutcome
import com.example.data.model.ErrorCategory
import com.example.xraycore.ConnectivityCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxyProbeClassificationTest {

    @Test
    fun `connectivity failure keeps the reported category`() {
        val categories = listOf(
            ErrorCategory.DNS_FAILURE,
            ErrorCategory.TLS_OR_HANDSHAKE,
            ErrorCategory.CONNECTION_REFUSED,
            ErrorCategory.AUTH_OR_PROTOCOL,
            ErrorCategory.TIMEOUT
        )

        categories.forEach { category ->
            val outcome = connectivityFailureOutcome(
                ConnectivityCheckResult(
                    reachable = false,
                    errorCategory = category,
                    safeMessage = "reported for $category"
                )
            )

            assertFalse("probe must fail for $category", outcome.success)
            assertEquals("category must be preserved for $category", category, outcome.errorCategory)
            assertEquals("message must be preserved for $category", "reported for $category", outcome.errorMessage)
        }
    }

    @Test
    fun `connectivity failure without a safe message still reports the category`() {
        val outcome = connectivityFailureOutcome(
            ConnectivityCheckResult(reachable = false, errorCategory = ErrorCategory.DNS_FAILURE)
        )

        assertFalse(outcome.success)
        assertEquals(ErrorCategory.DNS_FAILURE, outcome.errorCategory)
        assertEquals("Server did not pass connectivity check", outcome.errorMessage)
    }
}
