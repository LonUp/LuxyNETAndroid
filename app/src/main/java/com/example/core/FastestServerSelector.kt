package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.xraycore.VpnSocketProtector
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot TCP response comparison, not an authentication or throughput claim. */
class FastestServerSelector(
    private val concurrency: Int = 4,
    private val measure: suspend (ServerProfileEntity) -> Int? = TcpServerMeasurement::measure
) {
    init { require(concurrency > 0) }

    data class Selection(val profile: ServerProfileEntity, val latencyMs: Int?)

    suspend fun rank(profiles: List<ServerProfileEntity>): List<Selection> = coroutineScope {
        val candidates = profiles.filter(ClientPolicy::isSupported)
        if (candidates.isEmpty()) return@coroutineScope emptyList()
        if (candidates.size == 1) return@coroutineScope listOf(Selection(candidates.single(), null))
        val next = AtomicInteger()
        val measured = arrayOfNulls<Selection>(candidates.size)
        withTimeoutOrNull(4_000) {
            coroutineScope {
                repeat(minOf(concurrency, candidates.size)) {
                    launch {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= candidates.size) break
                            val latency = try {
                                measure(candidates[index])?.takeIf { it >= 0 }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (latency != null) measured[index] = Selection(candidates[index], latency)
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val ordered = measured.filterNotNull().sortedBy { it.latencyMs }.toMutableList()
        for (index in candidates.indices) {
            // Unmeasured endpoints are connection fallbacks, never claimed to be fastest.
            if (measured[index] == null) ordered += Selection(candidates[index], null)
        }
        ordered
    }
}

private object TcpServerMeasurement {
    // DNS may block in the platform resolver. Cancellation closes the socket immediately and
    // detaches that blocking call; it must never delay the selection deadline or connect later.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun measure(profile: ServerProfileEntity): Int? = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        val worker = ioScope.launch {
            val latency = try {
                socket.use {
                    if (!VpnSocketProtector.protect(it)) return@use null
                    val start = System.nanoTime()
                    val endpoint = InetSocketAddress(profile.address, profile.port)
                    currentCoroutineContext().ensureActive()
                    it.connect(endpoint, 2_500)
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(0)
                }
            } catch (_: Exception) {
                null
            }
            if (continuation.isActive) continuation.resume(latency)
        }
        continuation.invokeOnCancellation {
            runCatching { socket.close() }
            worker.cancel()
        }
    }
}
