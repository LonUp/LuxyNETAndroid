package com.example.core

import com.example.xraycore.TunnelConnectivityCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Stage 3 exit verification: determines the actual public exit IP and country strictly through
 * the proxy outbound (never direct device routing).
 */
class ExitCountryVerifier(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    proxy: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TunnelConnectivityCheck.SOCKS_PORT)),
    private val overallTimeoutMs: Long = 4_500L,
    callTimeoutMs: Long = 3_500L,
    private val geoIpLookup: (String) -> String? = { null }
) {
    data class ExitVerificationResult(
        val exitIp: String?,
        val countryCode: String?,
        val verifiedAtMs: Long
    )

    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    suspend fun verifyExit(): ExitVerificationResult? = withTimeoutOrNull(overallTimeoutMs) {
        for (url in endpoints) {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            val body = client.newCall(request).fetchBodyOrNull() ?: continue
            val parsed = parseResponse(body)
            if (parsed != null) {
                val (ip, traceLoc) = parsed
                val geoCode = ip?.let { ServerCountryResolver.normalizeCode(geoIpLookup(it)) }
                val country = geoCode ?: ServerCountryResolver.normalizeCode(traceLoc)
                if (ip != null || country != null) {
                    return@withTimeoutOrNull ExitVerificationResult(
                        exitIp = ip,
                        countryCode = country,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                }
            }
        }
        null
    }

    private suspend fun Call.fetchBodyOrNull(): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.string()?.take(4096)
                }
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }

    internal fun parseResponse(body: String): Pair<String?, String?>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        // Cloudflare trace format: key=value lines including ip=... and loc=...
        if (trimmed.contains("ip=") && trimmed.contains('\n')) {
            var ip: String? = null
            var loc: String? = null
            trimmed.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    when (k) {
                        "ip" -> ip = validatePublicIp(v)
                        "loc" -> loc = ServerCountryResolver.normalizeCode(v)
                    }
                }
            }
            if (ip != null || loc != null) return ip to loc
        }
        val plainIp = validatePublicIp(trimmed.lineSequence().firstOrNull()?.trim().orEmpty())
        return if (plainIp != null) plainIp to null else null
    }

    private fun validatePublicIp(raw: String): String? {
        val clean = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (!isNumericIpLiteral(clean)) return null
        val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return null
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return null
        }
        return addr.hostAddress
    }

    private fun isNumericIpLiteral(host: String): Boolean {
        if (host.all { it in '0'..'9' || it == '.' }) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return host.contains(':') && host.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
        }
    }

    companion object {
        private val DEFAULT_ENDPOINTS = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://api.ipify.org",
            "https://icanhazip.com"
        )
    }
}
