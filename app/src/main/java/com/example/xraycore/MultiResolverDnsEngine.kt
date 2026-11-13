package com.example.xraycore

import com.example.data.model.DnsPoolStrategy
import com.example.data.model.StabilitySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

enum class DnsTransportProtocol(val label: String, val shortTag: String) {
    DOH("DoH (RFC 8484)", "DoH"),
    DOT("DoT (RFC 7858)", "DoT"),
    TCP("TCP DNS", "TCP"),
    UDP("UDP DNS", "UDP")
}

data class DnsResolverEndpoint(
    val id: String,
    val label: String,
    val transport: DnsTransportProtocol,
    val host: String,
    val port: Int,
    val url: String = ""
)

data class DnsWireAnswer(
    val txId: Int,
    val rcode: Int,
    val ips: List<String>,
    val isTruncated: Boolean = false,
    val isResponse: Boolean = true
)

data class DnsIntegrityVerdict(
    val isValid: Boolean,
    val reason: String? = null
)

data class ResolverProbeEntry(
    val resolverId: String,
    val label: String,
    val transport: DnsTransportProtocol,
    val success: Boolean,
    val rttMs: Int,
    val ips: List<String> = emptyList(),
    val rcode: Int = 0,
    val rejectedReason: String? = null
)

data class DnsPoolQueryResult(
    val success: Boolean,
    val winnerLabel: String = "",
    val transport: DnsTransportProtocol? = null,
    val rttMs: Int = -1,
    val resolvedIps: List<String> = emptyList(),
    val entries: List<ResolverProbeEntry> = emptyList(),
    val rejectedForPoisoning: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Multi-Resolver DNS Pool (DoH, DoT, TCP, UDP) with wire-format DNS parser,
 * anti-poisoning integrity validation (TXID, bogon/private IP trap detection,
 * forged NXDOMAIN filtering), and Parallel / Sequential / Sticky resolution strategies.
 */
object MultiResolverDnsEngine {

    private const val RCODE_NOERROR = 0
    private const val RCODE_NXDOMAIN = 3

    @Volatile
    private var stickyResolverId: String? = null

    private val protectedDohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(VpnSocketProtector.protectedOkHttpSocketFactory())
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun buildResolverPool(settings: StabilitySettings): List<DnsResolverEndpoint> {
        val pool = mutableListOf<DnsResolverEndpoint>()
        if (settings.dohEnabled) {
            val dohUrl = settings.dohServerUrl.trim().ifBlank { "https://cloudflare-dns.com/dns-query" }
            val dohHost = dohUrl.substringAfter("://").substringBefore("/").substringBefore(":")
            pool.add(
                DnsResolverEndpoint(
                    id = "doh_primary",
                    label = "DoH • $dohHost",
                    transport = DnsTransportProtocol.DOH,
                    host = dohHost,
                    port = 443,
                    url = dohUrl
                )
            )
        }
        if (settings.dnsDotEnabled) {
            val dotRaw = settings.dnsDotServer.trim().removePrefix("tls://").ifBlank { "1.1.1.1:853" }
            val dotHost = dotRaw.substringBefore(":")
            val dotPort = dotRaw.substringAfter(":", "853").toIntOrNull()?.takeIf { it in 1..65535 } ?: 853
            pool.add(
                DnsResolverEndpoint(
                    id = "dot_primary",
                    label = "DoT • $dotHost:$dotPort",
                    transport = DnsTransportProtocol.DOT,
                    host = dotHost,
                    port = dotPort
                )
            )
        }
        if (settings.dnsTcpEnabled) {
            val tcpHost = settings.primaryDns.trim().ifBlank { "1.1.1.1" }
            pool.add(
                DnsResolverEndpoint(
                    id = "tcp_primary",
                    label = "TCP • $tcpHost:53",
                    transport = DnsTransportProtocol.TCP,
                    host = tcpHost,
                    port = 53
                )
            )
        }
        if (settings.dnsUdpEnabled) {
            val udpHost = settings.primaryDns.trim().ifBlank { "1.1.1.1" }
            pool.add(
                DnsResolverEndpoint(
                    id = "udp_primary",
                    label = "UDP • $udpHost:53",
                    transport = DnsTransportProtocol.UDP,
                    host = udpHost,
                    port = 53
                )
            )
            val fallbackHost = settings.fallbackDns.trim()
            if (fallbackHost.isNotBlank() && fallbackHost != udpHost) {
                pool.add(
                    DnsResolverEndpoint(
                        id = "udp_fallback",
                        label = "UDP • $fallbackHost:53",
                        transport = DnsTransportProtocol.UDP,
                        host = fallbackHost,
                        port = 53
                    )
                )
            }
        }
        if (pool.isEmpty()) {
            pool.add(
                DnsResolverEndpoint(
                    id = "udp_default",
                    label = "UDP • 1.1.1.1:53",
                    transport = DnsTransportProtocol.UDP,
                    host = "1.1.1.1",
                    port = 53
                )
            )
        }
        return pool
    }

    /**
     * Validates a single resolver answer for TXID mismatch, malformed header, or bogon/private IP poisoning.
     */
    fun validateAnswer(
        domain: String,
        expectedTxId: Int,
        answer: DnsWireAnswer?,
        enforceIntegrity: Boolean = true
    ): DnsIntegrityVerdict {
        if (answer == null) {
            return DnsIntegrityVerdict(false, "Malformed or truncated DNS response")
        }
        if (!answer.isResponse) {
            return DnsIntegrityVerdict(false, "DNS packet QR flag indicates query, not response")
        }
        if (expectedTxId >= 0 && answer.txId != (expectedTxId and 0xFFFF)) {
            return DnsIntegrityVerdict(
                false,
                "TXID mismatch (expected=${expectedTxId and 0xFFFF}, actual=${answer.txId})"
            )
        }
        if (!enforceIntegrity) {
            return DnsIntegrityVerdict(answer.rcode == RCODE_NOERROR && answer.ips.isNotEmpty(), null)
        }
        if (answer.rcode == RCODE_NXDOMAIN) {
            return DnsIntegrityVerdict(false, "NXDOMAIN returned (subject to cross-resolver verification)")
        }
        if (answer.rcode != RCODE_NOERROR) {
            return DnsIntegrityVerdict(false, "DNS error RCODE=${answer.rcode}")
        }
        if (answer.ips.isEmpty()) {
            return DnsIntegrityVerdict(false, "Empty A/AAAA answer section")
        }
        if (!isInternalDomain(domain)) {
            val poisoned = answer.ips.firstOrNull { isBogonOrPrivateIp(it) }
            if (poisoned != null) {
                return DnsIntegrityVerdict(false, "Forged bogon/private IP answer detected: $poisoned")
            }
        }
        return DnsIntegrityVerdict(true, null)
    }

    /**
     * Cross-checks multiple resolver entries to flag forged NXDOMAIN responses from hijacked UDP/plain
     * resolvers whenever any healthy resolver in the pool returned valid public A/AAAA records.
     */
    fun reconcilePoolEntries(
        domain: String,
        entries: List<ResolverProbeEntry>,
        enforceIntegrity: Boolean = true
    ): Pair<ResolverProbeEntry?, List<String>> {
        val validEntries = entries.filter { it.success && it.ips.isNotEmpty() && it.rejectedReason == null }
        val poisoningNotes = entries.mapNotNull { entry ->
            entry.rejectedReason?.let { "${entry.label}: $it" }
        }.toMutableList()

        if (enforceIntegrity && validEntries.isNotEmpty()) {
            entries.filter { it.rcode == RCODE_NXDOMAIN || (!it.success && it.rejectedReason?.contains("NXDOMAIN") == true) }
                .forEach { forgedNx ->
                    val note = "${forgedNx.label}: Forged NXDOMAIN discarded (healthy resolver returned ${validEntries.first().ips.first()})"
                    if (note !in poisoningNotes) poisoningNotes.add(note)
                }
        }

        val best = validEntries.minByOrNull { it.rttMs }
        return best to poisoningNotes
    }

    suspend fun queryPool(
        domain: String = "www.cloudflare.com",
        settings: StabilitySettings,
        timeoutMs: Int = 2500,
        customExecutor: (suspend (DnsResolverEndpoint, String, Int) -> ResolverProbeEntry)? = null
    ): DnsPoolQueryResult = withContext(Dispatchers.IO) {
        val pool = buildResolverPool(settings)
        val executor = customExecutor ?: { ep, host, txId -> executeRealQuery(ep, host, txId, settings.dnsIntegrityFilterEnabled, timeoutMs) }
        val txId = Random.nextInt(1, 65535)

        when (settings.dnsPoolStrategy) {
            DnsPoolStrategy.PARALLEL_FASTEST -> {
                val entries = coroutineScope {
                    pool.map { ep ->
                        async(Dispatchers.IO) { executor(ep, domain, txId) }
                    }.awaitAll()
                }
                val (winner, poisoning) = reconcilePoolEntries(domain, entries, settings.dnsIntegrityFilterEnabled)
                if (winner != null) {
                    stickyResolverId = winner.resolverId
                    DnsPoolQueryResult(
                        success = true,
                        winnerLabel = winner.label,
                        transport = winner.transport,
                        rttMs = winner.rttMs,
                        resolvedIps = winner.ips,
                        entries = entries,
                        rejectedForPoisoning = poisoning
                    )
                } else {
                    DnsPoolQueryResult(
                        success = false,
                        entries = entries,
                        rejectedForPoisoning = poisoning,
                        error = poisoning.firstOrNull() ?: "All ${pool.size} resolvers failed or returned invalid answers"
                    )
                }
            }
            DnsPoolStrategy.SEQUENTIAL_FALLBACK -> {
                val ordered = pool
                val entries = mutableListOf<ResolverProbeEntry>()
                for (ep in ordered) {
                    val res = executor(ep, domain, txId)
                    entries.add(res)
                    if (res.success && res.ips.isNotEmpty() && res.rejectedReason == null) {
                        stickyResolverId = res.resolverId
                        return@withContext DnsPoolQueryResult(
                            success = true,
                            winnerLabel = res.label,
                            transport = res.transport,
                            rttMs = res.rttMs,
                            resolvedIps = res.ips,
                            entries = entries,
                            rejectedForPoisoning = entries.mapNotNull { e -> e.rejectedReason?.let { "${e.label}: $it" } }
                        )
                    }
                }
                val (_, poisoning) = reconcilePoolEntries(domain, entries, settings.dnsIntegrityFilterEnabled)
                DnsPoolQueryResult(
                    success = false,
                    entries = entries,
                    rejectedForPoisoning = poisoning,
                    error = poisoning.firstOrNull() ?: "Sequential DNS fallback exhausted"
                )
            }
            DnsPoolStrategy.STICKY -> {
                val currentSticky = stickyResolverId
                val ordered = if (currentSticky != null) {
                    val primary = pool.filter { it.id == currentSticky }
                    val rest = pool.filter { it.id != currentSticky }
                    primary + rest
                } else pool

                val entries = mutableListOf<ResolverProbeEntry>()
                for (ep in ordered) {
                    val res = executor(ep, domain, txId)
                    entries.add(res)
                    if (res.success && res.ips.isNotEmpty() && res.rejectedReason == null) {
                        stickyResolverId = res.resolverId
                        return@withContext DnsPoolQueryResult(
                            success = true,
                            winnerLabel = res.label,
                            transport = res.transport,
                            rttMs = res.rttMs,
                            resolvedIps = res.ips,
                            entries = entries,
                            rejectedForPoisoning = entries.mapNotNull { e -> e.rejectedReason?.let { "${e.label}: $it" } }
                        )
                    }
                }
                val (_, poisoning) = reconcilePoolEntries(domain, entries, settings.dnsIntegrityFilterEnabled)
                DnsPoolQueryResult(
                    success = false,
                    entries = entries,
                    rejectedForPoisoning = poisoning,
                    error = poisoning.firstOrNull() ?: "Sticky resolver pool exhausted"
                )
            }
        }
    }

    private fun executeRealQuery(
        endpoint: DnsResolverEndpoint,
        domain: String,
        txId: Int,
        enforceIntegrity: Boolean,
        timeoutMs: Int
    ): ResolverProbeEntry {
        val queryBytes = buildDnsQueryPacket(domain, txId, qType = 1)
        val startNs = System.nanoTime()
        return try {
            val responseBytes = when (endpoint.transport) {
                DnsTransportProtocol.DOH -> queryOverDohWire(endpoint.url, queryBytes)
                DnsTransportProtocol.DOT -> queryOverDotWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
                DnsTransportProtocol.TCP -> queryOverTcpWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
                DnsTransportProtocol.UDP -> queryOverUdpWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
            }
            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            val answer = parseDnsResponsePacket(responseBytes)
            val expectedTx = if (endpoint.transport == DnsTransportProtocol.DOH) -1 else txId
            val verdict = validateAnswer(domain, expectedTx, answer, enforceIntegrity)
            ResolverProbeEntry(
                resolverId = endpoint.id,
                label = endpoint.label,
                transport = endpoint.transport,
                success = verdict.isValid,
                rttMs = rttMs,
                ips = if (verdict.isValid) answer?.ips.orEmpty() else emptyList(),
                rcode = answer?.rcode ?: -1,
                rejectedReason = if (verdict.isValid) null else verdict.reason
            )
        } catch (e: Exception) {
            ResolverProbeEntry(
                resolverId = endpoint.id,
                label = endpoint.label,
                transport = endpoint.transport,
                success = false,
                rttMs = -1,
                rcode = -1,
                rejectedReason = e.localizedMessage ?: e.javaClass.simpleName
            )
        }
    }

    private fun queryOverDohWire(url: String, queryBytes: ByteArray): ByteArray {
        val mediaType = "application/dns-message".toMediaType()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .post(queryBytes.toRequestBody(mediaType))
            .build()
        protectedDohClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("DoH HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IllegalStateException("Empty DoH body")
        }
    }

    private fun queryOverDotWire(host: String, port: Int, queryBytes: ByteArray, timeoutMs: Int): ByteArray {
        val tcp = VpnSocketProtector.createProtectedSocket()
        var ssl: SSLSocket? = null
        return try {
            tcp.connect(InetSocketAddress(host, port), timeoutMs)
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(tcp, host, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            if (!host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                val params: SSLParameters = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
            }
            ssl.startHandshake()
            val out = DataOutputStream(ssl.outputStream)
            out.writeShort(queryBytes.size)
            out.write(queryBytes)
            out.flush()

            val inp = DataInputStream(ssl.inputStream)
            val len = inp.readUnsignedShort()
            ByteArray(len).also { inp.readFully(it) }
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
            try { tcp.close() } catch (_: Exception) {}
        }
    }

    private fun queryOverTcpWire(host: String, port: Int, queryBytes: ByteArray, timeoutMs: Int): ByteArray {
        val sock = VpnSocketProtector.createProtectedSocket()
        return try {
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            val out = DataOutputStream(sock.getOutputStream())
            out.writeShort(queryBytes.size)
            out.write(queryBytes)
            out.flush()

            val inp = DataInputStream(sock.getInputStream())
            val len = inp.readUnsignedShort()
            ByteArray(len).also { inp.readFully(it) }
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun queryOverUdpWire(host: String, port: Int, queryBytes: ByteArray, timeoutMs: Int): ByteArray {
        val sock = VpnSocketProtector.createProtectedDatagramSocket()
        return try {
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            val req = DatagramPacket(queryBytes, queryBytes.size, addr, port)
            sock.send(req)
            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            buf.copyOf(resp.length)
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun extractQueryDomain(queryBytes: ByteArray): String {
        if (queryBytes.size <= 12) return ""
        return try {
            val labels = mutableListOf<String>()
            var pos = 12
            while (pos < queryBytes.size) {
                val len = queryBytes[pos].toInt() and 0xFF
                if (len == 0 || (len and 0xC0) != 0) break
                pos++
                if (pos + len > queryBytes.size) break
                labels.add(String(queryBytes, pos, len, Charsets.UTF_8))
                pos += len
            }
            labels.joinToString(".")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Forwards a raw DNS wire query captured from the TUN interface through the
     * VpnSocketProtector-protected MultiResolver pool and validates integrity before returning
     * the wire response bytes to inject back into the TUN file descriptor.
     */
    fun forwardRawDnsQuery(
        queryBytes: ByteArray,
        settings: StabilitySettings,
        timeoutMs: Int = 3500
    ): ByteArray? {
        if (queryBytes.size < 12) return null
        val txId = ((queryBytes[0].toInt() and 0xFF) shl 8) or (queryBytes[1].toInt() and 0xFF)
        val domain = extractQueryDomain(queryBytes).ifBlank { "query.local" }
        val pool = buildResolverPool(settings)
        val ordered = stickyResolverId?.let { pinned ->
            val match = pool.firstOrNull { it.id == pinned }
            if (match != null) listOf(match) + pool.filter { it.id != pinned } else pool
        } ?: pool

        for (endpoint in ordered) {
            try {
                val respBytes = when (endpoint.transport) {
                    DnsTransportProtocol.DOH -> queryOverDohWire(endpoint.url, queryBytes)
                    DnsTransportProtocol.DOT -> queryOverDotWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
                    DnsTransportProtocol.TCP -> queryOverTcpWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
                    DnsTransportProtocol.UDP -> queryOverUdpWire(endpoint.host, endpoint.port, queryBytes, timeoutMs)
                }
                val parsed = parseDnsResponsePacket(respBytes)
                val expectedTx = if (endpoint.transport == DnsTransportProtocol.DOH) -1 else txId
                val verdict = validateAnswer(domain, expectedTx, parsed, settings.dnsIntegrityFilterEnabled)
                if (verdict.isValid) {
                    stickyResolverId = endpoint.id
                    if (respBytes.size >= 2) {
                        respBytes[0] = ((txId ushr 8) and 0xFF).toByte()
                        respBytes[1] = (txId and 0xFF).toByte()
                    }
                    return respBytes
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun buildDnsQueryPacket(domain: String, txId: Int, qType: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        // Header (12 bytes)
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // RD = 1
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00) // ANCOUNT = 0
        out.write(0x00); out.write(0x00) // NSCOUNT = 0
        out.write(0x00); out.write(0x00) // ARCOUNT = 0

        domain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0x00) // End of QNAME
        out.write((qType shr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00); out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    fun parseDnsResponsePacket(packet: ByteArray, length: Int = packet.size): DnsWireAnswer? {
        if (length < 12 || length > packet.size) return null
        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flagsHi = packet[2].toInt() and 0xFF
        val flagsLo = packet[3].toInt() and 0xFF
        val isResponse = (flagsHi and 0x80) != 0
        val isTruncated = (flagsHi and 0x02) != 0
        val rcode = flagsLo and 0x0F
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 4 > length) return null
            offset += 4
        }

        val ips = mutableListOf<String>()
        repeat(anCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 10 > length) return@repeat
            val rrType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val rdLength = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > length) return null
            if (rrType == 1 && rdLength == 4) {
                val ip = "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}." +
                    "${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
                ips.add(ip)
            } else if (rrType == 28 && rdLength == 16) {
                val addr = InetAddress.getByAddress(packet.copyOfRange(offset, offset + 16))
                addr.hostAddress?.let { ips.add(it) }
            }
            offset += rdLength
        }

        return DnsWireAnswer(
            txId = txId,
            rcode = rcode,
            ips = ips,
            isTruncated = isTruncated,
            isResponse = isResponse
        )
    }

    private fun skipDnsName(packet: ByteArray, startOffset: Int, limit: Int): Int {
        var pos = startOffset
        var steps = 0
        while (pos < limit && steps < 128) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) {
                return if (pos + 2 <= limit) pos + 2 else -1
            }
            pos += 1 + len
            steps++
        }
        return -1
    }

    fun isInternalDomain(domain: String): Boolean {
        val lower = domain.trim().lowercase().trimEnd('.')
        return lower == "localhost" ||
            lower.endsWith(".local") ||
            lower.endsWith(".lan") ||
            lower.endsWith(".internal") ||
            lower.endsWith(".home.arpa")
    }

    fun isBogonOrPrivateIp(ip: String): Boolean {
        val clean = ip.trim()
        if (clean.contains(":")) {
            val lower = clean.lowercase()
            return lower == "::" ||
                lower == "::1" ||
                lower.startsWith("fc") ||
                lower.startsWith("fd") ||
                lower.startsWith("fe80") ||
                lower.startsWith("ff")
        }
        val octets = clean.split(".")
        if (octets.size != 4) return true
        val o0 = octets[0].toIntOrNull() ?: return true
        val o1 = octets[1].toIntOrNull() ?: return true
        val o2 = octets[2].toIntOrNull() ?: return true
        val o3 = octets[3].toIntOrNull() ?: return true
        if (o0 !in 0..255 || o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255) return true

        return when {
            o0 == 0 -> true // 0.0.0.0/8
            o0 == 10 -> true // 10.0.0.0/8 RFC1918
            o0 == 100 && o1 in 64..127 -> true // 100.64.0.0/10 CGNAT
            o0 == 127 -> true // 127.0.0.0/8 Loopback
            o0 == 169 && o1 == 254 -> true // 169.254.0.0/16 Link-local
            o0 == 172 && o1 in 16..31 -> true // 172.16.0.0/12 RFC1918
            o0 == 192 && o1 == 0 && o2 == 0 -> true // 192.0.0.0/24
            o0 == 192 && o1 == 0 && o2 == 2 -> true // TEST-NET-1
            o0 == 192 && o1 == 168 -> true // 192.168.0.0/16 RFC1918
            o0 == 198 && (o1 == 18 || o1 == 19) -> true // 198.18.0.0/15 FakeDNS / Benchmark
            o0 >= 224 -> true // Multicast / Reserved
            else -> false
        }
    }

    /**
     * Validates an ECHConfigList payload (Base64 or RFC 9460 SvcParam "ech=<base64>").
     * Rejects empty, truncated, or malformed ECH payloads so Fail-Closed ECH never downgrades silently.
     */
    fun validateEchConfigList(rawConfig: String): Boolean {
        val cleaned = extractEchBase64Token(rawConfig)
        if (cleaned.length < 12) return false
        return try {
            val decoded = java.util.Base64.getDecoder().decode(cleaned)
            if (decoded.size < 8) return false
            val declaredLength = ((decoded[0].toInt() and 0xFF) shl 8) or (decoded[1].toInt() and 0xFF)
            val validFramed = declaredLength in 4..(decoded.size - 2)
            // Also accept raw ECHConfig without outer 2-byte ECHConfigList length prefix when >= 16 bytes
            // and version tag looks like draft-13/RFC (0xfe0d, 0xfe0c, 0xfe0a, 0xff00..0xffff, or length-prefixed).
            val versionTag = ((decoded[0].toInt() and 0xFF) shl 8) or (decoded[1].toInt() and 0xFF)
            val knownEchVersion = versionTag == 0xFE0D || versionTag == 0xFE0C || versionTag == 0xFE0A
            validFramed || (knownEchVersion && decoded.size >= 16)
        } catch (_: Exception) {
            false
        }
    }

    fun extractEchBase64Token(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val afterParam = if (trimmed.contains("ech=", ignoreCase = true)) {
            trimmed.substringAfter("ech=").substringBefore(" ").substringBefore(";").trim('"', '\'', ' ')
        } else {
            trimmed
        }
        return afterParam.replace("\\s+".toRegex(), "")
    }

    fun extractValidEchFromHttpsRecords(records: List<String>): String? {
        for (record in records) {
            val token = extractEchBase64Token(record)
            if (validateEchConfigList(token)) {
                return token
            }
        }
        return null
    }
}
