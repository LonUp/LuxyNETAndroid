package com.example.core

import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Public egress identity. Connected checks go out through the local SOCKS inbound so they
 * actually leave via the tunnel. The app itself is excluded from the TUN, so a normal request
 * would still show the ISP address.
 */
data class PublicEgress(
    val ip: String,
    val countryCode: String,
    val countryName: String,
    val provider: String
)

enum class EgressFailure {
    TIMEOUT,
    UNAVAILABLE
}

class PublicEgressProbe(
    private val socksPort: Int = 10808
) {
    suspend fun probe(throughTunnel: Boolean): Result<PublicEgress> = withContext(Dispatchers.IO) {
        val client = if (throughTunnel) tunneledClient else directClient
        var lastTimeout = false
        for (url in ENDPOINTS) {
            try {
                val response = client.newCall(Request.Builder().url(url).get().build()).await()
                response.use {
                    if (!it.isSuccessful) return@use
                    val body = it.body?.string().orEmpty()
                    parseEgressBody(body)?.let { parsed -> return@withContext Result.success(parsed) }
                }
            } catch (_: java.net.SocketTimeoutException) {
                lastTimeout = true
            } catch (_: Exception) {
                lastTimeout = false
            }
        }
        Result.failure(if (lastTimeout) EgressException(EgressFailure.TIMEOUT) else EgressException(EgressFailure.UNAVAILABLE))
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .socketFactory(VpnSocketProtector.createProtectedSocketFactory())
            .build()
    }

    private val tunneledClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .build()
    }

    companion object {
        private val ENDPOINTS = listOf(
            "https://ipwho.is/",
            "https://ipapi.co/json/"
        )

        fun parseEgressBody(body: String): PublicEgress? {
            if (body.isBlank()) return null
            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                return null
            }
            if (json.has("success") && !json.optBoolean("success", true)) return null
            val ip = json.optString("ip").trim().ifBlank { return null }
            val code = json.optString("country_code")
                .ifBlank { json.optString("country_code") }
                .ifBlank { json.optString("country") }
                .trim()
                .uppercase()
            val countryCode = code.takeIf { it.length == 2 && it.all { ch -> ch.isLetter() } }.orEmpty()
            val rawCountry = json.optString("country").trim()
            val countryName = json.optString("country_name").trim().ifBlank {
                rawCountry.takeUnless { it.length == 2 }.orEmpty()
            }
            val connection = json.optJSONObject("connection")
            val provider = listOf(
                connection?.optString("isp"),
                connection?.optString("org"),
                json.optString("org"),
                json.optString("asn_org"),
                json.optString("isp")
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()
            return PublicEgress(
                ip = ip,
                countryCode = countryCode,
                countryName = countryName,
                provider = provider
            )
        }
    }
}

class EgressException(val failure: EgressFailure) : Exception(failure.name)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
