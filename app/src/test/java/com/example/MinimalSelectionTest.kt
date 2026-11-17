package com.example

import com.example.core.FastestServerSelector
import com.example.data.local.ServerProfileEntity
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MinimalSelectionTest {
    private fun profile(id: String) = ServerProfileEntity(
        id = id, name = id, address = "server.example", protocol = "VLESS", security = "TLS",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555"
    )

    @Test fun singleEligibleServerConnectsWithoutAMeasurementDelay() = runTest {
        var measured = false
        val valid = profile("only")
        val unsupported = profile("unsupported").copy(protocol = "HYSTERIA2")
        val selected = FastestServerSelector { measured = true; 100 }.rank(listOf(unsupported, valid))
        assertEquals(listOf(valid), selected.map { it.profile })
        assertNull(selected.single().latencyMs)
        assertFalse(measured)
    }

    @Test fun ranksFreshSuccessfulMeasurementsForAutomaticConnectionFallback() = runTest {
        val profiles = listOf(profile("slow"), profile("failed"), profile("fast"), profile("tie"))
        val measured = mutableListOf<String>()
        val ranked = FastestServerSelector { node ->
            measured += node.id
            delay(10)
            when (node.id) { "slow" -> 90; "fast", "tie" -> 12; else -> null }
        }.rank(profiles)
        assertEquals(listOf("fast", "tie", "slow", "failed"), ranked.map { it.profile.id })
        assertEquals(listOf(12, 12, 90, null), ranked.map { it.latencyMs })
        assertEquals(profiles.map { it.id }.toSet(), measured.toSet())
    }

    @Test fun failedMeasurementsRemainHonestUnmeasuredConnectionFallbacks() = runTest {
        val ranked = FastestServerSelector { null }.rank(listOf(profile("a"), profile("b")))
        assertEquals(listOf("a", "b"), ranked.map { it.profile.id })
        assertTrue(ranked.all { it.latencyMs == null })
    }

    @Test fun measurementConcurrencyIsBoundedAndWorkStopsAfterCancellation() = runTest {
        var running = 0
        var maxRunning = 0
        var started = 0
        val full = CompletableDeferred<Unit>()
        val selector = FastestServerSelector(concurrency = 3) {
            running++
            started++
            maxRunning = maxOf(maxRunning, running)
            if (running == 3) full.complete(Unit)
            try { awaitCancellation() } finally { running-- }
        }
        val pending = async { selector.rank((1..20).map { profile(it.toString()) }) }
        runCurrent()
        full.await()
        assertEquals(3, maxRunning)
        pending.cancelAndJoin()
        runCurrent()
        assertEquals(0, running)
        assertEquals(3, started)
        assertTrue(pending.isCancelled)
    }

    @Test fun globalDeadlineRetainsResponsesAndCancelsOutstandingMeasurements() = runTest {
        var running = 0
        val selector = FastestServerSelector {
            running++
            try {
                if (it.id == "fast") { delay(20); 7 } else awaitCancellation()
            } finally { running-- }
        }
        val ranked = selector.rank(listOf(profile("fast")) + (1..1000).map { profile(it.toString()) })
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(0, running)
        assertEquals("fast", ranked.first().profile.id)
        assertEquals(7, ranked.first().latencyMs)
        assertEquals(1001, ranked.size)
        assertTrue(ranked.drop(1).all { it.latencyMs == null })
    }

    @Test fun productionTcpMeasurementUsesActualReachableEndpoints() = runBlocking {
        ServerSocket(0).use { first ->
            ServerSocket(0).use { second ->
                val nodes = listOf(profile("a").copy(address = "127.0.0.1", port = first.localPort),
                    profile("b").copy(address = "127.0.0.1", port = second.localPort))
                val ranked = FastestServerSelector().rank(nodes)
                assertEquals(nodes.map { it.id }.toSet(), ranked.map { it.profile.id }.toSet())
                assertTrue(ranked.all { it.latencyMs != null && it.latencyMs >= 0 })
            }
        }
    }
}
