package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.AutomaticServerSelector
import com.example.core.ConfigurationHealthTracker
import com.example.core.ProbeBatchPolicy
import com.example.core.ProbeOutcome
import com.example.core.ServerProbe
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.EffectiveHealthState
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import com.example.ui.LuxyVpnViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticSelectionAndHealthTest {
    private lateinit var app: LuxyVpnApplication
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        database = app.database
        repository = app.repository
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("test setup")
            repository.getAllProfiles().forEach { repository.deleteProfile(it.id) }
            repository.getAllSubscriptions().forEach { repository.deleteSubscriptionAndNodes(it.id) }
            repository.updateSettings { StabilitySettings() }
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("test teardown")
            repository.getAllProfiles().forEach { repository.deleteProfile(it.id) }
            repository.getAllSubscriptions().forEach { repository.deleteSubscriptionAndNodes(it.id) }
            repository.updateSettings { StabilitySettings() }
        }
    }

    private fun profile(
        id: String,
        name: String = id,
        subId: String? = null,
        sourceType: SourceType = if (subId != null) SourceType.SUBSCRIPTION else SourceType.MANUAL,
        enabled: Boolean = true,
        health: HealthStatus = HealthStatus.UNTESTED,
        latencyMs: Int? = null,
        lastSuccessAt: Long? = null,
        lastAttemptAt: Long? = null,
        consecutiveFailures: Int = 0,
        cooldownUntilMs: Long = 0L,
        countryCode: String? = null,
        confidence: CountryConfidence = CountryConfidence.UNKNOWN
    ) = ServerProfileEntity(
        id = id,
        name = name,
        address = "$id.example.com",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        subscriptionId = subId,
        sourceType = sourceType.name,
        isEnabled = enabled,
        healthStatus = health.name,
        latencyMs = latencyMs,
        lastSuccessAt = lastSuccessAt,
        lastAttemptAt = lastAttemptAt,
        consecutiveFailures = consecutiveFailures,
        cooldownUntilMs = cooldownUntilMs,
        countryCode = countryCode,
        countryConfidence = confidence.name,
        countrySource = if (confidence == CountryConfidence.VERIFIED) CountrySource.EXIT_VERIFIED.name else CountrySource.NONE.name
    )

    private suspend fun awaitMainUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (condition()) return
            delay(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    @Test
    fun singleMultiAndDisabledSourceFilteringWorksWithCountrySelection() {
        val sub1 = SubscriptionEntity(id = "s1", name = "Sub 1", url = "https://a.example", isEnabledForConnection = true)
        val sub2 = SubscriptionEntity(id = "s2", name = "Sub 2", url = "https://b.example", isEnabledForConnection = false)
        val p1 = profile("p1", "🇩🇪 S1 DE", subId = "s1", countryCode = "DE", confidence = CountryConfidence.ESTIMATED)
        val p2 = profile("p2", "🇩🇪 S2 DE", subId = "s2", countryCode = "DE", confidence = CountryConfidence.ESTIMATED)
        val p3 = profile("p3", "🇺🇸 Manual US", subId = null, countryCode = "US", confidence = CountryConfidence.ESTIMATED)
        val p4 = profile("p4", "🇩🇪 Manual Disabled", subId = null, enabled = false, countryCode = "DE", confidence = CountryConfidence.ESTIMATED)
        val p5 = profile("p5", "Unknown Manual", subId = null)
        val p6 = profile("p6", "Legacy", subId = null, sourceType = SourceType.LEGACY_UNCLASSIFIED)

        val all = listOf(p1, p2, p3, p4, p5, p6)

        // Automatic with s1 + manual enabled, s2 disabled -> p1, p3, p5 (unknown allowed in Automatic!)
        val autoPlan = AutomaticServerSelector.buildPlan(
            allProfiles = all,
            subscriptions = listOf(sub1, sub2),
            manualConfigsEnabled = true,
            selectedCountryCode = null,
            previousActiveId = null
        ) as AutomaticServerSelector.SelectionPlan.Candidates
        assertEquals(setOf("p1", "p3", "p5"), autoPlan.ordered.map { it.id }.toSet())

        // Specific country DE -> only p1 (p2 excluded by source, p4 disabled, p5 unknown excluded)
        val dePlan = AutomaticServerSelector.buildPlan(
            allProfiles = all,
            subscriptions = listOf(sub1, sub2),
            manualConfigsEnabled = true,
            selectedCountryCode = "DE",
            previousActiveId = null
        ) as AutomaticServerSelector.SelectionPlan.Candidates
        assertEquals(listOf("p1"), dePlan.ordered.map { it.id })

        // Manual disabled -> p3 and p5 excluded
        val subOnlyPlan = AutomaticServerSelector.buildPlan(
            allProfiles = all,
            subscriptions = listOf(sub1, sub2),
            manualConfigsEnabled = false,
            selectedCountryCode = null,
            previousActiveId = null
        ) as AutomaticServerSelector.SelectionPlan.Candidates
        assertEquals(listOf("p1"), subOnlyPlan.ordered.map { it.id })

        // All sources disabled -> NoSourcesEnabled
        val nonePlan = AutomaticServerSelector.buildPlan(
            allProfiles = all,
            subscriptions = listOf(sub1.copy(isEnabledForConnection = false), sub2),
            manualConfigsEnabled = false,
            selectedCountryCode = null,
            previousActiveId = null
        )
        assertEquals(AutomaticServerSelector.SelectionPlan.NoSourcesEnabled, nonePlan)
    }

    @Test
    fun invalidProfilesAreExcludedFromEligibleProfileCounts() {
        val valid = profile("eligible")
        val invalid = profile("invalid", health = HealthStatus.INVALID)
        val eligible = AutomaticServerSelector.eligibleProfiles(
            allProfiles = listOf(valid, invalid),
            subscriptions = emptyList(),
            manualConfigsEnabled = true,
            nowMs = 1_000_000L
        )

        assertEquals(listOf("eligible"), eligible.map { it.id })
    }

    @Test
    fun tieredSelectionPrioritizesWorkingInterleavesSourcesAndAppliesCooldownWithoutBlacklisting() {
        val now = 1_000_000_000L
        val sub1 = SubscriptionEntity(id = "s1", name = "Broken Sub", url = "https://a.example", isEnabledForConnection = true)
        val sub2 = SubscriptionEntity(id = "s2", name = "Healthy Sub", url = "https://b.example", isEnabledForConnection = true)

        val working = profile("w1", subId = "s2", health = HealthStatus.WORKING, latencyMs = 35, lastSuccessAt = now - 10_000L)
        val untestedS1 = (1..5).map { profile("u1-$it", subId = "s1") }
        val untestedS2 = (1..5).map { profile("u2-$it", subId = "s2") }
        val expiredCooldown = profile(
            "f-exp", subId = "s1", health = HealthStatus.FAILED,
            lastAttemptAt = now - 120_000L, consecutiveFailures = 1, cooldownUntilMs = now - 1_000L
        )
        val activeCooldown = profile(
            "f-cool", subId = "s1", health = HealthStatus.FAILED,
            lastAttemptAt = now - 5_000L, consecutiveFailures = 3, cooldownUntilMs = now + 600_000L
        )

        val plan = AutomaticServerSelector.buildPlan(
            allProfiles = untestedS1 + listOf(activeCooldown, expiredCooldown, working) + untestedS2,
            subscriptions = listOf(sub1, sub2),
            manualConfigsEnabled = true,
            selectedCountryCode = null,
            previousActiveId = null,
            nowMs = now
        ) as AutomaticServerSelector.SelectionPlan.Candidates

        // Tier 1 working comes first
        assertEquals("w1", plan.ordered.first().id)
        // Tier 2 untested is interleaved between s1 and s2
        val tier2Ids = plan.ordered.drop(1).take(10).map { it.id }
        assertTrue(tier2Ids.take(4).any { it.startsWith("u1-") } && tier2Ids.take(4).any { it.startsWith("u2-") })
        // Tier 3 cooldown-expired comes before Tier 4 active-cooldown
        assertEquals("f-exp", plan.ordered[11].id)
        assertEquals("f-cool", plan.ordered[12].id)
    }

    @Test
    fun sourceTogglesPersistAndDoNotInterruptActiveConnection() = runBlocking {
        val sub = SubscriptionEntity(id = "s1", name = "Sub 1", url = "https://a.example", isEnabledForConnection = true)
        val pSub = profile("p-sub", "🇩🇪 Sub", subId = "s1")
        val pManual = profile("p-man", "🇺🇸 Manual", subId = null)
        withContext(Dispatchers.IO) {
            repository.saveSubscription(sub)
            repository.saveProfiles(listOf(pSub, pManual))
        }

        // Simulate active connection on pSub
        app.tunnelManager.connectCandidates(listOf(pSub))
        assertEquals(ConnectionState.CONNECTING, app.tunnelManager.telemetry.value.connectionState)

        val vm = LuxyVpnViewModel(app)
        awaitMainUntil { vm.connectionSources.value.size == 2 }

        // Disable both sources while connecting/connected -> must NOT disconnect!
        vm.setSubscriptionEnabledForConnection("s1", false)
        vm.setManualConfigsEnabledForConnection(false)
        awaitMainUntil {
            vm.connectionSources.value.none { it.isEnabledForConnection } &&
                vm.sourceChangePendingReconnect.value
        }
        assertEquals(ConnectionState.CONNECTING, app.tunnelManager.telemetry.value.connectionState)

        // Persistence check across fresh DataStore/Repository
        val freshRepo = LuxyVpnRepository(database, SettingsDataStore(app))
        assertFalse(freshRepo.getSettings().manualConfigsEnabledForConnection)
        assertFalse(freshRepo.getAllSubscriptions().single().isEnabledForConnection)

        // After disconnect, new connection attempt is prevented when all sources are disabled
        withContext(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("done") }
        vm.toggleConnect()
        awaitMainUntil { vm.uiNotice.value != null }
        assertTrue(vm.uiNotice.value!!.contains("All connection sources are disabled"))
        assertEquals(ConnectionState.DISCONNECTED, app.tunnelManager.telemetry.value.connectionState)
    }

    @Test
    fun bulkDeleteCreatesBackupAndRestoresLosslesslyAndFailedRetestNeverDeletes() = runBlocking {
        val p1 = profile("m1", "Manual 1")
        val p2 = profile("m2", "Manual 2")
        withContext(Dispatchers.IO) { repository.saveProfiles(listOf(p1, p2)) }

        val fakeProbe = object : ServerProbe {
            override suspend fun probe(profile: ServerProfileEntity, verifyExitCountry: Boolean): ProbeOutcome {
                return if (profile.id == "m1") {
                    ProbeOutcome(success = true, latencyMs = 25, exitIp = "198.51.100.9", verifiedCountryCode = "DE")
                } else {
                    ProbeOutcome(success = false, errorCategory = ErrorCategory.TIMEOUT, errorMessage = "Timed out")
                }
            }
        }
        val vm = LuxyVpnViewModel(app, fakeProbe)
        vm.retestProfiles(listOf("m1", "m2"))
        awaitMainUntil { !vm.isRetesting.value && vm.allProfiles.value.any { it.id == "m1" && it.healthStatus == HealthStatus.WORKING.name } }

        val afterTest = withContext(Dispatchers.IO) { repository.getAllProfiles().associateBy { it.id } }
        assertEquals(2, afterTest.size) // Failed test NEVER deletes m2!
        assertEquals(HealthStatus.WORKING.name, afterTest.getValue("m1").healthStatus)
        assertEquals("DE", afterTest.getValue("m1").countryCode)
        assertEquals(CountryConfidence.VERIFIED.name, afterTest.getValue("m1").countryConfidence)
        assertEquals(HealthStatus.FAILED.name, afterTest.getValue("m2").healthStatus)
        assertEquals(ErrorCategory.TIMEOUT.name, afterTest.getValue("m2").lastErrorCategory)

        // Bulk delete with backup + restore
        vm.deleteManualProfiles(listOf("m1", "m2"), "Test delete")
        awaitMainUntil { vm.allProfiles.value.isEmpty() && vm.lastDeletedBatch.value != null }
        val batchId = vm.lastDeletedBatch.value!!.batchId

        vm.restoreBackupBatch(batchId)
        awaitMainUntil { vm.allProfiles.value.size == 2 }
        val restored = withContext(Dispatchers.IO) { repository.getAllProfiles().associateBy { it.id } }
        assertEquals("DE", restored.getValue("m1").countryCode)
        assertEquals(HealthStatus.FAILED.name, restored.getValue("m2").healthStatus)
    }

    @Test
    fun automaticSelectionWith500BrokenServersConnectsQuicklyToWorkingServer() = runBlocking {
        val broken = (1..500).map { i ->
            profile("dead-$i", "Dead $i", health = HealthStatus.FAILED, consecutiveFailures = 2, cooldownUntilMs = System.currentTimeMillis() + 600_000L)
        }
        val working = profile("live-1", "Live 1", health = HealthStatus.WORKING, latencyMs = 18, lastSuccessAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { repository.saveProfiles(broken + working) }

        val vm = LuxyVpnViewModel(app)
        Shadows.shadowOf(app).clearStartedServices()
        vm.toggleConnect()
        awaitMainUntil { !vm.isSelectingFastestServer.value && app.tunnelManager.telemetry.value.connectionState == ConnectionState.CONNECTING }
        assertEquals("live-1", app.tunnelManager.telemetry.value.activeNode?.id)
    }

    @Test
    fun healthStalenessInvalidatesOnNetworkChangeAndConfigEdit() {
        val now = 1_000_000_000L
        val working = profile("w", health = HealthStatus.WORKING, lastSuccessAt = now - 1_000L)
            .copy(lastNetworkFingerprint = "wifi:1")
        assertEquals(EffectiveHealthState.WORKING, HealthPolicy.effectiveHealthState(working, now, "wifi:1"))
        assertEquals(EffectiveHealthState.UNTESTED, HealthPolicy.effectiveHealthState(working, now, "cellular:2"))
        assertEquals(EffectiveHealthState.UNTESTED, HealthPolicy.effectiveHealthState(working, now + HealthPolicy.WORKING_STALE_MS + 1, "wifi:1"))

        val edited = ConfigurationHealthTracker.invalidateOnConfigEdit(
            working.copy(
                port = 8443,
                countryCode = "DE",
                countryConfidence = CountryConfidence.VERIFIED.name
            )
        )
        assertEquals(HealthStatus.UNTESTED.name, edited.healthStatus)
        assertNull(edited.lastSuccessAt)
        assertEquals(CountryConfidence.UNKNOWN.name, edited.countryConfidence)
    }

    @Test
    fun connectivityOnlySuccessPreservesPreviouslyVerifiedCountryMetadata() {
        val verifiedAt = 1_000_000_000L
        val verified = profile("verified").copy(
            countryCode = "DE",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countrySource = CountrySource.EXIT_VERIFIED.name,
            countryVerifiedAt = verifiedAt,
            exitIp = "203.0.113.10"
        )

        val recorded = ConfigurationHealthTracker.recordSuccess(
            profile = verified,
            latencyMs = 42,
            networkFingerprint = "wifi:1",
            nowMs = verifiedAt + 1
        )

        assertEquals("DE", recorded.countryCode)
        assertEquals(CountryConfidence.VERIFIED.name, recorded.countryConfidence)
        assertEquals(CountrySource.EXIT_VERIFIED.name, recorded.countrySource)
        assertEquals(verifiedAt, recorded.countryVerifiedAt)
        assertEquals("203.0.113.10", recorded.exitIp)
    }

    @Test
    fun probeBatchPolicySuppressesOnlyTransientDominatedBatches() {
        fun suppress(vararg categories: ErrorCategory) =
            ProbeBatchPolicy.shouldSuppressHealthStamp(categories.toList())

        assertFalse(suppress())
        assertFalse("Fewer than four probes is never a network-wide verdict", suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT
        ))
        assertTrue(suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT,
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT
        ))
        assertTrue(suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.DNS_FAILURE,
            ErrorCategory.NO_INTERNET, ErrorCategory.TIMEOUT
        ))
        assertFalse("75 percent transient is below the threshold", suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT,
            ErrorCategory.TIMEOUT, ErrorCategory.INVALID_CONFIG
        ))
        assertFalse("Successes count toward the completed batch", suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.NONE, ErrorCategory.NONE, ErrorCategory.NONE
        ))
        assertTrue("Exactly 80 percent transient is suppressed", suppress(
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT,
            ErrorCategory.TIMEOUT, ErrorCategory.TIMEOUT, ErrorCategory.NONE
        ))
        assertFalse("Inconclusive exit verification alone is not a batch timeout signal", suppress(
            ErrorCategory.EXIT_VERIFICATION_FAILED, ErrorCategory.EXIT_VERIFICATION_FAILED,
            ErrorCategory.EXIT_VERIFICATION_FAILED, ErrorCategory.EXIT_VERIFICATION_FAILED
        ))
        assertFalse(suppress(
            ErrorCategory.INVALID_CONFIG, ErrorCategory.INVALID_CONFIG,
            ErrorCategory.INVALID_CONFIG, ErrorCategory.INVALID_CONFIG
        ))
    }

    @Test
    fun bulkRetestSuppressesTransientNetworkWideFailures() = runBlocking {
        val profiles = (1..4).map { i ->
            profile("transient-$i", "Transient $i", health = HealthStatus.WORKING, lastSuccessAt = 1_000L)
        }
        withContext(Dispatchers.IO) { repository.saveProfiles(profiles) }

        val fakeProbe = object : ServerProbe {
            override suspend fun probe(profile: ServerProfileEntity, verifyExitCountry: Boolean) =
                ProbeOutcome(success = false, errorCategory = ErrorCategory.TIMEOUT, errorMessage = "Timed out")
        }
        val vm = LuxyVpnViewModel(app, fakeProbe)
        vm.retestProfiles(profiles.map { it.id })
        awaitMainUntil { !vm.isRetesting.value && vm.retestProgress.value == null }

        val stored = withContext(Dispatchers.IO) { repository.getAllProfiles().associateBy { it.id } }
        assertEquals(4, stored.size)
        stored.values.forEach { storedProfile ->
            assertEquals(HealthStatus.WORKING.name, storedProfile.healthStatus)
            assertEquals(0, storedProfile.consecutiveFailures)
            assertEquals(ErrorCategory.NONE.name, storedProfile.lastErrorCategory)
            assertEquals(1_000L, storedProfile.lastSuccessAt)
        }
    }

    @Test
    fun bulkRetestStillRecordsAnIsolatedFailure() = runBlocking {
        val profiles = (1..4).map { i -> profile("mixed-$i", "Mixed $i") }
        withContext(Dispatchers.IO) { repository.saveProfiles(profiles) }

        val fakeProbe = object : ServerProbe {
            override suspend fun probe(profile: ServerProfileEntity, verifyExitCountry: Boolean) =
                if (profile.id == "mixed-4") {
                    ProbeOutcome(success = false, errorCategory = ErrorCategory.TIMEOUT, errorMessage = "Timed out")
                } else {
                    ProbeOutcome(success = true, latencyMs = 20, verifiedCountryCode = "DE")
                }
        }
        val vm = LuxyVpnViewModel(app, fakeProbe)
        vm.retestProfiles(profiles.map { it.id })
        awaitMainUntil { !vm.isRetesting.value && vm.retestProgress.value == null }

        val stored = withContext(Dispatchers.IO) { repository.getAllProfiles().associateBy { it.id } }
        assertEquals(HealthStatus.WORKING.name, stored.getValue("mixed-1").healthStatus)
        assertEquals(HealthStatus.WORKING.name, stored.getValue("mixed-3").healthStatus)
        assertEquals(HealthStatus.FAILED.name, stored.getValue("mixed-4").healthStatus)
        assertEquals(ErrorCategory.TIMEOUT.name, stored.getValue("mixed-4").lastErrorCategory)
        assertEquals(1, stored.getValue("mixed-4").consecutiveFailures)
    }
}
