package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.connectivityFailureOutcome
import com.example.data.model.ErrorCategory
import com.example.xraycore.ConnectivityCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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

    @Test
    fun `measure dispatcher is a bounded daemon pool`() = kotlinx.coroutines.test.runTest {
        val dispatcher = com.example.core.XrayServerProbe(
            ApplicationProvider.getApplicationContext(),
            isVpnActive = { false }
        ).measureDispatcher

        // The pool must be bounded: with all three tasks parked before their first
        // progress point, only the pool's two workers can have started them.
        val started = java.util.concurrent.CompletableFuture<Unit>()
        val running = java.util.concurrent.CountDownLatch(2)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val work = (1..3).map { index ->
            async(dispatcher) {
                if (index <= 2) {
                    started.complete(Unit)
                    running.countDown()
                    gate.await()
                }
                index
            }
        }
        started.get(2, java.util.concurrent.TimeUnit.SECONDS)
        delay(300)
        assertEquals("third task must still be queued behind 2 workers", 0, running.count)
        gate.complete(Unit)
        assertEquals(listOf(1, 2, 3), work.map { it.await() })
    }
}
