package com.example

import com.example.core.AutomaticServerSelector
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preferred (pinned) configurations are a hint inside a health tier, never a bypass of
 * source toggles, eligibility, country priority, or health-based fallback.
 */
class PreferredServerSelectionTest {

    private val now = 1_000_000_000L

    private fun profile(
        id: String,
        subId: String? = null,
        sourceType: SourceType = if (subId != null) SourceType.SUBSCRIPTION else SourceType.MANUAL,
        preferred: Boolean = false,
        enabled: Boolean = true,
        health: HealthStatus = HealthStatus.UNTESTED,
        latencyMs: Int? = null,
        lastSuccessAt: Long? = null,
        lastAttemptAt: Long? = null,
        consecutiveFailures: Int = 0,
        cooldownUntilMs: Long = 0L,
        countryCode: String? = null,
        confidence: CountryConfidence = CountryConfidence.UNKNOWN,
        address: String = "$id.example.com"
    ) = ServerProfileEntity(
        id = id,
        name = id,
        address = address,
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        subscriptionId = subId,
        sourceType = sourceType.name,
        isPinned = preferred,
        isEnabled = enabled,
        healthStatus = health.name,
        latencyMs = latencyMs,
        lastSuccessAt = lastSuccessAt,
        lastAttemptAt = lastAttemptAt,
        consecutiveFailures = consecutiveFailures,
        cooldownUntilMs = cooldownUntilMs,
        countryCode = countryCode,
        countryConfidence = confidence.name,
        countrySource = when (confidence) {
            CountryConfidence.VERIFIED -> CountrySource.EXIT_VERIFIED.name
            CountryConfidence.ESTIMATED -> CountrySource.GEOIP.name
            CountryConfidence.UNKNOWN -> CountrySource.NONE.name
        }
    )

    private fun candidates(
        profiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity>,
        manualEnabled: Boolean = true,
        country: String? = null,
        previousActiveId: String? = null
    ): AutomaticServerSelector.SelectionPlan.Candidates = AutomaticServerSelector.buildPlan(
        allProfiles = profiles,
        subscriptions = subscriptions,
        manualConfigsEnabled = manualEnabled,
        selectedCountryCode = country,
        previousActiveId = previousActiveId,
        nowMs = now
    ) as AutomaticServerSelector.SelectionPlan.Candidates

    @Test
    fun preferredUntestedServersLeadTierTwoWithoutBeingDemotedByInterleaving() {
        val s1 = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        val s2 = SubscriptionEntity(id = "s2", name = "S2", url = "https://s2.example")
        val preferredS1 = profile("pref-s1", subId = "s1", preferred = true, latencyMs = 900)
        val preferredS2 = profile("pref-s2", subId = "s2", preferred = true, latencyMs = 800)
        val regularS1 = (1..3).map { profile("s1-u$it", subId = "s1", latencyMs = it) }
        val regularS2 = (1..3).map { profile("s2-u$it", subId = "s2", latencyMs = it) }

        val plan = candidates(
            listOf(preferredS1, preferredS2) + regularS1 + regularS2,
            listOf(s1, s2)
        )

        assertEquals(8, plan.tierCounts.untested)
        val ordered = plan.ordered.map { it.id }
        assertEquals(setOf("pref-s1", "pref-s2"), ordered.take(2).toSet())
        // Low-latency regular servers must stay behind both preferred ones.
        assertTrue(ordered.indexOf("pref-s1") < ordered.indexOf("s1-u1"))
        assertTrue(ordered.indexOf("pref-s2") < ordered.indexOf("s2-u1"))
        // Interleaving still applies inside the regular partition.
        val regularIds = ordered.drop(2)
        assertTrue(regularIds.take(4).any { it.startsWith("s1-") })
        assertTrue(regularIds.take(4).any { it.startsWith("s2-") })
    }

    @Test
    fun preferredFailedServerNeverBlocksHealthyFallback() {
        val s1 = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        val preferredFailed = profile(
            "pref-failed", subId = "s1", preferred = true, health = HealthStatus.FAILED,
            lastAttemptAt = now - 2_000L, consecutiveFailures = 4, cooldownUntilMs = now + 600_000L
        )
        val regularUntested = profile("regular-untested", subId = "s1", latencyMs = 40)
        val regularWorking = profile(
            "regular-working", subId = "s1", health = HealthStatus.WORKING,
            lastSuccessAt = now - 10_000L, latencyMs = 90
        )

        val plan = candidates(listOf(preferredFailed, regularUntested, regularWorking), listOf(s1))

        val ordered = plan.ordered.map { it.id }
        assertEquals(listOf("regular-working", "regular-untested", "pref-failed"), ordered)
        assertEquals(1, plan.tierCounts.working)
        assertEquals(1, plan.tierCounts.untested)
        assertEquals(1, plan.tierCounts.inCooldown)
    }

    @Test
    fun preferredFailedServerWithoutCooldownStaysBehindWorkingServers() {
        val preferredFailed = profile(
            "pref-failed", preferred = true, health = HealthStatus.FAILED,
            lastAttemptAt = now - 120_000L, consecutiveFailures = 2, cooldownUntilMs = now - 1_000L
        )
        val regularWorking = profile(
            "regular-working", health = HealthStatus.WORKING,
            lastSuccessAt = now - 5_000L, latencyMs = 120
        )

        val plan = candidates(listOf(preferredFailed, regularWorking), emptyList())

        assertEquals(listOf("regular-working", "pref-failed"), plan.ordered.map { it.id })
        assertEquals(1, plan.tierCounts.cooldownExpired)
    }

    @Test
    fun preferredStillRespectsSourceTogglesEligibilityAndEnablement() {
        val enabledSub = SubscriptionEntity(id = "enabled", name = "Enabled", url = "https://a.example")
        val disabledSub = SubscriptionEntity(
            id = "disabled", name = "Disabled", url = "https://b.example", isEnabledForConnection = false
        )
        val manualEnabled = profile("manual-enabled", preferred = true)
        val fromDisabledSub = profile("sub-disabled", subId = "disabled", preferred = true)
        val explicitlyDisabled = profile("disabled-profile", preferred = true, enabled = false)
        val unsupported = profile(
            "unsupported", preferred = true, address = "bad host"
        )

        val plan = candidates(
            listOf(manualEnabled, fromDisabledSub, explicitlyDisabled, unsupported),
            listOf(enabledSub, disabledSub)
        )

        assertEquals(listOf("manual-enabled"), plan.ordered.map { it.id })
    }

    @Test
    fun preferredWorkingServerLeadsWithinSameCountryConfidence() {
        val sub = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        val preferredSlow = profile(
            "pref-slow", subId = "s1", preferred = true, health = HealthStatus.WORKING,
            lastSuccessAt = now - 10_000L, latencyMs = 5_000, countryCode = "DE",
            confidence = CountryConfidence.ESTIMATED
        )
        val regularFast = profile(
            "regular-fast", subId = "s1", health = HealthStatus.WORKING,
            lastSuccessAt = now - 10_000L, latencyMs = 10, countryCode = "DE",
            confidence = CountryConfidence.ESTIMATED
        )

        val plan = candidates(listOf(preferredSlow, regularFast), listOf(sub))

        assertEquals(listOf("pref-slow", "regular-fast"), plan.ordered.map { it.id })
    }

    @Test
    fun countryConfidenceStillOutranksPreferenceInsideTierOne() {
        val sub = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        val preferredEstimated = profile(
            "pref-estimated", subId = "s1", preferred = true, health = HealthStatus.WORKING,
            lastSuccessAt = now - 10_000L, latencyMs = 10, countryCode = "DE",
            confidence = CountryConfidence.ESTIMATED
        )
        val verifiedRegular = profile(
            "verified-regular", subId = "s1", health = HealthStatus.WORKING,
            lastSuccessAt = now - 10_000L, latencyMs = 900, countryCode = "DE",
            confidence = CountryConfidence.VERIFIED
        )

        val plan = candidates(listOf(preferredEstimated, verifiedRegular), listOf(sub), country = "DE")

        assertEquals(listOf("verified-regular", "pref-estimated"), plan.ordered.map { it.id })
    }

    @Test
    fun preferredInLargeSubscriptionCannotStarveAnotherSourceInTierTwo() {
        val s1 = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        val s2 = SubscriptionEntity(id = "s2", name = "S2", url = "https://s2.example")
        val preferredMany = (1..6).map { profile("pref-s1-$it", subId = "s1", preferred = true, latencyMs = it) }
        val preferredOther = profile("pref-s2-1", subId = "s2", preferred = true, latencyMs = 50)

        val plan = candidates(preferredMany + preferredOther, listOf(s1, s2))

        val ordered = plan.ordered.map { it.id }
        assertFalse(ordered.take(6).all { it.startsWith("pref-s1-") })
        assertTrue(ordered.take(2).contains("pref-s2-1"))
    }
}
