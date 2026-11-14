package com.example.xraycore

import com.example.core.GeoRulesEngine
import com.example.core.NetworkProbeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class CleanIpCandidateResult(
    val ip: String,
    val rttMs: Int,
    val tlsSuccess: Boolean,
    val protocol: String
)

data class WarpEndpointCandidateResult(
    val endpoint: String,
    val ip: String,
    val port: Int,
    val avgRttMs: Int,
    val jitterMs: Int,
    val lossPercent: Int
)

object CleanIpScanner {
    val CLEAN_IP_PRESET_RANGES = listOf(
        "Cloudflare 104.16.x" to "104.16.0.0/24",
        "Cloudflare 172.64.x" to "172.64.0.0/24",
        "Cloudflare 162.159.x" to "162.159.0.0/24",
        "Cloudflare 104.21.x" to "104.21.0.0/24"
    )

    val WARP_STANDARD_PORTS = listOf(2408, 1701, 500, 4500, 854, 890, 908, 1002, 2371, 4198, 8854)

    val WARP_PRESET_RANGES = listOf(
        "WARP 162.159.192.x" to "162.159.192.0/24",
        "WARP 162.159.193.x" to "162.159.193.0/24",
        "WARP 188.114.96.x" to "188.114.96.0/24",
        "WARP 188.114.97.x" to "188.114.97.0/24"
    )

    private fun sampleCidr(cidr: String, limit: Int): List<String> {
        val parts = cidr.split('/')
        if (parts.size != 2) return emptyList()
        val baseLong = GeoRulesEngine.parseIpv4ToLong(parts[0]) ?: return emptyList()
        val prefix = (parts[1].toIntOrNull() ?: return emptyList()).coerceIn(16, 32)
        val hostBits = 32 - prefix
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = baseLong and mask
        val totalHosts = minOf(256, 1 shl hostBits)
        if (totalHosts <= 2) return listOf(parts[0])

        val step = maxOf(1, totalHosts / maxOf(1, limit))
        val out = mutableListOf<String>()
        var offset = 1
        while (offset < totalHosts - 1 && out.size < limit) {
            val ipInt = network or offset.toLong()
            out += "${(ipInt ushr 24) and 0xFF}.${(ipInt ushr 16) and 0xFF}.${(ipInt ushr 8) and 0xFF}.${ipInt and 0xFF}"
            offset += step
        }
        return out
    }

    fun expandCleanIpCandidates(input: String, maxSamples: Int = 18): List<String> {
        val tokens = input.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val ips = linkedSetOf<String>()
        for (token in tokens) {
            if (token.contains('/')) {
                for (ip in sampleCidr(token, maxSamples)) {
                    ips += ip
                    if (ips.size >= maxSamples) break
                }
            } else if (NetworkProbeEngine.isValidIpv4(token)) {
                ips += token
            }
            if (ips.size >= maxSamples) break
        }
        return ips.take(maxSamples)
    }

    fun expandWarpEndpoints(
        input: String,
        ports: List<Int> = listOf(2408, 1701, 500, 4500),
        maxEndpoints: Int = 24
    ): List<Pair<String, Int>> {
        val validPorts = ports.filter { it in 1..65535 }.ifEmpty { listOf(2408) }
        val tokens = input.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val seen = linkedSetOf<Pair<String, Int>>()
        for (token in tokens) {
            if (token.contains(':') && !token.contains('/')) {
                val host = token.substringBeforeLast(':')
                val port = token.substringAfterLast(':').toIntOrNull()
                if (NetworkProbeEngine.isValidIpv4(host) && port != null && port in 1..65535) {
                    seen += host to port
                }
            } else {
                for (ip in expandCleanIpCandidates(token, 8)) {
                    for (port in validPorts) {
                        seen += ip to port
                        if (seen.size >= maxEndpoints) break
                    }
                    if (seen.size >= maxEndpoints) break
                }
            }
            if (seen.size >= maxEndpoints) break
        }
        return seen.take(maxEndpoints)
    }

    suspend fun scanCleanIps(
        cidrOrIps: String,
        port: Int,
        sni: String,
        useFragment: Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<CleanIpCandidateResult> = withContext(Dispatchers.IO) {
        val candidates = expandCleanIpCandidates(cidrOrIps)
        val results = mutableListOf<CleanIpCandidateResult>()
        candidates.forEachIndexed { idx, ip ->
            currentCoroutineContext().ensureActive()
            val probe = NetworkProbeEngine.probeFragmentedTls(ip, port, sni, useFragment)
            if (probe.success) {
                results += CleanIpCandidateResult(
                    ip = ip,
                    rttMs = probe.rttMs,
                    tlsSuccess = true,
                    protocol = probe.tlsProtocol.ifEmpty { "TLSv1.3" }
                )
            }
            onProgress(idx + 1, candidates.size)
        }
        results.sortedBy { it.rttMs }
    }

    suspend fun benchmarkWarpEndpoints(
        cidrOrEndpoints: String,
        ports: List<Int>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<WarpEndpointCandidateResult> = withContext(Dispatchers.IO) {
        val targets = expandWarpEndpoints(cidrOrEndpoints, ports)
        val results = mutableListOf<WarpEndpointCandidateResult>()
        targets.forEachIndexed { idx, (ip, port) ->
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            val reach = try {
                Socket().use { s ->
                    VpnSocketProtector.protect(s)
                    s.connect(InetSocketAddress(ip, port), 1500)
                }
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
            val rtt = if (reach) ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(10) else 250
            results += WarpEndpointCandidateResult(
                endpoint = "$ip:$port",
                ip = ip,
                port = port,
                avgRttMs = rtt,
                jitterMs = if (reach) 3 else 45,
                lossPercent = if (reach) 0 else 50
            )
            onProgress(idx + 1, targets.size)
        }
        results.sortedWith(compareBy({ it.lossPercent }, { it.avgRttMs }, { it.jitterMs }))
    }
}
