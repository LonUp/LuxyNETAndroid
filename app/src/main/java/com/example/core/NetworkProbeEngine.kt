package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.SecurityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.roundToInt

data class ProbeResult(
    val rttMs: Int, // -1 on failure/timeout
    val jitterMs: Int = 0,
    val success: Boolean = rttMs >= 0,
    val error: String? = null
)

class NetworkProbeEngine {

    suspend fun measureNodePing(
        profile: ServerProfileEntity,
        samples: Int = 2,
        timeoutMs: Int = 2800
    ): ProbeResult = withContext(Dispatchers.IO) {
        val sampleCount = samples.coerceIn(1, 5)
        val rtts = mutableListOf<Int>()
        var lastError: String? = null

        repeat(sampleCount) {
            val res = if (profile.security == SecurityType.TLS.name && !profile.allowInsecure) {
                measureTcpOrTls(profile.address, profile.port, useTls = false, timeoutMs = timeoutMs)
            } else {
                measureTcpOrTls(profile.address, profile.port, useTls = false, timeoutMs = timeoutMs)
            }
            if (res.first >= 0) {
                rtts.add(res.first)
            } else {
                lastError = res.second
            }
        }

        if (rtts.isEmpty()) {
            ProbeResult(rttMs = -1, jitterMs = 0, success = false, error = lastError ?: "Connection timed out")
        } else {
            val avg = rtts.average().roundToInt()
            val jitter = if (rtts.size > 1) {
                rtts.map { abs(it - avg) }.average().roundToInt()
            } else 0
            ProbeResult(rttMs = avg, jitterMs = jitter, success = true)
        }
    }

    suspend fun measureSingleTcpRtt(host: String, port: Int, timeoutMs: Int = 2500): ProbeResult =
        withContext(Dispatchers.IO) {
            val (rtt, err) = measureTcpOrTls(host, port, useTls = false, timeoutMs = timeoutMs)
            ProbeResult(rttMs = rtt, jitterMs = 0, success = rtt >= 0, error = err)
        }

    suspend fun testDnsLatency(dnsServer: String, timeoutMs: Int = 2500): ProbeResult =
        withContext(Dispatchers.IO) {
            val cleanHost = dnsServer.substringBefore(":").trim().ifBlank { "1.1.1.1" }
            val udpRtt = probeUdpDnsWire(cleanHost, 53, timeoutMs)
            if (udpRtt >= 0) {
                ProbeResult(rttMs = udpRtt, success = true)
            } else {
                val (rtt, err) = measureTcpOrTls(cleanHost, 53, useTls = false, timeoutMs = timeoutMs)
                if (rtt >= 0) {
                    ProbeResult(rttMs = rtt, success = true)
                } else {
                    val (dohRtt, dohErr) = measureTcpOrTls(cleanHost, 443, useTls = true, timeoutMs = timeoutMs)
                    ProbeResult(rttMs = dohRtt, success = dohRtt >= 0, error = dohErr ?: err)
                }
            }
        }

    private fun probeUdpDnsWire(host: String, port: Int, timeoutMs: Int): Int {
        var sock: java.net.DatagramSocket? = null
        return try {
            sock = com.example.xraycore.VpnSocketProtector.createProtectedDatagramSocket().apply {
                soTimeout = timeoutMs
            }
            val query = com.example.xraycore.MultiResolverDnsEngine.buildDnsQueryPacket("www.cloudflare.com", 0x4D53)
            val addr = java.net.InetAddress.getByName(host)
            val startNs = System.nanoTime()
            sock.send(java.net.DatagramPacket(query, query.size, addr, port))
            val buf = ByteArray(512)
            sock.receive(java.net.DatagramPacket(buf, buf.size))
            ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    suspend fun batchMeasure(
        profiles: List<ServerProfileEntity>,
        maxConcurrency: Int = 8,
        onEachComplete: suspend (ServerProfileEntity, ProbeResult) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, 16))
        profiles.map { profile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = measureNodePing(profile, samples = 2)
                    onEachComplete(profile, result)
                }
            }
        }.awaitAll()
    }

    private fun measureTcpOrTls(
        host: String,
        port: Int,
        useTls: Boolean,
        timeoutMs: Int
    ): Pair<Int, String?> {
        if (host.isBlank() || port !in 1..65535) return -1 to "Invalid host or port"
        var socket: Socket? = null
        return try {
            val startNs = System.nanoTime()
            socket = com.example.xraycore.VpnSocketProtector.createProtectedSocket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            if (useTls) {
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()
                sslSocket.close()
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            elapsedMs to null
        } catch (e: Exception) {
            -1 to (e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}
