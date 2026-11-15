package com.example

import com.example.vpn.TrafficAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The core restarts its byte counters whenever it reloads (adaptive stage switch, routing
 * refresh, network handoff) while the tunnel stays connected. Session totals must not follow that
 * counter to zero — they may only be cleared by a connect/disconnect reset.
 */
class TrafficAccumulatorTest {

    @Test
    fun totalsKeepGrowingAcrossCoreCounterRestarts() {
        val acc = TrafficAccumulator()

        assertEquals(100L, acc.sample(rawRx = 100, rawTx = 50).totalRx)
        assertEquals(50L, acc.sample(rawRx = 100, rawTx = 50).totalTx)

        val second = acc.sample(rawRx = 250, rawTx = 80)
        assertEquals(250L, second.totalRx)
        assertEquals(150L, second.deltaRx)
        assertEquals(80L, second.totalTx)
        assertEquals(30L, second.deltaTx)

        // Core reloaded: raw counters restart at zero and begin again at 40/10.
        val afterRestart = acc.sample(rawRx = 40, rawTx = 10)
        assertEquals("total must not fall back to the raw counter", 290L, afterRestart.totalRx)
        assertEquals("the post-restart traffic is still new", 40L, afterRestart.deltaRx)
        assertEquals(90L, afterRestart.totalTx)

        val third = acc.sample(rawRx = 80, rawTx = 20)
        assertEquals(330L, third.totalRx)
        assertEquals(40L, third.deltaRx)
    }

    @Test
    fun resetClearsTheSession() {
        val acc = TrafficAccumulator()
        acc.sample(rawRx = 5_000, rawTx = 1_000)
        acc.reset()

        val first = acc.sample(rawRx = 12, rawTx = 7)
        assertEquals(12L, first.totalRx)
        assertEquals(12L, first.deltaRx)
        assertEquals(7L, first.totalTx)
    }

    @Test
    fun negativeOrMissingCountersAreClamped() {
        val acc = TrafficAccumulator()
        val first = acc.sample(rawRx = -5, rawTx = -1)
        assertEquals(0L, first.totalRx)
        assertEquals(0L, first.totalTx)
        assertEquals(0L, first.deltaRx)

        // A regression from 0 to a larger value still counts normally afterwards.
        assertEquals(9L, acc.sample(rawRx = 9, rawTx = 4).totalRx)
    }
}
