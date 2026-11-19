package com.example.xraycore

import com.example.core.ErrorClassifier
import com.example.data.model.ErrorCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class ConnectivityCheckResult(
    val reachable: Boolean,
    val latencyMs: Int? = null,
    val errorCategory: ErrorCategory = ErrorCategory.NONE,
    val safeMessage: String? = null,
    val technicalDetail: String? = null,
    val dnsVerified: Boolean = false
)

/**
 * OkHttp DNS resolution for verification traffic. The SOCKS proxy resolves only IP-literal DNS
 * server addresses, so no local resolver is consulted while the VPN app is excluded from its TUN.
 */
internal class SocksProxiedDns(
    private val proxy: Proxy,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("Empty hostname")
        if (proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) return Dns.SYSTEM.lookup(hostname)
        if (proxy.type() != Proxy.Type.SOCKS) {
            throw UnknownHostException("DNS-over-SOCKS requires a SOCKS proxy")
        }

        val deadlineNs = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1).toLong())
        var lastFailure: UnknownHostException? = null
        val ipv4 = try {
            resolveWithFallback(hostname, TYPE_A, deadlineNs)
        } catch (failure: UnknownHostException) {
            lastFailure = failure
            emptyList()
        }
        val ipv6 = try {
            resolveWithFallback(hostname, TYPE_AAAA, deadlineNs)
        } catch (failure: UnknownHostException) {
            lastFailure = failure
            emptyList()
        }
        val results = (ipv4 + ipv6).distinct()
        if (results.isNotEmpty()) return results
        throw UnknownHostException("DNS-over-SOCKS returned no addresses").apply {
            lastFailure?.let { initCause(it) }
        }
    }

    private fun resolveWithFallback(
        hostname: String,
        queryType: Int,
        deadlineNs: Long
    ): List<InetAddress> {
        var lastFailure: IOException? = null
        for (server in DNS_SERVERS) {
            try {
                val answers = query(hostname, queryType, server, deadlineNs)
                if (answers.isNotEmpty()) return answers
            } catch (failure: IOException) {
                lastFailure = failure
            } catch (failure: RuntimeException) {
                lastFailure = UnknownHostException("DNS-over-SOCKS query failed").apply {
                    initCause(failure)
                }
            }
        }
        if (lastFailure != null) {
            throw UnknownHostException("DNS-over-SOCKS query failed").apply { initCause(lastFailure) }
        }
        return emptyList()
    }
    private fun query(
        hostname: String,
        queryType: Int,
        server: ByteArray,
        deadlineNs: Long
    ): List<InetAddress> {
        val (id, payload) = createQuery(hostname, queryType)
        Socket(proxy).use { socket ->
            // InetAddress is built from literal octets; neither endpoint can trigger local DNS.
            socket.connect(
                InetSocketAddress(InetAddress.getByAddress(server), DNS_PORT),
                remainingTimeoutMs(deadlineNs)
            )
            socket.soTimeout = remainingTimeoutMs(deadlineNs)
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(payload.size)
            output.write(payload)
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val responseLength = input.readUnsignedShort()
            if (responseLength < DNS_HEADER_LENGTH) throw IOException("Truncated DNS response")
            val response = ByteArray(responseLength)
            input.readFully(response)
            return parseResponse(response, id)
        }
    }
    private fun remainingTimeoutMs(deadlineNs: Long): Int {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) throw SocketTimeoutException("DNS-over-SOCKS timed out")
        return TimeUnit.NANOSECONDS.toMillis(remainingNs)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun createQuery(hostname: String, queryType: Int): Pair<Int, ByteArray> {
        val id = java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1_0000)
        val query = ByteArrayOutputStream()
        DataOutputStream(query).use { out ->
            out.writeShort(id)
            out.writeShort(0x0100) // recursion desired
            out.writeShort(1)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(0)
            val normalized = hostname.trimEnd('.')
            if (normalized.isEmpty()) throw UnknownHostException("Empty hostname")
            normalized.split('.').forEach { label ->
                val ascii = IDN.toASCII(label)
                val bytes = ascii.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) throw UnknownHostException("Invalid hostname")
                out.writeByte(bytes.size)
                out.write(bytes)
            }
            out.writeByte(0)
            out.writeShort(queryType)
            out.writeShort(CLASS_IN)
        }
        if (query.size() > MAX_DNS_MESSAGE_LENGTH) throw UnknownHostException("Invalid hostname")
        return id to query.toByteArray()
    }

    private fun parseResponse(response: ByteArray, expectedId: Int): List<InetAddress> {
        if (response.size < DNS_HEADER_LENGTH || readU16(response, 0) != expectedId) {
            throw IOException("Invalid DNS response")
        }
        val flags = readU16(response, 2)
        if (flags and 0x8000 == 0 || flags and 0x0200 != 0) throw IOException("Invalid DNS response")
        val responseCode = flags and 0x000f
        if (responseCode != 0) throw UnknownHostException("DNS lookup returned code $responseCode")

        val questionCount = readU16(response, 4)
        val answerCount = readU16(response, 6)
        var offset = DNS_HEADER_LENGTH
        repeat(questionCount) {
            offset = skipName(response, offset)
            if (offset + 4 > response.size) throw IOException("Truncated DNS question")
            offset += 4
        }

        val addresses = ArrayList<InetAddress>()
        repeat(answerCount) {
            offset = skipName(response, offset)
            if (offset + 10 > response.size) throw IOException("Truncated DNS answer")
            val type = readU16(response, offset)
            val recordClass = readU16(response, offset + 2)
            val dataLength = readU16(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) throw IOException("Truncated DNS answer data")
            val addressLength = when (type) {
                TYPE_A -> 4
                TYPE_AAAA -> 16
                else -> 0
            }
            if (recordClass == CLASS_IN && addressLength != 0 && dataLength == addressLength) {
                addresses += InetAddress.getByAddress(response.copyOfRange(offset, offset + dataLength))
            }
            offset += dataLength
        }
        return addresses
    }

    private fun skipName(packet: ByteArray, start: Int): Int {
        var offset = start
        while (true) {
            if (offset >= packet.size) throw IOException("Truncated DNS name")
            val length = packet[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                length and 0xc0 == 0xc0 -> {
                    if (offset + 1 >= packet.size) throw IOException("Truncated DNS pointer")
                    return offset + 2
                }
                length and 0xc0 != 0 -> throw IOException("Invalid DNS label")
                else -> {
                    offset += length + 1
                    if (offset > packet.size) throw IOException("Truncated DNS label")
                }
            }
        }
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    companion object {
        private const val DNS_PORT = 53
        private const val DEFAULT_TIMEOUT_MS = 2_000
        private const val DNS_HEADER_LENGTH = 12
        private const val MAX_DNS_MESSAGE_LENGTH = 512
        private const val CLASS_IN = 1
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private val DNS_SERVERS = listOf(
            byteArrayOf(1, 1, 1, 1),
            byteArrayOf(8, 8, 8, 8)
        )
    }
}

/** One connection-time data-path check. Never runs periodically and can never dial directly. */
internal class TunnelConnectivityCheck(
    private val endpoints: List<String> = ENDPOINTS,
    proxy: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)),
    private val overallTimeoutMs: Long = 4_500L,
    callTimeoutMs: Long = 4_000L
) {
    private val usesSocksProxy = proxy.type() == Proxy.Type.SOCKS
    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .dns(SocksProxiedDns(proxy))
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followSslRedirects(false)
        .build()

    suspend fun isReachable(): Boolean = check().reachable

    suspend fun check(): ConnectivityCheckResult {
        val outcome = withTimeoutOrNull(overallTimeoutMs) {
            if (endpoints.isEmpty()) return@withTimeoutOrNull emptyList()
            coroutineScope {
                val results = Channel<ProbeResult>(endpoints.size)
                val jobs = endpoints.map { url ->
                    launch {
                        try {
                            val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                            results.trySend(client.newCall(request).returnsNoContent())
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            results.trySend(ProbeResult(false, null, false, failure))
                        }
                    }
                }
                val failures = ArrayList<ProbeResult>(endpoints.size)
                repeat(endpoints.size) {
                    val result = results.receive()
                    if (result.reachable) {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope listOf(result)
                    }
                    failures += result
                }
                failures
            }
        }

        if (outcome == null) {
            val classified = ErrorClassifier.classify("Connectivity check timed out")
            return ConnectivityCheckResult(
                reachable = false,
                errorCategory = ErrorCategory.TIMEOUT,
                safeMessage = classified.userMessage,
                technicalDetail = "Connectivity check timed out"
            )
        }
        val success = outcome.firstOrNull { it.reachable }
        if (success != null) {
            return ConnectivityCheckResult(
                reachable = true,
                latencyMs = success.latencyMs,
                dnsVerified = success.usedHostname && usesSocksProxy
            )
        }
        val detail = outcome.firstNotNullOfOrNull { it.failure?.message }
            ?: if (endpoints.isEmpty()) "No connectivity endpoints configured" else null
        val cause = outcome.firstNotNullOfOrNull { it.failure }
        val classified = ErrorClassifier.classify(detail, cause)
        return ConnectivityCheckResult(
            reachable = false,
            errorCategory = classified.category,
            safeMessage = classified.userMessage,
            technicalDetail = classified.technicalDetail
        )
    }

    private suspend fun Call.returnsNoContent(): ProbeResult = suspendCancellableCoroutine { continuation ->
        val startedAtNs = System.nanoTime()
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(ProbeResult(false, null, false, e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    val reachable = it.code == 204
                    ProbeResult(
                        reachable = reachable,
                        latencyMs = if (reachable) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null,
                        usedHostname = !isIpLiteral(call.request().url.host),
                        failure = if (reachable) null else IOException("HTTP ${it.code} from connectivity endpoint")
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || (host.isNotEmpty() && host.all { it in '0'..'9' || it == '.' })

    private data class ProbeResult(
        val reachable: Boolean,
        val latencyMs: Int?,
        val usedHostname: Boolean,
        val failure: Throwable?
    )

    companion object {
        const val SOCKS_PORT = 10808
        private val ENDPOINTS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}
