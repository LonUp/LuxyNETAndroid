package com.example.data.import

import com.example.core.ClientPolicy
import com.example.core.ConfigFingerprint
import com.example.core.CountryDetector
import com.example.core.SubscriptionFetcher
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Finishes network fetches and persistence before reporting any successful import. */
class ProfileImporter(
    private val repository: LuxyVpnRepository,
    private val countryDetector: CountryDetector = CountryDetector()
) {
    suspend fun importText(rawInput: String, groupName: String = "Imported"): ImportSummary = withContext(Dispatchers.IO) {
        val results = LinkImporter.handleMany(rawInput, groupName)
        if (results.isEmpty()) return@withContext ImportSummary(failures = listOf("Nothing to import"))
        var profiles = 0
        var duplicates = 0
        var invalid = 0
        var subscriptions = 0
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val fetchedUrls = mutableSetOf<String>()
        val pendingManualNodes = mutableListOf<ServerProfileEntity>()
        for (result in results) {
            currentCoroutineContext().ensureActive()
            try {
                when (result) {
                    is LinkImporter.Result.Unsupported -> {
                        failures += result.reason
                        invalid++
                    }
                    is LinkImporter.Result.Nodes -> {
                        failures += result.failures
                        invalid += result.failures.size
                        notes += result.notes
                        val accepted = result.nodes.filter(ClientPolicy::isSupported)
                        val rejectedCount = result.nodes.size - accepted.size
                        if (rejectedCount > 0) {
                            failures += "Unsupported experimental servers were rejected"
                            invalid += rejectedCount
                        }
                        pendingManualNodes += accepted
                    }
                    is LinkImporter.Result.Subscription -> {
                        val url = result.url.toHttpUrlOrNull()!!.newBuilder().fragment(null).build().toString()
                        if (!fetchedUrls.add(url)) continue
                        val existing = repository.getAllSubscriptions().firstOrNull {
                            it.url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() == url
                        }
                        val subscription = existing ?: SubscriptionEntity(
                            name = result.name.ifBlank { "Subscription" }, url = url
                        )
                        val summary = fetchAndReplace(subscription, groupName, existing == null)
                        failures += summary.failures
                        notes += summary.notes
                        profiles += summary.profiles
                        duplicates += summary.duplicates
                        invalid += summary.invalid
                        subscriptions += summary.subscriptions
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += "Imported configuration could not be saved"
            }
        }
        if (pendingManualNodes.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val manualResult = repository.importManualProfiles(pendingManualNodes, countryDetector)
            profiles += manualResult.imported
            duplicates += manualResult.duplicates
            invalid += manualResult.invalid
        }
        ImportSummary(
            profiles = profiles,
            duplicates = duplicates,
            invalid = invalid,
            subscriptions = subscriptions,
            failures = failures,
            notes = notes.distinct()
        )
    }

    suspend fun refreshSubscription(id: String): ImportSummary = withContext(Dispatchers.IO) {
        val subscription = repository.getAllSubscriptions().firstOrNull { it.id == id }
            ?: return@withContext ImportSummary(failures = listOf("Could not update subscription"))
        fetchAndReplace(subscription, "Imported", false)
    }

    suspend fun importSubscriptionUrl(
        rawUrl: String,
        customName: String = "",
        groupName: String = "Imported"
    ): ImportSummary = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext ImportSummary(failures = listOf("Invalid HTTP(S) subscription URL"))
        }
        val parsed = trimmed.toHttpUrlOrNull()
            ?: return@withContext ImportSummary(failures = listOf("Invalid HTTP(S) subscription URL"))
        val normalizedUrl = parsed.newBuilder().fragment(null).build().toString()
        val existing = repository.getAllSubscriptions().firstOrNull {
            it.url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() == normalizedUrl
        }
        val cleanName = customName.trim()
        val subscription = existing ?: SubscriptionEntity(
            name = cleanName.ifBlank { "Subscription" },
            url = normalizedUrl
        )
        val useProviderName = existing == null && cleanName.isBlank()
        fetchAndReplace(subscription, groupName, useProviderName)
    }

    private suspend fun fetchAndReplace(
        subscription: SubscriptionEntity,
        groupName: String,
        useProviderName: Boolean
    ): ImportSummary {
        try {
            val fetched = SubscriptionFetcher.fetchSubscription(subscription.url, subscription.id, groupName)
            val accepted = fetched.report.nodes.filter(ClientPolicy::isSupported)
            val rejectedCount = fetched.report.nodes.size - accepted.size
            val invalidCount = fetched.report.failures.size + rejectedCount
            if (!fetched.success || accepted.isEmpty()) {
                if (!useProviderName) repository.saveSubscription(subscription.copy(lastStatus = "Failed",
                    lastError = "Could not update subscription"))
                return ImportSummary(
                    invalid = invalidCount,
                    failures = listOf(
                        if (fetched.error == com.example.core.ImportInput.TOO_LARGE) com.example.core.ImportInput.TOO_LARGE
                        else "Could not update subscription"
                    )
                )
            }
            currentCoroutineContext().ensureActive()
            val seenFingerprints = HashSet<String>(accepted.size)
            val deduped = ArrayList<ServerProfileEntity>(accepted.size)
            var duplicateCount = 0
            for (node in accepted) {
                val fp = ConfigFingerprint.compute(node)
                if (seenFingerprints.add(fp)) {
                    deduped += node.copy(canonicalFingerprint = fp)
                } else {
                    duplicateCount++
                }
            }
            val quota = fetched.userInfo
            repository.importSubscription(
                subscription.copy(
                    name = if (useProviderName) fetched.profileTitle ?: subscription.name else subscription.name,
                    lastUpdatedAt = System.currentTimeMillis(),
                    nodeCount = deduped.size,
                    lastStatus = "Updated",
                    lastError = null,
                    uploadBytes = quota?.uploadBytes ?: 0L,
                    downloadBytes = quota?.downloadBytes ?: 0L,
                    totalBytes = quota?.totalBytes ?: 0L,
                    expireAtEpochSec = quota?.expireAtEpochSec ?: 0L,
                    profileTitle = fetched.profileTitle
                ),
                deduped,
                countryDetector
            )
            return ImportSummary(
                profiles = deduped.size,
                duplicates = duplicateCount,
                invalid = invalidCount,
                subscriptions = 1,
                failures = fetched.report.failures,
                notes = fetched.report.strippedDirectives.map { "Ignored imported directive: $it" }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!useProviderName) repository.saveSubscription(subscription.copy(lastStatus = "Failed",
                lastError = "Could not update subscription"))
            return ImportSummary(failures = listOf("Could not update subscription"))
        }
    }
}
