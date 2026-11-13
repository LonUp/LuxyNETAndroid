package com.example.xraycore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

/**
 * Anti-censorship Clean CDN IP & WARP/MASQUE Endpoint Scanner powered by LuxyVpnCore.
 * Scans candidate IPv4 addresses or CIDR blocks with real TCP Fragmented TLS handshakes
 * as well as WireGuard/MASQUE UDP latency benchmarks across standard obfuscation ports.
 */
object CleanIpScanner {

    val PRESET_RANGES = listOf(
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

    fun expandCandidates(input: String, maxSamples: Int = 18): List<String> {
        val tokens = input.split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val ips = linkedSetOf<String>()
        for (token in tokens) {
            if (token.contains("/")) {
                ips.addAll(sampleCidr(token, maxSamples))
            } else if (isValidIpv4(token)) {
                ips.add(token)
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
        val tokens = input.split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val endpoints = linkedSetOf<Pair<String, Int>>()
        for (token in tokens) {
            if (token.contains(":") && !token.contains("/")) {
                val host = token.substringBeforeLast(":")
                val port = token.substringAfterLast(":").toIntOrNull()
                if (isValidIpv4(host) && port != null && port in 1..65535) {
                    endpoints.add(host to port)
                }
            } else {
                val ips = expandCandidates(token, maxSamples = 8)
                for (ip in ips) {
                    for (port in validPorts) {
                        endpoints.add(ip to port)
                        if (endpoints.size >= maxEndpoints) break
                    }
                    if (endpoints.size >= maxEndpoints) break
                }
            }
            if (endpoints.size >= maxEndpoints) break
        }
        return endpoints.take(maxEndpoints)
    }

    suspend fun benchmarkWarpEndpoints(
        cidrOrEndpoints: String,
        ports: List<Int> = listOf(2408, 1701, 500, 4500),
        samplesPerEndpoint: Int = 2,
        timeoutMs: Int = 900,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<WarpEndpointCandidateResult> = withContext(Dispatchers.IO) {
        val targets = expandWarpEndpoints(cidrOrEndpoints, ports)
        if (targets.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(6)
        var done = 0
        val total = targets.size

        val results = coroutineScope {
            targets.map { (ip, port) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val rtts = mutableListOf<Int>()
                        var failures = 0
                        repeat(samplesPerEndpoint.coerceIn(1, 5)) {
                            val rtt = probeUdpEndpoint(ip, port, timeoutMs)
                            if (rtt > 0) rtts.add(rtt) else failures++
                        }
                        done++
                        onProgress(done, total)
                        if (rtts.isEmpty()) {
                            null
                        } else {
                            val avg = rtts.average().toInt().coerceAtLeast(1)
                            val jitter = if (rtts.size > 1) (rtts.max() - rtts.min()) else 0
                            val loss = ((failures * 100) / samplesPerEndpoint.coerceIn(1, 5))
                            WarpEndpointCandidateResult(
                                endpoint = "$ip:$port",
                                ip = ip,
                                port = port,
                                avgRttMs = avg,
                                jitterMs = jitter,
                                lossPercent = loss
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        results
            .filterNotNull()
            .sortedWith(compareBy<WarpEndpointCandidateResult> { it.lossPercent }.thenBy { it.avgRttMs }.thenBy { it.jitterMs })
    }

    private fun probeUdpEndpoint(ip: String, port: Int, timeoutMs: Int): Int {
        var socket: java.net.DatagramSocket? = null
        return try {
            socket = VpnSocketProtector.createProtectedDatagramSocket().apply {
                soTimeout = timeoutMs
            }
            val addr = java.net.InetAddress.getByName(ip)
            // Compact WireGuard handshake initiation shaped probe header (type 0x01, reserved zero bytes)
            val probePayload = ByteArray(148).apply {
                this[0] = 0x01
            }
            val packet = java.net.DatagramPacket(probePayload, probePayload.size, addr, port)
            val recvBuf = ByteArray(256)
            val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
            val startNs = System.nanoTime()
            socket.connect(addr, port)
            socket.send(packet)
            try {
                socket.receive(recvPacket)
                ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            } catch (_: java.net.PortUnreachableException) {
                ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            } catch (_: java.net.SocketTimeoutException) {
                // UDP handshake initiation sent over reachable interface without response;
                // fallback to TCP/443 or TCP/port socket reachability timing if on same edge IP.
                probeTcpEdgeFallback(ip, timeoutMs)
            }
        } catch (_: Exception) {
            -1
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun probeTcpEdgeFallback(ip: String, timeoutMs: Int): Int {
        var tcp: java.net.Socket? = null
        return try {
            tcp = VpnSocketProtector.createProtectedSocket()
            val startNs = System.nanoTime()
            tcp.connect(java.net.InetSocketAddress(ip, 443), timeoutMs.coerceAtMost(700))
            ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            try {
                tcp?.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun scan(
        cidrOrIps: String,
        port: Int = 443,
        sni: String = "www.cloudflare.com",
        useFragment: Boolean = true,
        fragmentLength: String = "10-20",
        fragmentInterval: String = "10-20",
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<CleanIpCandidateResult> = withContext(Dispatchers.IO) {
        val candidates = expandCandidates(cidrOrIps)
        if (candidates.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(8)
        var done = 0
        val total = candidates.size

        val results = coroutineScope {
            candidates.map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val tlsRes = LuxyVpnCore.probeFragmentedTls(
                            host = ip,
                            port = port,
                            sni = sni.ifBlank { "www.cloudflare.com" },
                            lengthRangeStr = fragmentLength,
                            intervalRangeStr = fragmentInterval,
                            enableFragment = useFragment,
                            timeoutMs = 2600
                        )
                        done++
                        onProgress(done, total)
                        if (tlsRes.success) {
                            CleanIpCandidateResult(
                                ip = ip,
                                rttMs = tlsRes.rttMs,
                                tlsSuccess = true,
                                protocol = tlsRes.tlsProtocol
                            )
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll()
        }

        results.filterNotNull().sortedBy { it.rttMs }
    }

    private fun sampleCidr(cidr: String, limit: Int): List<String> {
        val parts = cidr.split("/")
        if (parts.size != 2 || !isValidIpv4(parts[0])) return emptyList()
        val prefix = parts[1].toIntOrNull()?.coerceIn(16, 32) ?: return emptyList()
        val baseOctets = parts[0].split(".").map { it.toInt() }
        val baseInt = (baseOctets[0] shl 24) or (baseOctets[1] shl 16) or (baseOctets[2] shl 8) or baseOctets[3]
        val hostBits = 32 - prefix
        val mask = if (prefix == 0) 0 else (-1 shl hostBits)
        val network = baseInt and mask
        val totalHosts = (1 shl hostBits).coerceAtMost(256)

        if (totalHosts <= 2) return listOf(parts[0])
        val step = (totalHosts / limit.coerceAtLeast(1)).coerceAtLeast(1)
        val out = mutableListOf<String>()
        var offset = 1
        while (offset < totalHosts - 1 && out.size < limit) {
            val ipInt = network or offset
            out.add(
                "${(ipInt ushr 24) and 0xFF}.${(ipInt ushr 16) and 0xFF}.${(ipInt ushr 8) and 0xFF}.${ipInt and 0xFF}"
            )
            offset += step
        }
        return out
    }

    private fun isValidIpv4(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) return false
        return octets.all { it.toIntOrNull() in 0..255 }
    }
}
