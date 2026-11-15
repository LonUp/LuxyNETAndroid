package com.example.vpn

/**
 * Session traffic totals that survive core restarts.
 *
 * The Xray core exposes monotonic counters, but they restart at zero whenever the core is
 * reloaded (adaptive-stage switch, routing refresh, network handoff) even though the tunnel stays
 * connected. Feeding those counters straight into the UI made "Total" jump back to zero without a
 * disconnect. This accumulator adds deltas and treats a *decrease* as a counter restart, so totals
 * grow for as long as the session lasts and are only cleared by [reset] (connect/disconnect).
 */
internal class TrafficAccumulator {

    data class Sample(
        val totalRx: Long,
        val totalTx: Long,
        val deltaRx: Long,
        val deltaTx: Long
    )

    private var lastRawRx = 0L
    private var lastRawTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    @Synchronized
    fun sample(rawRx: Long, rawTx: Long): Sample {
        val safeRx = rawRx.coerceAtLeast(0L)
        val safeTx = rawTx.coerceAtLeast(0L)
        // raw < last means the core restarted its counters: everything since the restart is new.
        val deltaRx = if (safeRx >= lastRawRx) safeRx - lastRawRx else safeRx
        val deltaTx = if (safeTx >= lastRawTx) safeTx - lastRawTx else safeTx
        lastRawRx = safeRx
        lastRawTx = safeTx
        totalRx += deltaRx
        totalTx += deltaTx
        return Sample(totalRx, totalTx, deltaRx, deltaTx)
    }

    @Synchronized
    fun reset() {
        lastRawRx = 0L
        lastRawTx = 0L
        totalRx = 0L
        totalTx = 0L
    }
}
