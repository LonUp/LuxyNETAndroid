package com.example.core

import com.example.data.local.ServerProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class SubscriptionFetchResult {
    data class Success(
        val nodes: List<ServerProfileEntity>,
        val totalParsed: Int = nodes.size,
        val trimmedCount: Int = 0,
        val strippedDirectives: List<String> = emptyList()
    ) : SubscriptionFetchResult()

    data class Error(val message: String) : SubscriptionFetchResult()
}

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetch(
        url: String,
        subscriptionId: String,
        groupName: String,
        topN: Int = 50,
        userAgent: String = DEFAULT_USER_AGENT
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext SubscriptionFetchResult.Error("Subscription URL must start with http:// or https://")
        }

        val effectiveUserAgent = userAgent.trim().ifBlank { DEFAULT_USER_AGENT }

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", effectiveUserAgent)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SubscriptionFetchResult.Error("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext SubscriptionFetchResult.Error("Subscription returned an empty response")
                }
                val report = ProxyUriParser.parseBatchWithReport(
                    rawInput = body,
                    subscriptionId = subscriptionId,
                    groupName = groupName,
                    topN = topN
                )
                if (report.nodes.isEmpty()) {
                    SubscriptionFetchResult.Error("No valid proxy URIs or Xray JSON configs found")
                } else {
                    SubscriptionFetchResult.Success(
                        nodes = report.nodes,
                        totalParsed = report.totalParsed,
                        trimmedCount = report.trimmedCount,
                        strippedDirectives = report.strippedDirectives
                    )
                }
            }
        } catch (e: Exception) {
            SubscriptionFetchResult.Error(e.localizedMessage ?: "Network request failed")
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "LuxyVPN/3.0"
        val USER_AGENT_PRESETS = listOf(
            "LuxyVPN/3.0",
            "v2rayNG/2.3.15",
            "HiddifyNext/5.9.0",
            "ClashMeta/1.19"
        )
    }
}
