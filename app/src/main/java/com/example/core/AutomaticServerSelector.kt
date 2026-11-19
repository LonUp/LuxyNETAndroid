package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.EffectiveHealthState
import com.example.data.model.HealthPolicy
import com.example.data.model.SourceType
import java.util.LinkedHashMap

/**
 * Source-aware, health-aware, country-aware candidate selection pipeline.
 * Independent of UI presentation.
 */
object AutomaticServerSelector {

    data class TierCounts(
        val working: Int = 0,
        val untested: Int = 0,
        val cooldownExpired: Int = 0,
        val inCooldown: Int = 0
    )

    sealed interface SelectionPlan {
        data object EmptyLibrary : SelectionPlan
        data object NoSourcesEnabled : SelectionPlan
        data object AllConfigsDisabled : SelectionPlan
        data object NoSupportedConfigs : SelectionPlan
        data class NoServersForCountry(val countryCode: String) : SelectionPlan
        data class Candidates(
            val ordered: List<ServerProfileEntity>,
            val tierCounts: TierCounts
        ) : SelectionPlan
    }

    fun isProfileFromEnabledSource(
        profile: ServerProfileEntity,
        enabledSubscriptionIds: Set<String>,
        manualConfigsEnabled: Boolean
    ): Boolean {
        return when (SourceType.parse(profile.sourceType)) {
            SourceType.SUBSCRIPTION -> profile.subscriptionId != null && profile.subscriptionId in enabledSubscriptionIds
            SourceType.MANUAL -> manualConfigsEnabled
            SourceType.LEGACY_UNCLASSIFIED -> false
        }
    }

    fun eligibleProfiles(
        allProfiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity>,
        manualConfigsEnabled: Boolean,
        selectedCountryCode: String? = null
    ): List<ServerProfileEntity> {
        val enabledSubIds = subscriptions.asSequence()
            .filter { it.isEnabledForConnection }
            .map { it.id }
            .toSet()
        return allProfiles.filter { profile ->
            isProfileFromEnabledSource(profile, enabledSubIds, manualConfigsEnabled) &&
                profile.isEnabled &&
                ClientPolicy.isSupported(profile) &&
                ServerCountryResolver.matches(profile, selectedCountryCode)
        }
    }

    fun buildPlan(
        allProfiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity>,
        manualConfigsEnabled: Boolean,
        selectedCountryCode: String?,
        previousActiveId: String?,
        currentNetworkFingerprint: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): SelectionPlan {
        if (allProfiles.isEmpty()) return SelectionPlan.EmptyLibrary

        val enabledSubIds = subscriptions.asSequence()
            .filter { it.isEnabledForConnection }
            .map { it.id }
            .toSet()

        val fromEnabledSources = allProfiles.filter {
            isProfileFromEnabledSource(it, enabledSubIds, manualConfigsEnabled)
        }
        if (fromEnabledSources.isEmpty()) {
            val anySourceEnabled = manualConfigsEnabled || enabledSubIds.isNotEmpty()
            return if (!anySourceEnabled) SelectionPlan.NoSourcesEnabled else SelectionPlan.EmptyLibrary
        }

        val enabledConfigs = fromEnabledSources.filter { it.isEnabled }
        if (enabledConfigs.isEmpty()) return SelectionPlan.AllConfigsDisabled

        val supportedConfigs = enabledConfigs.filter(ClientPolicy::isSupported)
        if (supportedConfigs.isEmpty()) return SelectionPlan.NoSupportedConfigs

        val normalizedCountry = ServerCountryResolver.normalizeCode(selectedCountryCode)
        val countryMatched = if (normalizedCountry == null) {
            supportedConfigs
        } else {
            supportedConfigs.filter { ServerCountryResolver.matches(it, normalizedCountry) }
        }
        if (countryMatched.isEmpty() && normalizedCountry != null) {
            return SelectionPlan.NoServersForCountry(normalizedCountry)
        }

        val tier1Working = mutableListOf<ServerProfileEntity>()
        val tier2Untested = mutableListOf<ServerProfileEntity>()
        val tier3CooldownExpired = mutableListOf<ServerProfileEntity>()
        val tier4InCooldown = mutableListOf<ServerProfileEntity>()

        for (profile in countryMatched) {
            when (HealthPolicy.effectiveHealthState(profile, nowMs, currentNetworkFingerprint)) {
                EffectiveHealthState.WORKING -> tier1Working += profile
                EffectiveHealthState.UNTESTED -> tier2Untested += profile
                EffectiveHealthState.FAILED -> {
                    if (HealthPolicy.isCooldownActive(profile, nowMs, currentNetworkFingerprint)) {
                        tier4InCooldown += profile
                    } else {
                        tier3CooldownExpired += profile
                    }
                }
                EffectiveHealthState.DISABLED, EffectiveHealthState.INVALID -> Unit
            }
        }

        val countryPriority: (ServerProfileEntity) -> Int = { p ->
            if (normalizedCountry == null) 0
            else when (ServerCountryResolver.effectiveConfidence(p)) {
                CountryConfidence.VERIFIED -> 0
                CountryConfidence.ESTIMATED -> 1
                CountryConfidence.UNKNOWN -> 2
            }
        }

        val sortedTier1 = tier1Working.sortedWith(
            compareBy<ServerProfileEntity>(countryPriority)
                .thenByDescending { it.isPinned }
                .thenByDescending { it.id == previousActiveId }
                .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                .thenByDescending { it.lastSuccessAt ?: 0L }
        )

        // Preferred (pinned) untested servers must never be demoted by source interleaving:
        // interleave the preferred and non-preferred partitions independently, preferred first.
        // Country priority is preserved by bucketing before interleaving.
        val sortedTier2 = tier2Untested
            .groupBy(countryPriority)
            .toSortedMap()
            .flatMap { (_, bucket) ->
                val tieBreak = compareBy<ServerProfileEntity> { it.id != previousActiveId }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE }
                    .thenByDescending { it.createdAt }
                val preferred = interleaveBySource(bucket.filter { it.isPinned }.sortedWith(tieBreak))
                val regular = interleaveBySource(bucket.filter { !it.isPinned }.sortedWith(tieBreak))
                preferred + regular
            }

        val sortedTier3 = tier3CooldownExpired.sortedWith(
            compareBy<ServerProfileEntity>(countryPriority)
                .thenBy { it.consecutiveFailures }
                .thenBy { it.lastAttemptAt ?: 0L }
        )

        val sortedTier4 = tier4InCooldown.sortedWith(
            compareBy<ServerProfileEntity>(countryPriority)
                .thenBy { it.cooldownUntilMs }
                .thenBy { it.consecutiveFailures }
        ).take(MAX_COOLDOWN_FALLBACK)

        val ordered = sortedTier1 + sortedTier2 + sortedTier3 + sortedTier4
        return SelectionPlan.Candidates(
            ordered = ordered,
            tierCounts = TierCounts(
                working = sortedTier1.size,
                untested = sortedTier2.size,
                cooldownExpired = sortedTier3.size,
                inCooldown = sortedTier4.size
            )
        )
    }

    /**
     * Round-robin interleaving across sources so a large broken subscription cannot starve
     * healthy servers from another enabled subscription or manual configurations.
     */
    private fun interleaveBySource(profiles: List<ServerProfileEntity>): List<ServerProfileEntity> {
        if (profiles.size <= 1) return profiles
        val buckets = LinkedHashMap<String, ArrayDeque<ServerProfileEntity>>()
        for (p in profiles) {
            val key = if (SourceType.parse(p.sourceType) == SourceType.SUBSCRIPTION) {
                "sub:${p.subscriptionId.orEmpty()}"
            } else {
                "manual"
            }
            buckets.getOrPut(key) { ArrayDeque() }.addLast(p)
        }
        if (buckets.size <= 1) return profiles
        val result = ArrayList<ServerProfileEntity>(profiles.size)
        while (result.size < profiles.size) {
            var progressed = false
            for (queue in buckets.values) {
                val next = queue.removeFirstOrNull()
                if (next != null) {
                    result.add(next)
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return result
    }

    private const val MAX_COOLDOWN_FALLBACK = 12
}
