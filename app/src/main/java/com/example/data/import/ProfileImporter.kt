package com.example.data.import

import com.example.core.ClientPolicy
import com.example.core.SubscriptionFetcher
import com.example.data.local.SubscriptionEntity
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Finishes network fetches and persistence before reporting any successful import. */
class ProfileImporter(private val repository: LuxyVpnRepository) {
    suspend fun importText(rawInput: String, groupName: String = "Imported"): ImportSummary = withContext(Dispatchers.IO) {
        val results = LinkImporter.handleMany(rawInput, groupName)
        if (results.isEmpty()) return@withContext ImportSummary(failures = listOf("Nothing to import"))
        var profiles = 0
        var subscriptions = 0
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val fetchedUrls = mutableSetOf<String>()
        for (result in results) {
            currentCoroutineContext().ensureActive()
            try {
                when (result) {
                    is LinkImporter.Result.Unsupported -> failures += result.reason
                    is LinkImporter.Result.Nodes -> {
                        failures += result.failures
                        notes += result.notes
                        val accepted = result.nodes.filter(ClientPolicy::isSupported)
                        if (accepted.size != result.nodes.size) failures += "Unsupported experimental servers were rejected"
                        if (accepted.isNotEmpty()) {
                            repository.saveProfiles(accepted)
                            profiles += accepted.size
                        }
                    }
                    is LinkImporter.Result.Subscription -> {
                        if (!fetchedUrls.add(result.url)) continue
                        val existing = repository.getAllSubscriptions().firstOrNull { it.url == result.url }
                        val subscription = existing ?: SubscriptionEntity(
                            name = result.name.ifBlank { "Subscription" }, url = result.url
                        )
                        val fetched = SubscriptionFetcher.fetchSubscription(result.url, subscription.id, groupName)
                        failures += fetched.report.failures
                        notes += fetched.report.strippedDirectives.map { "Ignored imported directive: $it" }
                        val accepted = fetched.report.nodes.filter(ClientPolicy::isSupported)
                        if (!fetched.success || accepted.isEmpty()) {
                            failures += fetched.error ?: "Subscription contains no supported stable Xray servers"
                            continue
                        }
                        if (accepted.size != fetched.report.nodes.size) failures += "Unsupported experimental servers were rejected"
                        currentCoroutineContext().ensureActive()
                        val quota = fetched.userInfo
                        repository.importSubscription(subscription.copy(
                            name = fetched.profileTitle ?: subscription.name,
                            lastUpdatedAt = System.currentTimeMillis(),
                            nodeCount = accepted.size,
                            lastStatus = "Updated",
                            lastError = null,
                            uploadBytes = quota?.uploadBytes ?: subscription.uploadBytes,
                            downloadBytes = quota?.downloadBytes ?: subscription.downloadBytes,
                            totalBytes = quota?.totalBytes ?: subscription.totalBytes,
                            expireAtEpochSec = quota?.expireAtEpochSec ?: subscription.expireAtEpochSec,
                            profileTitle = fetched.profileTitle ?: subscription.profileTitle
                        ), accepted)
                        profiles += accepted.size
                        subscriptions++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += "Imported configuration could not be saved"
            }
        }
        ImportSummary(profiles = profiles, subscriptions = subscriptions,
            failures = failures, notes = notes.distinct())
    }
}
