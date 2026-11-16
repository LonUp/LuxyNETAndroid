package com.example

import com.example.vpn.TrafficAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The core restarts its byte counters whenever it reloads while the tunnel stays connected.
 * Session totals must not follow that counter to zero — they may only be cleared by reset.
 */
class TrafficAccumulatorTest {

    @Test
    fun totalsKeepGrowingAcrossCoreCounterRestarts() {
        val acc = TrafficAccumulator()
        acc.reset(0L)

        assertEquals(100L, acc.sample(rawRx = 100, rawTx = 50, nowMs = 1_000).totalRx)
        assertEquals(50L, acc.sample(rawRx = 100, rawTx = 50, nowMs = 2_000).totalTx)

        val second = acc.sample(rawRx = 250, rawTx = 80, nowMs = 3_000)
        assertEquals(250L, second.totalRx)
        assertEquals(80L, second.totalTx)

        val afterRestart = acc.sample(rawRx = 40, rawTx = 10, nowMs = 4_000)
        assertEquals(290L, afterRestart.totalRx)
        assertEquals(90L, afterRestart.totalTx)

        val third = acc.sample(rawRx = 80, rawTx = 20, nowMs = 5_000)
        assertEquals(330L, third.totalRx)
        assertEquals(100L, third.totalTx)
    }

    @Test
    fun resetClearsTheSession() {
        val acc = TrafficAccumulator()
        acc.sample(rawRx = 5_000, rawTx = 1_000, nowMs = 1_000)
        acc.reset()

        val first = acc.sample(rawRx = 12, rawTx = 7, nowMs = 2_000)
        assertEquals(12L, first.totalRx)
        assertEquals(7L, first.totalTx)
    }

    @Test
    fun negativeOrMissingCountersAreClamped() {
        val acc = TrafficAccumulator()
        val first = acc.sample(rawRx = -5, rawTx = -1, nowMs = 1_000)
        assertEquals(0L, first.totalRx)
        assertEquals(0L, first.totalTx)
        assertEquals(0L, first.rxBytesPerSec)

        assertEquals(9L, acc.sample(rawRx = 9, rawTx = 4, nowMs = 2_000).totalRx)
    }
}
