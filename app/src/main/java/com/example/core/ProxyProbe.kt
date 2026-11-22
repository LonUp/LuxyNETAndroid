package com.example.core

import android.content.Context
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ErrorCategory
import com.example.xraycore.ConnectivityCheckResult
import com.example.xraycore.TunnelConnectivityCheck
import com.example.xraycore.XrayConfigCompiler
import com.example.xraycore.XrayEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ProbeOutcome(
    val success: Boolean,
    val latencyMs: Int? = null,
    val errorCategory: ErrorCategory = ErrorCategory.NONE,
    val errorMessage: String? = null,
    val exitIp: String? = null,
    val verifiedCountryCode: String? = null
)

/**
 * Maps a failed connectivity check to a probe failure, preserving the category the check actually
 * reported so a DNS, TLS, or refused-connection failure is never recorded as a timeout.
 */
internal fun connectivityFailureOutcome(result: ConnectivityCheckResult): ProbeOutcome =
    ProbeOutcome(
        success = false,
        errorCategory = result.errorCategory,
        errorMessage = result.safeMessage ?: "Server did not pass connectivity check"
    )

interface ServerProbe {
    suspend fun probe(profile: ServerProfileEntity, verifyExitCountry: Boolean = true): ProbeOutcome
}

/**
 * Protocol-aware proxy probe for manual retesting.
 *
 * - When the VPN tunnel is disconnected, spins up an isolated ephemeral [CoreController] with a
 *   loopback SOCKS inbound (`tunFd = 0`) to verify real HTTP 204 connectivity and Stage 3 exit IP/country.
 * - When the VPN tunnel is active, uses `Libv2ray.measureOutboundDelay` (which creates an isolated
 *   inbound-less Xray instance without touching `xray.tun.fd` or the active VPN core) so the live
 *   VPN connection is never interrupted.
 */
class XrayServerProbe(
    private val context: Context,
    private val isVpnActive: () -> Boolean,
    private val geoIpLookup: (String) -> String? = { ip ->
        GeoIpDatabase.getInstance(context)?.lookupCountry(ip)
    }
) : ServerProbe {

    override suspend fun probe(profile: ServerProfileEntity, verifyExitCountry: Boolean): ProbeOutcome {
        if (!ClientPolicy.isSupported(profile)) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.INVALID_CONFIG,
                errorMessage = "Unsupported or invalid configuration"
            )
        }
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (isVpnActive()) {
                probeViaMeasureOutboundDelay(profile)
            } else {
                probeViaEphemeralSocksCore(profile, verifyExitCountry)
            }
        }
    }

    /**
     * Measures connectivity and latency only. It cannot verify an exit IP or country, so callers
     * must preserve any existing verified-country metadata when this result is recorded.
     */
    private suspend fun probeViaMeasureOutboundDelay(profile: ServerProfileEntity): ProbeOutcome {
        val init = XrayEngine.initialize(context)
        if (init.isFailure) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.UNKNOWN,
                errorMessage = "Core initialization failed"
            )
        }
        val configJson = try {
            XrayConfigCompiler.compileProbeConfig(profile, socksPort = 0)
        } catch (e: Exception) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.INVALID_CONFIG,
                errorMessage = e.message ?: "Invalid configuration"
            )
        }
        val delayMs = withTimeoutOrNull(6_000L) {
            runCatching {
                Libv2ray.measureOutboundDelay(configJson, "https://www.gstatic.com/generate_204")
            }
        }
        if (delayMs == null) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.TIMEOUT,
                errorMessage = "Connection test timed out"
            )
        }
        return delayMs.fold(
            onSuccess = { rtt ->
                if (rtt >= 0L) {
                    ProbeOutcome(
                        success = true,
                        latencyMs = rtt.toInt(),
                        exitIp = null,
                        verifiedCountryCode = null
                    )
                } else {
                    ProbeOutcome(
                        success = false,
                        errorCategory = ErrorCategory.UNKNOWN,
                        errorMessage = "Connection test failed"
                    )
                }
            },
            onFailure = { err ->
                ProbeOutcome(
                    success = false,
                    errorCategory = ConfigurationHealthTracker.classifyError(err.message, err),
                    errorMessage = err.message ?: "Connection test failed"
                )
            }
        )
    }

    private suspend fun probeViaEphemeralSocksCore(
        profile: ServerProfileEntity,
        verifyExitCountry: Boolean
    ): ProbeOutcome {
        val init = XrayEngine.initialize(context)
        if (init.isFailure) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.UNKNOWN,
                errorMessage = "Core initialization failed"
            )
        }
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull()
            ?: return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.UNKNOWN,
                errorMessage = "Could not allocate local probe port"
            )
        val configJson = try {
            XrayConfigCompiler.compileProbeConfig(profile, socksPort = port)
        } catch (e: Exception) {
            return ProbeOutcome(
                success = false,
                errorCategory = ErrorCategory.INVALID_CONFIG,
                errorMessage = e.message ?: "Invalid configuration"
            )
        }

        var ctrl: CoreController? = null
        try {
            val callback = object : CoreCallbackHandler {
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
                override fun shutdown(): Long = 0L
                override fun startup(): Long = 0L
            }
            ctrl = Libv2ray.newCoreController(callback)
            val started = startEphemeralCore(ctrl, configJson)
            if (!started) {
                return ProbeOutcome(
                    success = false,
                    errorCategory = ErrorCategory.UNKNOWN,
                    errorMessage = "Could not start probe core"
                )
            }
            currentCoroutineContext().ensureActive()
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val check = TunnelConnectivityCheck(
                proxy = proxy,
                overallTimeoutMs = 4_500L,
                callTimeoutMs = 3_500L
            )
            val startNs = System.nanoTime()
            val connectivity = check.check()
            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(0)
            if (!connectivity.reachable) {
                return connectivityFailureOutcome(connectivity)
            }
            val exitResult = if (verifyExitCountry) {
                ExitCountryVerifier(
                    proxy = proxy,
                    overallTimeoutMs = 3_500L,
                    callTimeoutMs = 3_000L,
                    geoIpLookup = geoIpLookup
                ).verifyExit()
            } else null

            return ProbeOutcome(
                success = true,
                latencyMs = rttMs,
                exitIp = exitResult?.exitIp,
                verifiedCountryCode = exitResult?.countryCode
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return ProbeOutcome(
                success = false,
                errorCategory = ConfigurationHealthTracker.classifyError(e.message, e),
                errorMessage = e.message ?: "Connection test failed"
            )
        } finally {
            runCatching { ctrl?.stopLoop() }
        }
    }

    private fun startEphemeralCore(ctrl: CoreController, configJson: String): Boolean {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread({
            try {
                ctrl.startLoop(configJson, 0)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }, "luxy-probe-core").apply { isDaemon = true }
        thread.start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ctrl.isRunning) {
            if (failure.get() != null) return false
            if (latch.count == 0L && !ctrl.isRunning) return false
            if (System.nanoTime() > deadline) return false
            Thread.sleep(25)
        }
        return ctrl.isRunning
    }
}
