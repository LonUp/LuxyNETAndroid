package com.example.core

import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SubscriptionFetchResult(
    val success: Boolean,
    val rawBody: String = "",
    val report: BatchParseReport = BatchParseReport(emptyList(), 0, 0, emptyList()),
    val error: String? = null
)

object SubscriptionFetcher {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(VpnSocketProtector.createProtectedSocketFactory())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun fetchSubscription(
        url: String,
        subscriptionId: String,
        groupName: String,
        userAgent: String = "LuxyVPN/3.0",
        topNLimit: Int = 50
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext SubscriptionFetchResult(
                success = false,
                error = "Subscription URL must start with http:// or https://"
            )
        }

        try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", userAgent.ifBlank { "LuxyVPN/3.0" })
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SubscriptionFetchResult(
                        success = false,
                        error = "HTTP ${resp.code} ${resp.message}"
                    )
                }
                val body = resp.body?.string().orEmpty()
                val report = ProxyUriParser.parseBatchWithReport(
                    rawInput = body,
                    subscriptionId = subscriptionId,
                    groupName = groupName,
                    topN = topNLimit
                )
                SubscriptionFetchResult(
                    success = report.nodes.isNotEmpty(),
                    rawBody = body,
                    report = report,
                    error = if (report.nodes.isEmpty()) "No valid proxy nodes found in subscription" else null
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            SubscriptionFetchResult(
                success = false,
                error = e.message ?: "Failed to fetch subscription"
            )
        }
    }
}
