package com.example.core

import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SubscriptionFetchResult(
    val success: Boolean,
    val rawBody: String = "",
    val report: BatchParseReport = BatchParseReport(emptyList(), 0, 0, emptyList()),
    val error: String? = null,
    /** Quota reported through `subscription-userinfo`, null when the panel omits it. */
    val userInfo: SubscriptionUserInfo? = null,
    /** Panel-supplied name (`profile-title`), usually base64-prefixed. */
    val profileTitle: String? = null
)

object SubscriptionFetcher {
    /**
     * `profile-title` is either plain UTF-8 or `base64:<payload>`; panels are inconsistent about
     * padding, so decoding is tolerant and falls back to the raw string.
     */
    private fun decodePanelTitle(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("base64:", ignoreCase = true)) return value
        val payload = value.substringAfter(':').trim()
        return runCatching {
            String(Base64Compat.decode(payload), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(VpnSocketProtector.createProtectedSocketFactory())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun fetchSubscription(
        url: String,
        subscriptionId: String,
        groupName: String,
        userAgent: String = "LuxyVPN",
        topNLimit: Int = 0
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        SubscriptionUrlPolicy.rejection(cleanUrl)?.let { reason ->
            return@withContext SubscriptionFetchResult(false, error = reason)
        }
        val request = try {
            Request.Builder().url(cleanUrl).header("User-Agent", userAgent.ifBlank { "LuxyVPN" }).get().build()
        } catch (_: IllegalArgumentException) {
            return@withContext SubscriptionFetchResult(false, error = "Invalid subscription URL")
        }
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        SubscriptionFetchResult(false, error = "Subscription request failed. Check your network and URL")
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                SubscriptionFetchResult(false, error = "Subscription returned HTTP ${resp.code}")
                            } else {
                                val responseBody = resp.body
                                if ((responseBody?.contentLength() ?: 0) > ImportInput.MAX_BYTES) throw ImportTooLargeException()
                                val body = responseBody?.byteStream()?.use(ImportInput::readText).orEmpty()
                                val report = ProxyUriParser.parseBatchWithReport(body, subscriptionId, groupName, topNLimit)
                                SubscriptionFetchResult(
                                    success = report.nodes.isNotEmpty(),
                                    rawBody = body,
                                    report = report,
                                    error = if (report.nodes.isEmpty()) {
                                        report.failures.firstOrNull() ?: "No supported stable Xray servers in subscription"
                                    } else null,
                                    userInfo = SubscriptionUserInfo.parse(resp.header("subscription-userinfo")),
                                    profileTitle = resp.header("profile-title")?.let(::decodePanelTitle)
                                )
                            }
                        }
                    } catch (_: ImportTooLargeException) {
                        SubscriptionFetchResult(false, error = ImportInput.TOO_LARGE)
                    } catch (e: Exception) {
                        SubscriptionFetchResult(false, error = "Could not read subscription response")
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
