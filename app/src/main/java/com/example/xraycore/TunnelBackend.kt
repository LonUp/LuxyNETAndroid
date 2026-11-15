package com.example.xraycore

import android.content.Context

/**
 * The seam between the application state machine and whatever actually carries packets.
 * One production implementation ([XrayBackend]); tests inject the simulated SOCKS runtime
 * through [LuxyVpnCore]'s `simulated` constructor parameter instead.
 */
interface TunnelBackend {
    /** Human-readable core label, shown in the UI/logs. */
    val label: String

    /** True when this backend really tunnels traffic (i.e. a native core is attached). */
    val isRealCore: Boolean

    /** Starts the core. [tunFd] is the established `VpnService` descriptor, or <= 0 for SOCKS-only. */
    fun start(configJson: String, tunFd: Int): Result<Unit>

    fun stop()

    val isRunning: Boolean

    /** Cumulative (received, transmitted) bytes reported by the core. */
    fun trafficTotals(): Pair<Long, Long>

    /** Round-trip latency measured through the tunnel, or null when unavailable. */
    fun measureThroughTunnel(url: String): Long?
}

class XrayBackend(private val context: Context) : TunnelBackend {

    override val label: String get() = "Xray ${XrayEngine.version()}"

    override val isRealCore: Boolean = true

    override fun start(configJson: String, tunFd: Int): Result<Unit> =
        XrayEngine.start(context, configJson, tunFd)

    override fun stop() = XrayEngine.stop()

    override val isRunning: Boolean get() = XrayEngine.isRunning

    override fun trafficTotals(): Pair<Long, Long> {
        val traffic = XrayEngine.trafficTotals()
        // Contract is (received, transmitted); Xray reports outbound uplink/downlink.
        return traffic.downlinkBytes to traffic.uplinkBytes
    }

    override fun measureThroughTunnel(url: String): Long? = XrayEngine.measureThroughTunnel(url)
}
