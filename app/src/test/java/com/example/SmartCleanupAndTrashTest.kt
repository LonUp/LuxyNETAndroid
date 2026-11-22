package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.SmartCleanupAnalyzer
import com.example.data.local.DeletedProfileBackupEntity
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import com.example.data.model.ServerProfileCodec
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SmartCleanupAndTrashTest {

    private val now = 1_000_000_000_000L

    private lateinit var context: Application
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LuxyVpnRepository(database, SettingsDataStore(context))
        repository.updateSettings { StabilitySettings() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun profile(
        id: String,
        name: String = id,
        subId: String? = null,
        sourceType: SourceType = if (subId != null) SourceType.SUBSCRIPTION else SourceType.MANUAL,
        enabled: Boolean = true,
        health: HealthStatus = HealthStatus.UNTESTED,
        preferred: Boolean = false,
        latencyMs: Int? = null,
        lastSuccessAt: Long? = null,
        lastAttemptAt: Long? = null,
        consecutiveFailures: Int = 0,
        errorCategory: ErrorCategory = ErrorCategory.NONE,
        cooldownUntilMs: Long = 0L,
        disabledAt: Long? = null,
        createdAt: Long = 1_000L,
        countryCode: String? = null,
        confidence: CountryConfidence = CountryConfidence.UNKNOWN,
        address: String = "$id.example.com",
        path: String = "",
        allowInsecure: Boolean = false
    ) = ServerProfileEntity(
        id = id,
        name = name,
        address = address,
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        path = path,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        allowInsecure = allowInsecure,
        subscriptionId = subId,
        sourceType = sourceType.name,
        isPinned = preferred,
        isEnabled = enabled,
        healthStatus = health.name,
        latencyMs = latencyMs,
        lastSuccessAt = lastSuccessAt,
        lastAttemptAt = lastAttemptAt,
        consecutiveFailures = consecutiveFailures,
        lastErrorCategory = errorCategory.name,
        cooldownUntilMs = cooldownUntilMs,
        disabledAt = disabledAt,
        createdAt = createdAt,
        countryCode = countryCode,
        countryConfidence = confidence.name
    )

    private fun analyze(
        profiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity> = emptyList(),
        activeNodeId: String? = null,
        networkFingerprint: String? = null,
        dismissedKeys: Set<String> = emptySet()
    ) = SmartCleanupAnalyzer.analyze(
        allProfiles = profiles,
        subscriptions = subscriptions,
        activeNodeId = activeNodeId,
        currentNetworkFingerprint = networkFingerprint,
        nowMs = now,
        dismissedKeys = dismissedKeys
    )

    private fun idsOf(plan: com.example.data.model.SmartCleanupPlan, category: SmartCleanupCategory) =
        plan.groups.firstOrNull { it.category == category }?.items?.map { it.profile.id }.orEmpty()

    @Test
    fun dismissedRepeatedFailureSuggestionsResurfaceWhenFailureCountChanges() {
        val failing = profile(
            "failing",
            consecutiveFailures = 3,
            errorCategory = ErrorCategory.AUTH_OR_PROTOCOL
        )
        val initialPlan = analyze(listOf(failing))
        val item = initialPlan.groups.single().items.single()
        val key = SmartCleanupAnalyzer.dismissalKey(item)

        assertEquals("REPEATED_FAILURES:failing::3:0", key)
        assertEquals(0, analyze(listOf(failing), dismissedKeys = setOf(key)).totalSuggested)

        val recoveredState = failing.copy(consecutiveFailures = 4)
        val updatedPlan = analyze(listOf(recoveredState), dismissedKeys = setOf(key))
        assertEquals(1, updatedPlan.totalSuggested)
        assertEquals(4, updatedPlan.groups.single().items.single().profile.consecutiveFailures)
    }

    // ------------------------------------------------------------------------------------
    // DUPLICATES
    // ------------------------------------------------------------------------------------

    @Test
    fun genuineManualDuplicatesAreGroupedAndKeepTheBestKeeper() {
        val default = profile("m1", createdAt = 1_000L, address = "shared.example.com")
        val preferred = profile("m2", preferred = true, createdAt = 2_000L, address = "shared.example.com")
        val other = profile("m3", createdAt = 3_000L, address = "shared.example.com")

        val plan = analyze(listOf(default, preferred, other))
        val group = plan.groups.single { it.category == SmartCleanupCategory.DUPLICATES }

        assertEquals(setOf("m1", "m3"), group.items.map { it.profile.id }.toSet())
        assertTrue(group.items.all { it.keeperId == "m2" })
        assertTrue(group.defaultSelected)
        assertEquals(2, plan.totalSuggested)
    }

    @Test
    fun duplicatesAreScopedPerSourceAndNeverMergedAcrossSubscriptionsOrManual() {
        val subA = SubscriptionEntity(id = "a", name = "A", url = "https://a.example")
        val subB = SubscriptionEntity(id = "b", name = "B", url = "https://b.example")
        val a1 = profile("a1", subId = "a", address = "shared.example.com")
        val a2 = profile("a2", subId = "a", address = "shared.example.com")
        val b1 = profile("b1", subId = "b", address = "shared.example.com")
        val m1 = profile("m1", address = "shared.example.com")

        val plan = analyze(listOf(a1, a2, b1, m1), listOf(subA, subB))
        val duplicates = idsOf(plan, SmartCleanupCategory.DUPLICATES)

        assertEquals(1, duplicates.size)
        assertEquals("a2", duplicates.single())
        assertTrue(plan.groups.flatMap { it.items }.none { it.profile.id == "b1" })
        assertTrue(plan.groups.flatMap { it.items }.none { it.profile.id == "m1" })
    }

    @Test
    fun meaningfulTransportAndTlsDifferencesAreNotDuplicates() {
        val tcp = profile("tcp", address = "shared.example.com")
        val ws = profile("ws", address = "shared.example.com").copy(transport = "WS", path = "/ws")
        val otherSni = profile("sni", address = "shared.example.com").copy(sni = "other.example.com")

        val plan = analyze(listOf(tcp, ws, otherSni))

        assertTrue(idsOf(plan, SmartCleanupCategory.DUPLICATES).isEmpty())
    }

    @Test
    fun activeNodeIsNeverSuggestedAndBecomesTheDuplicateKeeper() {
        val active = profile("active", createdAt = 5_000L, address = "shared.example.com")
        val preferred = profile("preferred", preferred = true, createdAt = 1_000L, address = "shared.example.com")

        val plan = analyze(listOf(active, preferred), activeNodeId = "active")
        val group = plan.groups.single { it.category == SmartCleanupCategory.DUPLICATES }

        assertEquals(listOf("preferred"), group.items.map { it.profile.id })
        assertEquals("active", group.items.single().keeperId)
    }

    @Test
    fun activeNodeIsExcludedFromEveryCategory() {
        val active = profile(
            "active",
            health = HealthStatus.INVALID,
            enabled = false,
            disabledAt = now - HealthPolicy.EXTENDED_DISABLED_MS - 1,
            consecutiveFailures = 10,
            errorCategory = ErrorCategory.AUTH_OR_PROTOCOL,
            sourceType = SourceType.LEGACY_UNCLASSIFIED
        )
        val plan = analyze(listOf(active), activeNodeId = "active")

        assertEquals(0, plan.totalSuggested)
        assertTrue(plan.groups.isEmpty())
    }

    // ------------------------------------------------------------------------------------
    // INVALID / ORPHANED
    // ------------------------------------------------------------------------------------

    @Test
    fun invalidConfigurationsAreSelectedByDefaultAndOutrankOtherCategories() {
        val unsupported = profile("unsupported", address = "has space")
        val markedInvalid = profile("marked", health = HealthStatus.INVALID)
        val failing = profile(
            "failing", health = HealthStatus.INVALID, consecutiveFailures = 8,
            errorCategory = ErrorCategory.AUTH_OR_PROTOCOL
        )

        val plan = analyze(listOf(unsupported, markedInvalid, failing))
        val invalid = idsOf(plan, SmartCleanupCategory.INVALID)
        val group = plan.groups.single { it.category == SmartCleanupCategory.INVALID }

        assertEquals(setOf("unsupported", "marked", "failing"), invalid.toSet())
        assertTrue(group.defaultSelected)
        // INVALID outranks REPEATED_FAILURES, so the failing profile is not double-counted there.
        assertTrue(idsOf(plan, SmartCleanupCategory.REPEATED_FAILURES).isEmpty())
        assertEquals(3, plan.totalSuggested)
    }

    @Test
    fun orphanedConfigurationsCoverLegacyAndMissingSubscriptionsOnly() {
        val legacy = profile("legacy", sourceType = SourceType.LEGACY_UNCLASSIFIED)
        val missing = profile("missing", subId = "gone")
        val existing = profile("existing", subId = "present")
        val subs = listOf(SubscriptionEntity(id = "present", name = "Present", url = "https://p.example"))

        val plan = analyze(listOf(legacy, missing, existing), subs, activeNodeId = null)
        val orphaned = idsOf(plan, SmartCleanupCategory.ORPHANED).toSet()
        val group = plan.groups.single { it.category == SmartCleanupCategory.ORPHANED }

        assertEquals(setOf("legacy", "missing"), orphaned)
        assertFalse(group.defaultSelected)
    }

    // ------------------------------------------------------------------------------------
    // REPEATED_FAILURES + transient failure protection
    // ------------------------------------------------------------------------------------

    @Test
    fun permanentFailureCategoriesAreSuggestedButNeverSelectedByDefault() {
        val auth = profile(
            "auth", health = HealthStatus.FAILED, consecutiveFailures = 3,
            errorCategory = ErrorCategory.AUTH_OR_PROTOCOL, lastAttemptAt = now - 60_000L
        )
        val tls = profile(
            "tls", health = HealthStatus.FAILED, consecutiveFailures = 3,
            errorCategory = ErrorCategory.TLS_OR_HANDSHAKE, lastAttemptAt = now - 60_000L
        )
        val refused = profile(
            "refused", health = HealthStatus.FAILED, consecutiveFailures = 3,
            errorCategory = ErrorCategory.CONNECTION_REFUSED, lastAttemptAt = now - 60_000L
        )

        val plan = analyze(listOf(auth, tls, refused))
        val group = plan.groups.single { it.category == SmartCleanupCategory.REPEATED_FAILURES }

        assertEquals(setOf("auth", "tls", "refused"), group.items.map { it.profile.id }.toSet())
        assertFalse(group.defaultSelected)
    }

    @Test
    fun temporaryTimeoutsAreNeverTreatedAsPermanentFailures() {
        val shortTimeout = profile(
            "short-timeout", health = HealthStatus.FAILED, consecutiveFailures = 3,
            errorCategory = ErrorCategory.TIMEOUT, lastAttemptAt = now - 60_000L
        )
        val recentSuccess = profile(
            "recent-success", health = HealthStatus.FAILED, consecutiveFailures = 6,
            errorCategory = ErrorCategory.TIMEOUT, lastSuccessAt = now - 60L * 60L * 1_000L
        )

        val plan = analyze(listOf(shortTimeout, recentSuccess))

        assertTrue(idsOf(plan, SmartCleanupCategory.REPEATED_FAILURES).isEmpty())
    }

    @Test
    fun longTimeoutStreakWithoutRecentSuccessIsNeverSuggestedForCleanup() {
        val longTimeout = profile(
            "long-timeout", health = HealthStatus.FAILED, consecutiveFailures = 7,
            errorCategory = ErrorCategory.TIMEOUT,
            lastSuccessAt = now - 5L * 24L * 60L * 60L * 1_000L,
            lastAttemptAt = now - 60_000L
        )

        val plan = analyze(listOf(longTimeout))

        assertTrue(idsOf(plan, SmartCleanupCategory.REPEATED_FAILURES).isEmpty())
        assertEquals(0, plan.totalSuggested)
    }

    @Test
    fun transientFailureCategoriesNeverSuggestCleanupRegardlessOfStreakLength() {
        val staleSuccess = now - 5L * 24L * 60L * 60L * 1_000L
        val dns = profile(
            "dns", health = HealthStatus.FAILED, consecutiveFailures = 7,
            errorCategory = ErrorCategory.DNS_FAILURE,
            lastSuccessAt = staleSuccess, lastAttemptAt = now - 60_000L
        )
        val offline = profile(
            "offline", health = HealthStatus.FAILED, consecutiveFailures = 7,
            errorCategory = ErrorCategory.NO_INTERNET,
            lastSuccessAt = staleSuccess, lastAttemptAt = now - 60_000L
        )
        val unverified = profile(
            "unverified", health = HealthStatus.FAILED, consecutiveFailures = 7,
            errorCategory = ErrorCategory.EXIT_VERIFICATION_FAILED,
            lastSuccessAt = staleSuccess, lastAttemptAt = now - 60_000L
        )

        val plan = analyze(listOf(dns, offline, unverified))

        assertTrue(idsOf(plan, SmartCleanupCategory.REPEATED_FAILURES).isEmpty())
        assertEquals(0, plan.totalSuggested)
    }

    // ------------------------------------------------------------------------------------
    // EXTENDED_DISABLED
    // ------------------------------------------------------------------------------------

    @Test
    fun extendedDisabledRequiresThirtyDaysAndADisableTimestamp() {
        val old = profile("old", enabled = false, disabledAt = now - HealthPolicy.EXTENDED_DISABLED_MS - 1)
        val recent = profile("recent", enabled = false, disabledAt = now - 5L * 24L * 60L * 60L * 1_000L)
        val noTimestamp = profile("no-timestamp", enabled = false)

        val plan = analyze(listOf(old, recent, noTimestamp))
        val group = plan.groups.single { it.category == SmartCleanupCategory.EXTENDED_DISABLED }

        assertEquals(listOf("old"), group.items.map { it.profile.id })
        assertFalse(group.defaultSelected)
    }

    // ------------------------------------------------------------------------------------
    // STALE_HEALTH
    // ------------------------------------------------------------------------------------

    @Test
    fun staleHealthRequiresStaleStatusAndFourteenDaysWithoutSuccess() {
        val stale = profile(
            "stale", health = HealthStatus.WORKING,
            lastSuccessAt = now - 20L * 24L * 60L * 60L * 1_000L
        )
        val fresh = profile(
            "fresh", health = HealthStatus.WORKING,
            lastSuccessAt = now - 60L * 60L * 1_000L
        )

        val plan = analyze(listOf(stale, fresh))
        val group = plan.groups.single { it.category == SmartCleanupCategory.STALE_HEALTH }

        assertEquals(listOf("stale"), group.items.map { it.profile.id })
        assertFalse(group.defaultSelected)
    }

    @Test
    fun recentHealthOnAChangedNetworkIsNotTreatedAsCleanupStale() {
        val recent = profile(
            "recent-network", health = HealthStatus.WORKING,
            lastSuccessAt = now - 60L * 60L * 1_000L
        ).copy(lastNetworkFingerprint = "old-network")

        val plan = analyze(listOf(recent), networkFingerprint = "new-network")

        assertTrue(idsOf(plan, SmartCleanupCategory.STALE_HEALTH).isEmpty())
        assertEquals(0, plan.totalSuggested)
    }

    @Test
    fun everyProfileAppearsInExactlyOneCategoryWithExactCounts() {
        val duplicateA = profile("dup-a", subId = "s1", address = "shared.example.com")
        val duplicateB = profile("dup-b", subId = "s1", address = "shared.example.com")
        val invalid = profile("invalid", address = "bad host")
        val legacy = profile("legacy", sourceType = SourceType.LEGACY_UNCLASSIFIED)
        val failing = profile(
            "failing", health = HealthStatus.FAILED, consecutiveFailures = 4,
            errorCategory = ErrorCategory.AUTH_OR_PROTOCOL, lastAttemptAt = now - 60_000L
        )
        val disabled = profile("disabled", enabled = false, disabledAt = now - HealthPolicy.EXTENDED_DISABLED_MS - 1)
        val stale = profile("stale", health = HealthStatus.WORKING, lastSuccessAt = now - 20L * 24L * 60L * 60L * 1_000L)
        val healthy = profile("healthy", latencyMs = 20)

        val plan = analyze(
            listOf(duplicateA, duplicateB, invalid, legacy, failing, disabled, stale, healthy),
            listOf(SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example"))
        )

        val allSuggested = plan.groups.flatMap { it.items }
        assertEquals(allSuggested.size, allSuggested.map { it.profile.id }.distinct().size)
        assertEquals(allSuggested.size, plan.totalSuggested)
        assertTrue(allSuggested.none { it.profile.id == "healthy" })
        assertEquals(1, idsOf(plan, SmartCleanupCategory.DUPLICATES).size)
        assertEquals(1, idsOf(plan, SmartCleanupCategory.INVALID).size)
        assertEquals(1, idsOf(plan, SmartCleanupCategory.ORPHANED).size)
        assertEquals(1, idsOf(plan, SmartCleanupCategory.REPEATED_FAILURES).size)
        assertEquals(1, idsOf(plan, SmartCleanupCategory.EXTENDED_DISABLED).size)
        assertEquals(1, idsOf(plan, SmartCleanupCategory.STALE_HEALTH).size)
    }

    // ------------------------------------------------------------------------------------
    // Repository: enable/disable timestamps + preference delegation
    // ------------------------------------------------------------------------------------

    @Test
    fun enablingAndDisablingProfilesMaintainsDisabledAt() = runBlocking {
        val p = profile("p1")
        database.serverProfileDao().upsert(p)

        val before = System.currentTimeMillis()
        repository.setProfileEnabled("p1", false)
        val disabled = database.serverProfileDao().getById("p1")!!
        assertFalse(disabled.isEnabled)
        assertNotNull(disabled.disabledAt)
        assertTrue(disabled.disabledAt!! >= before)

        repository.setProfileEnabled("p1", true)
        val enabled = database.serverProfileDao().getById("p1")!!
        assertTrue(enabled.isEnabled)
        assertNull(enabled.disabledAt)
    }

    @Test
    fun bulkEnableDisableMaintainsDisabledAt() = runBlocking {
        database.serverProfileDao().upsertAll(listOf(profile("p1"), profile("p2")))

        repository.setProfilesEnabled(listOf("p1", "p2"), false)
        val disabled = database.serverProfileDao().getAll()
        assertTrue(disabled.all { !it.isEnabled && it.disabledAt != null })

        repository.setProfilesEnabled(listOf("p1", "p2"), true)
        assertTrue(database.serverProfileDao().getAll().all { it.isEnabled && it.disabledAt == null })
    }

    @Test
    fun setPreferredDelegatesToPinned() = runBlocking {
        database.serverProfileDao().upsert(profile("p1"))

        repository.setPreferred("p1", true)
        assertTrue(database.serverProfileDao().getById("p1")!!.isPinned)

        repository.setPreferred("p1", false)
        assertFalse(database.serverProfileDao().getById("p1")!!.isPinned)
    }

    // ------------------------------------------------------------------------------------
    // Repository: Trash
    // ------------------------------------------------------------------------------------

    @Test
    fun moveProfilesToTrashStoresFullMetadataAndExcludesActiveNode() = runBlocking {
        val sub = SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example")
        database.subscriptionDao().upsert(sub)
        val active = profile("active", name = "Active")
        val subNode = profile("sub-node", name = "Sub Node", subId = "s1")
        database.serverProfileDao().upsertAll(listOf(active, subNode))
        repository.updateSettings { it.copy(activeProfileId = "active") }

        val items = listOf(
            SmartCleanupItem(active, SmartCleanupCategory.STALE_HEALTH, "stale"),
            SmartCleanupItem(subNode, SmartCleanupCategory.REPEATED_FAILURES, "failed often")
        )
        val batch = repository.moveProfilesToTrash(items, "Smart cleanup", activeNodeId = "active")

        assertNotNull(batch)
        assertEquals(1, batch!!.count)
        assertEquals(listOf("active"), database.serverProfileDao().getAll().map { it.id })
        assertEquals("active", repository.getSettings().activeProfileId)

        val row = database.deletedProfileBackupDao().getAll().single()
        assertEquals(batch!!.batchId, row.batchId)
        assertEquals("sub-node", row.profileId)
        assertEquals(SmartCleanupCategory.REPEATED_FAILURES.name, row.category)
        assertEquals("failed often", row.reason)
        assertEquals("Smart cleanup", row.label)
        assertEquals(SourceType.SUBSCRIPTION.name, row.sourceType)
        assertEquals("s1", row.subscriptionId)
        assertEquals(row.deletedAt + HealthPolicy.TRASH_RETENTION_MS, row.expiresAt)

        val decoded = ServerProfileCodec.decode(row.payloadJson)!!
        assertEquals("sub-node", decoded.id)
        assertEquals("Sub Node", decoded.name)
        assertEquals("s1", decoded.subscriptionId)
    }

    @Test
    fun movingTheSettingsActiveProfileClearsActiveProfileId() = runBlocking {
        val p = profile("p1")
        database.serverProfileDao().upsert(p)
        repository.updateSettings { it.copy(activeProfileId = "p1") }

        val batch = repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(p, SmartCleanupCategory.DUPLICATES, "duplicate")),
            "Smart cleanup"
        )

        assertEquals(1, batch!!.count)
        assertNull(repository.getSettings().activeProfileId)
        assertTrue(database.serverProfileDao().getAll().isEmpty())
    }

    @Test
    fun trashEntriesFlowExposesDecodedEntries() = runBlocking {
        val p = profile("p1", name = "Preferred Node", preferred = true, subId = "s1")
        database.serverProfileDao().upsert(p)

        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(p, SmartCleanupCategory.ORPHANED, "missing subscription")),
            "Smart cleanup"
        )

        val entry = repository.trashEntriesFlow.first().single()
        assertEquals("p1", entry.profileId)
        assertEquals("Preferred Node", entry.profileName)
        assertEquals("p1", entry.profile?.id)
        assertTrue(entry.profile!!.isPinned)
        assertEquals(SmartCleanupCategory.ORPHANED.name, entry.category)
        assertEquals("missing subscription", entry.reason)
        assertEquals("s1", entry.subscriptionId)
        assertEquals(SourceType.SUBSCRIPTION.name, entry.sourceType)
        assertTrue(entry.expiresAt > 0L)
    }

    @Test
    fun restoreTrashEntriesPreservesIdentityOwnershipAndPreferences() = runBlocking {
        database.subscriptionDao().upsert(SubscriptionEntity(id = "s1", name = "S1", url = "https://s1.example"))
        val original = profile(
            "p1", name = "Keep Me", subId = "s1", preferred = true,
            enabled = false, disabledAt = now, health = HealthStatus.WORKING,
            latencyMs = 42, countryCode = "DE", confidence = CountryConfidence.VERIFIED
        )
        database.serverProfileDao().upsert(original)
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(original, SmartCleanupCategory.EXTENDED_DISABLED, "old")),
            "Smart cleanup"
        )
        val entry = database.deletedProfileBackupDao().getAll().single()

        val restored = repository.restoreTrashEntries(listOf(entry.id))

        assertEquals(1, restored)
        val restoredProfile = database.serverProfileDao().getById("p1")!!
        assertEquals("Keep Me", restoredProfile.name)
        assertEquals("s1", restoredProfile.subscriptionId)
        assertEquals(SourceType.SUBSCRIPTION.name, restoredProfile.sourceType)
        assertTrue(restoredProfile.isPinned)
        assertFalse(restoredProfile.isEnabled)
        assertEquals(now, restoredProfile.disabledAt)
        assertEquals(HealthStatus.WORKING.name, restoredProfile.healthStatus)
        assertEquals(42, restoredProfile.latencyMs)
        assertEquals(CountryConfidence.VERIFIED.name, restoredProfile.countryConfidence)
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
    }

    @Test
    fun restoreSkipsDuplicateInsertionInTheSameScopeButClearsTheTrashRow() = runBlocking {
        val trashed = profile("p1", address = "shared.example.com")
        database.serverProfileDao().upsert(trashed)
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(trashed, SmartCleanupCategory.DUPLICATES, "duplicate")),
            "Smart cleanup"
        )
        val entry = database.deletedProfileBackupDao().getAll().single()
        val existingDuplicate = profile("p2", address = "shared.example.com")
        database.serverProfileDao().upsert(existingDuplicate)

        val restored = repository.restoreTrashEntries(listOf(entry.id))

        assertEquals(0, restored)
        assertEquals(listOf("p2"), database.serverProfileDao().getAll().map { it.id })
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
    }

    @Test
    fun restoreAllowsIdenticalFingerprintFromAnotherScope() = runBlocking {
        val trashed = profile("p1", subId = "s1", address = "shared.example.com")
        database.serverProfileDao().upsert(trashed)
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(trashed, SmartCleanupCategory.DUPLICATES, "duplicate")),
            "Smart cleanup"
        )
        val entry = database.deletedProfileBackupDao().getAll().single()
        database.serverProfileDao().upsert(profile("other-scope", subId = "s2", address = "shared.example.com"))

        val restored = repository.restoreTrashEntries(listOf(entry.id))

        assertEquals(1, restored)
        assertNotNull(database.serverProfileDao().getById("p1"))
        assertNotNull(database.serverProfileDao().getById("other-scope"))
    }

    @Test
    fun permanentlyDeleteTrashEntriesRemovesRows() = runBlocking {
        val p = profile("p1")
        database.serverProfileDao().upsert(p)
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(p, SmartCleanupCategory.INVALID, "bad")),
            "Smart cleanup"
        )
        val entry = database.deletedProfileBackupDao().getAll().single()

        assertEquals(1, repository.permanentlyDeleteTrashEntries(listOf(entry.id)))
        assertEquals(0, repository.permanentlyDeleteTrashEntries(listOf(entry.id)))
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
        assertNull(database.serverProfileDao().getById("p1"))
    }

    @Test
    fun purgeExpiredTrashDeletesOnlyExpiredRows() = runBlocking {
        val expired = DeletedProfileBackupEntity(
            batchId = "b1",
            deletedAt = now - 1_000L,
            label = "old",
            profileId = "p1",
            payloadJson = ServerProfileCodec.encode(profile("p1")),
            expiresAt = now - 1L
        )
        val retained = DeletedProfileBackupEntity(
            batchId = "b2",
            deletedAt = now,
            label = "new",
            profileId = "p2",
            payloadJson = ServerProfileCodec.encode(profile("p2")),
            expiresAt = now + HealthPolicy.TRASH_RETENTION_MS
        )
        database.deletedProfileBackupDao().insertAll(listOf(expired, retained))

        assertEquals(1, repository.purgeExpiredTrash(now))
        assertEquals(listOf(retained.id), database.deletedProfileBackupDao().getAll().map { it.id })
    }

    @Test
    fun ensureMigratedPurgesExpiredTrashWithoutFailing() = runBlocking {
        val expired = DeletedProfileBackupEntity(
            batchId = "b1",
            deletedAt = 1L,
            label = "old",
            profileId = "p1",
            payloadJson = ServerProfileCodec.encode(profile("p1")),
            expiresAt = 2L
        )
        database.deletedProfileBackupDao().insertAll(listOf(expired))

        repository.ensureMigrated()

        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
    }

    @Test
    fun manualTrashRetentionKeepsMoreThanTenBatchesUntilThirtyDays() = runBlocking {
        repeat(12) { index ->
            val p = profile("retained-$index")
            database.serverProfileDao().upsert(p)
            repository.deleteProfilesWithBackup(listOf(p.id), "Delete $index")
        }

        val rows = database.deletedProfileBackupDao().getAll()
        assertEquals(12, rows.size)
        assertTrue(rows.all { it.expiresAt == it.deletedAt + HealthPolicy.TRASH_RETENTION_MS })
        assertEquals(
            12,
            repository.purgeExpiredTrash(System.currentTimeMillis() + HealthPolicy.TRASH_RETENTION_MS + 1L)
        )
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
    }

    @Test
    fun subscriptionTrashSuppressionAndNodeCountsStayInSyncAcrossRestoreAndRefresh() = runBlocking {
        val sub = SubscriptionEntity(id = "refresh-sub", name = "Refresh", url = "https://refresh.example")
        val node = profile("refresh-node", subId = sub.id, address = "refresh-node.example")
        repository.importSubscription(sub, listOf(node))
        assertEquals(1, database.subscriptionDao().getAll().single().nodeCount)

        val first = repository.getAllProfiles().single()
        val batch = repository.deleteProfilesWithBackup(listOf(first.id), "Delete subscription node")!!
        assertEquals(0, database.subscriptionDao().getAll().single().nodeCount)
        assertEquals(1, repository.restoreBackupBatch(batch.batchId))
        assertEquals(1, database.subscriptionDao().getAll().single().nodeCount)

        val restored = repository.getAllProfiles().single()
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(restored, SmartCleanupCategory.STALE_HEALTH, "removed by user")),
            "Trash subscription node"
        )
        val trashRow = database.deletedProfileBackupDao().getAll().single()
        assertEquals(0, database.subscriptionDao().getAll().single().nodeCount)

        repository.importSubscription(sub, listOf(node.copy(name = "Refreshed name")))
        assertTrue(repository.getAllProfiles().isEmpty())
        assertEquals(0, database.subscriptionDao().getAll().single().nodeCount)

        assertEquals(1, repository.restoreTrashEntries(listOf(trashRow.id)))
        assertEquals(1, database.subscriptionDao().getAll().single().nodeCount)

        val restoredAgain = repository.getAllProfiles().single()
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(restoredAgain, SmartCleanupCategory.STALE_HEALTH, "permanently removed")),
            "Trash subscription node"
        )
        val finalTrashRow = database.deletedProfileBackupDao().getAll().single()
        assertEquals(1, repository.permanentlyDeleteTrashEntries(listOf(finalTrashRow.id)))
        repository.importSubscription(sub, listOf(node))
        assertEquals(1, repository.getAllProfiles().size)
        assertEquals(1, database.subscriptionDao().getAll().single().nodeCount)
    }

    @Test
    fun restoringSubscriptionTrashAfterDeletingItsParentReclassifiesItAsManual() = runBlocking {
        val sub = SubscriptionEntity(id = "orphan-sub", name = "Orphan", url = "https://orphan.example")
        database.subscriptionDao().upsert(sub.copy(nodeCount = 1))
        val original = profile("orphan-node", subId = sub.id)
        database.serverProfileDao().upsert(original)
        repository.moveProfilesToTrash(
            listOf(SmartCleanupItem(original, SmartCleanupCategory.ORPHANED, "parent removed")),
            "Trash orphan"
        )
        val row = database.deletedProfileBackupDao().getAll().single()
        repository.deleteSubscriptionAndNodes(sub.id)

        assertEquals(1, repository.restoreTrashEntries(listOf(row.id)))
        val restored = database.serverProfileDao().getById(original.id)!!
        assertEquals(SourceType.MANUAL.name, restored.sourceType)
        assertNull(restored.subscriptionId)
        assertEquals("Restored", restored.groupName)
    }

    @Test
    fun restoreBackupBatchNeverReplacesDifferentFingerprintWithSameId() = runBlocking {
        val original = profile("collision-id", name = "Original", address = "original.example")
        database.serverProfileDao().upsert(original)
        val batch = repository.deleteProfilesWithBackup(listOf(original.id), "Manual delete")!!
        val existing = profile("collision-id", name = "Existing", address = "existing.example")
        database.serverProfileDao().upsert(existing)

        assertEquals(1, repository.restoreBackupBatch(batch.batchId))
        val profiles = database.serverProfileDao().getAll()
        assertEquals(2, profiles.size)
        assertEquals("Existing", database.serverProfileDao().getById("collision-id")!!.name)
        assertTrue(profiles.any { it.id != original.id && it.address == original.address })
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())
    }

    @Test
    fun restoreBackupBatchSkipsSameScopeDuplicateAndPreservesUnreadableTrash() = runBlocking {
        val duplicate = profile("duplicate-original", address = "duplicate.example")
        database.serverProfileDao().upsert(duplicate)
        val batch = repository.deleteProfilesWithBackup(listOf(duplicate.id), "Manual delete")!!
        database.serverProfileDao().upsert(duplicate.copy(id = "duplicate-existing"))

        assertEquals(0, repository.restoreBackupBatch(batch.batchId))
        assertEquals(listOf("duplicate-existing"), database.serverProfileDao().getAll().map { it.id })
        assertTrue(database.deletedProfileBackupDao().getAll().isEmpty())

        val corrupt = DeletedProfileBackupEntity(
            batchId = "corrupt-batch",
            deletedAt = now,
            label = "Unreadable",
            profileId = "corrupt",
            payloadJson = "not valid profile json"
        )
        database.deletedProfileBackupDao().insertAll(listOf(corrupt))
        assertEquals(0, repository.restoreBackupBatch(corrupt.batchId))
        assertEquals(listOf(corrupt.id), database.deletedProfileBackupDao().getAll().map { it.id })
    }

    // ------------------------------------------------------------------------------------
    // Repository: manual delete backup metadata
    // ------------------------------------------------------------------------------------

    @Test
    fun deleteProfilesWithBackupPopulatesTrashRetentionMetadata() = runBlocking {
        val p = profile("p1", name = "Deleted", subId = "s1")
        database.serverProfileDao().upsert(p)

        val before = System.currentTimeMillis()
        val batch = repository.deleteProfilesWithBackup(listOf("p1"), "Manual delete")

        assertNotNull(batch)
        val row = database.deletedProfileBackupDao().getAll().single()
        assertEquals("MANUAL_DELETE", row.category)
        assertEquals("Manual delete", row.reason)
        assertEquals(SourceType.SUBSCRIPTION.name, row.sourceType)
        assertEquals("s1", row.subscriptionId)
        assertEquals(batch!!.batchId, row.batchId)
        assertTrue(row.expiresAt in (before + HealthPolicy.TRASH_RETENTION_MS)..(System.currentTimeMillis() + HealthPolicy.TRASH_RETENTION_MS))
        assertNull(database.serverProfileDao().getById("p1"))
    }
}
