package com.example.vpn

/**
 * Session traffic totals that survive core restarts.
 *
 * The Xray core exposes monotonic counters, but they restart at zero whenever the core is
 * reloaded (adaptive-stage switch, routing refresh, network handoff) even though the tunnel stays
 * connected. Feeding those counters straight into the UI made "Total" jump back to zero without a
 * disconnect. This accumulator adds deltas and treats a *decrease* as a counter restart, so totals
 * grow for as long as the session lasts and are only cleared by [reset] (connect/disconnect).
 *
 * Rates are `delta / elapsed`, using the caller's monotonic clock. A missing or tiny interval
 * yields 0 rather than a spike. Totals saturate at [Long.MAX_VALUE] instead of wrapping negative.
 */
internal class TrafficAccumulator {

    data class Sample(
        val totalRx: Long,
        val totalTx: Long,
        val rxBytesPerSec: Long,
        val txBytesPerSec: Long
    )

    private var lastRawRx = 0L
    private var lastRawTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L
    private var lastAtMs = 0L

    @Synchronized
    fun sample(rawRx: Long, rawTx: Long, nowMs: Long): Sample {
        val safeRx = rawRx.coerceAtLeast(0L)
        val safeTx = rawTx.coerceAtLeast(0L)
        val deltaRx = if (safeRx >= lastRawRx) safeRx - lastRawRx else safeRx
        val deltaTx = if (safeTx >= lastRawTx) safeTx - lastRawTx else safeTx
        val elapsed = if (lastAtMs <= 0L) 0L else (nowMs - lastAtMs).coerceAtLeast(0L)
        lastRawRx = safeRx
        lastRawTx = safeTx
        lastAtMs = nowMs.coerceAtLeast(0L)
        totalRx = saturatingAdd(totalRx, deltaRx)
        totalTx = saturatingAdd(totalTx, deltaTx)
        return Sample(
            totalRx = totalRx,
            totalTx = totalTx,
            rxBytesPerSec = rate(deltaRx, elapsed),
            txBytesPerSec = rate(deltaTx, elapsed)
        )
    }

    @Synchronized
    fun reset(nowMs: Long = 0L) {
        lastRawRx = 0L
        lastRawTx = 0L
        totalRx = 0L
        totalTx = 0L
        lastAtMs = nowMs.coerceAtLeast(0L)
    }

    companion object {
        fun saturatingAdd(a: Long, b: Long): Long {
            if (b <= 0L) return a.coerceAtLeast(0L)
            if (a >= Long.MAX_VALUE - b) return Long.MAX_VALUE
            return a + b
        }

        /** Bytes per second. Intervals under 200ms are ignored so a late reset cannot spike. */
        fun rate(delta: Long, elapsedMs: Long): Long {
            if (delta <= 0L || elapsedMs < 200L) return 0L
            if (delta > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE / elapsedMs
            return (delta * 1000L) / elapsedMs
        }
    }
}
