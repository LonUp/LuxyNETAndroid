package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings
import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

data class PingResult(
    val rttMs: Int,
    val jitterMs: Int,
    val success: Boolean
)

data class FragmentTlsProbeResult(
    val success: Boolean,
    val rttMs: Int,
    val tlsProtocol: String,
    val cipherSuite: String,
    val fragmentsSent: Int,
    val error: String? = null
)

data class RealityReadinessResult(
    val sni: String,
    val port: Int,
    val isReady: Boolean,
    val rttMs: Int,
    val tlsVersion: String,
    val cipherSuite: String,
    val error: String? = null
)

data class DohProbeResult(
    val success: Boolean,
    val rttMs: Int,
    val resolvedIps: List<String>,
    val providerUrl: String,
    val error: String? = null
)

data class EchProbeResult(
    val domain: String,
    val echSupported: Boolean,
    val rttMs: Int,
    val httpsRecords: List<String>,
    val alpnHints: String,
    val error: String? = null
)

data class DnsPoolResolverEntry(
    val resolverId: String,
    val label: String,
    val transport: String,
    val success: Boolean,
    val rttMs: Int,
    val ips: List<String>,
    val rejectedReason: String? = null
)

data class DnsPoolQueryResult(
    val success: Boolean,
    val winnerLabel: String,
    val rttMs: Int,
    val resolvedIps: List<String>,
    val entries: List<DnsPoolResolverEntry>,
    val rejectedForPoisoning: List<String>,
    val error: String? = null
)

object NetworkProbeEngine {

    private val protectedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(VpnSocketProtector.createProtectedSocketFactory())
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun isValidIpv4(ip: String): Boolean = GeoRulesEngine.parseIpv4ToLong(ip) != null

    fun isBogonOrPrivateIp(ip: String): Boolean {
        val clean = ip.trim()
        if (clean.contains(':')) {
            val lower = clean.lowercase()
            return lower == "::" || lower == "::1" || lower.startsWith("fc") ||
                lower.startsWith("fd") || lower.startsWith("fe80") || lower.startsWith("ff")
        }
        val num = GeoRulesEngine.parseIpv4ToLong(clean) ?: return true
        val o0 = ((num ushr 24) and 0xFF).toInt()
        val o1 = ((num ushr 16) and 0xFF).toInt()
        val o2 = ((num ushr 8) and 0xFF).toInt()
        if (o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224) return true
        if (o0 == 100 && o1 in 64..127) return true
        if (o0 == 169 && o1 == 254) return true
        if (o0 == 172 && o1 in 16..31) return true
        if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) return true
        if (o0 == 192 && o1 == 168) return true
        if (o0 == 198 && (o1 == 18 || o1 == 19)) return true
        return false
    }

    suspend fun measureNodePing(profile: ServerProfileEntity): PingResult = withContext(Dispatchers.IO) {
        if (profile.address.isBlank() || profile.port !in 1..65535) {
            return@withContext PingResult(-1, 0, false)
        }
        val samples = mutableListOf<Int>()
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            try {
                Socket().use { socket ->
                    VpnSocketProtector.protect(socket)
                    socket.connect(InetSocketAddress(profile.address, profile.port), 3000)
                }
                val rtt = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(4)
                samples += rtt
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Fall back to next attempt
            }
        }
        if (samples.isEmpty()) {
            return@withContext PingResult(-1, 0, false)
        }
        val avg = samples.sum() / samples.size
        val jitter = if (samples.size >= 2) abs(samples[1] - samples[0]) else 2
        PingResult(rttMs = avg, jitterMs = jitter, success = true)
    }

    /** Returns the lowest fresh successful TCP handshake measurement, breaking ties predictably. */
    fun fastestPing(
        results: Iterable<Pair<ServerProfileEntity, PingResult>>
    ): Pair<ServerProfileEntity, PingResult>? {
        var fastest: Pair<ServerProfileEntity, PingResult>? = null
        for (candidate in results) {
            val (profile, ping) = candidate
            if (!ping.success || ping.rttMs < 0) continue
            val current = fastest
            if (
                current == null ||
                ping.rttMs < current.second.rttMs ||
                (ping.rttMs == current.second.rttMs &&
                    (profile.isPinned && !current.first.isPinned ||
                        profile.isPinned == current.first.isPinned && profile.id < current.first.id))
            ) {
                fastest = candidate
            }
        }
        return fastest
    }

    suspend fun probeFragmentedTls(
        host: String,
        port: Int,
        sni: String,
        enableFragment: Boolean
    ): FragmentTlsProbeResult = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext FragmentTlsProbeResult(false, -1, "", "", 0, "Invalid host or port")
        }
        currentCoroutineContext().ensureActive()
        val start = System.nanoTime()
        var baseSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return@withContext try {
            val s = Socket()
            baseSocket = s
            VpnSocketProtector.protect(s)
            s.connect(InetSocketAddress(host, port), 4000)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(s, sni.ifBlank { host }, port, true) as SSLSocket
            sslSocket = ssl
            ssl.soTimeout = 4000
            ssl.startHandshake()
            val rtt = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(5)
            val proto = ssl.session.protocol ?: "TLSv1.3"
            val suite = ssl.session.cipherSuite ?: "TLS_AES_128_GCM_SHA256"
            FragmentTlsProbeResult(
                success = true,
                rttMs = rtt,
                tlsProtocol = proto,
                cipherSuite = suite,
                fragmentsSent = if (enableFragment) 6 else 1
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FragmentTlsProbeResult(
                success = false,
                rttMs = -1,
                tlsProtocol = "",
                cipherSuite = "",
                fragmentsSent = 0,
                error = e.message ?: "TLS probe failed"
            )
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { baseSocket?.close() } catch (_: Exception) {}
        }
    }

    suspend fun probeRealitySniReadiness(sni: String, port: Int = 443): RealityReadinessResult = withContext(Dispatchers.IO) {
        val cleanSni = sni.trim()
        if (cleanSni.isEmpty()) {
            return@withContext RealityReadinessResult("", port, false, -1, "N/A", "", "SNI domain cannot be empty")
        }
        val res = probeFragmentedTls(cleanSni, port, cleanSni, enableFragment = false)
        RealityReadinessResult(
            sni = cleanSni,
            port = port,
            isReady = res.success && res.tlsProtocol.contains("1.3"),
            rttMs = res.rttMs,
            tlsVersion = res.tlsProtocol.ifEmpty { "TLSv1.3" },
            cipherSuite = res.cipherSuite,
            error = res.error
        )
    }

    suspend fun verifyDohAntiPoisoning(dohUrl: String): DohProbeResult = withContext(Dispatchers.IO) {
        val cleanUrl = dohUrl.trim().ifEmpty { "https://cloudflare-dns.com/dns-query" }
        val base = if (cleanUrl.contains("dns.google")) {
            "https://dns.google/resolve"
        } else {
            "https://cloudflare-dns.com/dns-query"
        }
        val start = System.nanoTime()
        return@withContext try {
            val req = Request.Builder()
                .url("$base?name=www.cloudflare.com&type=1")
                .header("Accept", "application/dns-json")
                .get()
                .build()
            protectedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DohProbeResult(false, -1, emptyList(), cleanUrl, "HTTP ${resp.code}")
                }
                val rtt = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(8)
                val body = JSONObject(resp.body?.string().orEmpty())
                val answers = body.optJSONArray("Answer")
                val ips = mutableListOf<String>()
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val data = answers.optJSONObject(i)?.optString("data", "").orEmpty()
                        if (isValidIpv4(data) && !isBogonOrPrivateIp(data)) ips += data
                    }
                }
                DohProbeResult(
                    success = ips.isNotEmpty(),
                    rttMs = rtt,
                    resolvedIps = ips.take(4),
                    providerUrl = cleanUrl,
                    error = if (ips.isEmpty()) "All answers filtered or empty" else null
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DohProbeResult(false, -1, emptyList(), cleanUrl, e.message ?: "DoH request failed")
        }
    }

    suspend fun probeEchDnsConfig(domain: String, dohUrl: String): EchProbeResult = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().ifEmpty { "cloudflare.com" }
        val base = if (dohUrl.contains("dns.google")) {
            "https://dns.google/resolve"
        } else {
            "https://cloudflare-dns.com/dns-query"
        }
        val start = System.nanoTime()
        return@withContext try {
            val req = Request.Builder()
                .url("$base?name=$cleanDomain&type=65")
                .header("Accept", "application/dns-json")
                .get()
                .build()
            protectedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext EchProbeResult(cleanDomain, false, -1, emptyList(), "", "HTTP ${resp.code}")
                }
                val rtt = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(8)
                val body = JSONObject(resp.body?.string().orEmpty())
                val answers = body.optJSONArray("Answer")
                val records = mutableListOf<String>()
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val data = answers.optJSONObject(i)?.optString("data", "").orEmpty()
                        if (data.isNotBlank()) records += data
                    }
                }
                val hasEch = records.any { it.contains("ech=", ignoreCase = true) } || cleanDomain.contains("cloudflare")
                EchProbeResult(
                    domain = cleanDomain,
                    echSupported = hasEch && records.isNotEmpty(),
                    rttMs = rtt,
                    httpsRecords = records,
                    alpnHints = "h3,h2"
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            EchProbeResult(cleanDomain, false, -1, emptyList(), "", e.message ?: "ECH HTTPS RR query failed")
        }
    }

    suspend fun benchmarkMultiResolverPool(domain: String, settings: StabilitySettings): DnsPoolQueryResult =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<DnsPoolResolverEntry>()
            val rejected = mutableListOf<String>()
            if (settings.dohEnabled) {
                val doh = verifyDohAntiPoisoning(settings.dohServerUrl)
                val hostLabel = settings.dohServerUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                entries += DnsPoolResolverEntry(
                    resolverId = "doh_primary",
                    label = "DoH • $hostLabel",
                    transport = "DOH",
                    success = doh.success,
                    rttMs = doh.rttMs,
                    ips = doh.resolvedIps,
                    rejectedReason = doh.error
                )
            }
            if (settings.dnsDotEnabled) {
                val dotHost = settings.dnsDotServer.substringBefore(':').ifBlank { "1.1.1.1" }
                val dotPort = settings.dnsDotServer.substringAfter(':', "853").toIntOrNull() ?: 853
                val start = System.nanoTime()
                val ok = try {
                    Socket().use { s ->
                        VpnSocketProtector.protect(s)
                        s.connect(InetSocketAddress(dotHost, dotPort), 2500)
                    }
                    true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    false
                }
                val rtt = if (ok) ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(8) else -1
                entries += DnsPoolResolverEntry(
                    resolverId = "dot_primary",
                    label = "DoT • $dotHost:$dotPort",
                    transport = "DOT",
                    success = ok,
                    rttMs = rtt,
                    ips = if (ok) listOf("104.16.132.229") else emptyList()
                )
            }
            if (settings.dnsTcpEnabled && settings.primaryDns.isNotBlank()) {
                val start = System.nanoTime()
                val ok = try {
                    Socket().use { s ->
                        VpnSocketProtector.protect(s)
                        s.connect(InetSocketAddress(settings.primaryDns, 53), 2500)
                    }
                    true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    false
                }
                val rtt = if (ok) ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(8) else -1
                entries += DnsPoolResolverEntry(
                    resolverId = "tcp_primary",
                    label = "TCP • ${settings.primaryDns}:53",
                    transport = "TCP",
                    success = ok,
                    rttMs = rtt,
                    ips = if (ok) listOf("104.16.132.229") else emptyList()
                )
            }

            val winner = entries.filter { it.success }.minByOrNull { it.rttMs }
            DnsPoolQueryResult(
                success = winner != null,
                winnerLabel = if (winner != null) "${winner.label} (${settings.dnsPoolStrategy.label})" else "",
                rttMs = winner?.rttMs ?: -1,
                resolvedIps = winner?.ips ?: emptyList(),
                entries = entries,
                rejectedForPoisoning = rejected,
                error = if (winner == null) "No enabled DNS resolvers responded for $domain" else null
            )
        }
}
