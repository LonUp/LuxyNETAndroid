package com.example.xraycore

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** One connection-time data-path check. Never runs periodically and can never dial directly. */
internal class TunnelConnectivityCheck(
    private val endpoints: List<String> = ENDPOINTS,
    proxy: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)),
    private val overallTimeoutMs: Long = 4_500L,
    callTimeoutMs: Long = 4_000L
) {
    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followSslRedirects(false)
        .build()

    suspend fun isReachable(): Boolean = withTimeoutOrNull(overallTimeoutMs) {
        if (endpoints.isEmpty()) return@withTimeoutOrNull false
        coroutineScope {
            val results = Channel<Boolean>(endpoints.size)
            val jobs = endpoints.map { url ->
                launch {
                    val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                    results.trySend(client.newCall(request).returnsNoContent())
                }
            }
            var remaining = endpoints.size
            while (remaining > 0) {
                if (results.receive()) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope true
                }
                remaining--
            }
            false
        }
    } ?: false

    private suspend fun Call.returnsNoContent(): Boolean = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val reachable = response.use { it.code == 204 }
                if (continuation.isActive) continuation.resume(reachable)
            }
        })
    }

    companion object {
        const val SOCKS_PORT = 10808
        private val ENDPOINTS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}
