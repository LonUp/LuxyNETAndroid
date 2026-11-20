package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.FastestServerSelector
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.HealthStatus
import com.example.data.model.StabilitySettings
import com.example.data.model.ConnectionState
import com.example.data.model.SourceType
import com.example.ui.LuxyVpnViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewModelIntegrationTest {
    private lateinit var app: LuxyVpnApplication
    private lateinit var repository: com.example.data.repository.LuxyVpnRepository

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        repository = app.repository
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("integration test setup")
            clearStoredData()
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("integration test teardown")
            clearStoredData()
        }
    }

    private suspend fun clearStoredData() {
        repository.getAllProfiles().forEach { repository.deleteProfile(it.id) }
        repository.getAllSubscriptions().forEach { repository.deleteSubscriptionAndNodes(it.id) }
        repository.trashEntriesFlow.first().forEach {
            repository.permanentlyDeleteTrashEntries(listOf(it.id))
        }
        repository.updateSettings { StabilitySettings() }
    }

    private fun profile(id: String, healthStatus: HealthStatus = HealthStatus.UNTESTED) =
        ServerProfileEntity(
            id = id,
            name = id,
            address = "$id.example.com",
            port = 443,
            protocol = "VLESS",
            security = "TLS",
            transport = "TCP",
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            healthStatus = healthStatus.name
        )

    private suspend fun awaitMainUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (condition()) return
            delay(20)
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun smartCleanupPlanTracksProfilesAndNeverSuggestsActiveNode() = runBlocking {
        val active = profile("active", HealthStatus.INVALID)
        val cleanup = profile("cleanup", HealthStatus.INVALID)
        withContext(Dispatchers.IO) { repository.saveProfiles(listOf(active, cleanup)) }

        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        awaitMainUntil {
            viewModel.smartCleanupPlan.value.totalSuggested == 2
        }

        app.tunnelManager.connectCandidates(listOf(active))
        awaitMainUntil {
            val suggestedIds = viewModel.smartCleanupPlan.value.groups
                .flatMap { it.items }
                .map { it.profile.id }
            viewModel.telemetry.value.activeNode?.id == active.id &&
                active.id !in suggestedIds && cleanup.id in suggestedIds
        }
    }

    @Test
    fun dismissCleanupSuggestionsUpdatesPlanAndPersistsInSettings() = runBlocking {
        val invalid = profile("keep-me", HealthStatus.INVALID)
        withContext(Dispatchers.IO) { repository.saveProfiles(listOf(invalid)) }

        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        val settingsJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
            viewModel.settings.collect {}
        }
        try {
            awaitMainUntil { viewModel.smartCleanupPlan.value.totalSuggested == 1 }
            val item = viewModel.smartCleanupPlan.value.groups.single().items.single()
            val key = com.example.core.SmartCleanupAnalyzer.dismissalKey(item)

            viewModel.dismissCleanupSuggestions(listOf(item))
            awaitMainUntil {
                viewModel.smartCleanupPlan.value.totalSuggested == 0 &&
                    viewModel.uiNotice.value == "Kept 1 configuration(s)"
            }

            assertEquals(setOf(key), repository.settingsFlow.first().dismissedCleanupKeys)
            repository.updateSettings { it.copy(language = "en") }
            assertEquals(setOf(key), repository.settingsFlow.first().dismissedCleanupKeys)
        } finally {
            settingsJob.cancel()
        }
    }

    @Test
    fun disconnectResetsExpandedDetails() = runBlocking {
        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        val active = profile("expanded-details")
        app.tunnelManager.connectCandidates(listOf(active))
        awaitMainUntil { viewModel.telemetry.value.activeNode?.id == active.id }

        viewModel.setDetailsExpanded(true)
        assertTrue(tunnelManagerFlag("detailsExpanded"))

        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("details reset integration test")
        }
        awaitMainUntil {
            !viewModel.detailsExpanded.value &&
                viewModel.telemetry.value.connectionState == ConnectionState.DISCONNECTED &&
                !tunnelManagerFlag("detailsExpanded")
        }
    }

    @Test
    fun cleanupTrashEntriesCanBeMovedRestoredAndPermanentlyDeleted() = runBlocking {
        val invalid = profile("trash-me", HealthStatus.INVALID)
        withContext(Dispatchers.IO) { repository.saveProfiles(listOf(invalid)) }
        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        awaitMainUntil { viewModel.smartCleanupPlan.value.totalSuggested == 1 }

        val cleanupItem = viewModel.smartCleanupPlan.value.groups.single().items.single()
        viewModel.moveCleanupItemsToTrash(listOf(cleanupItem))
        awaitMainUntil {
            viewModel.allProfiles.value.isEmpty() &&
                viewModel.trashEntries.value.size == 1 &&
                viewModel.lastDeletedBatch.value != null &&
                viewModel.uiNotice.value == "Moved 1 configuration(s) to Trash"
        }
        assertEquals("Moved 1 configuration(s) to Trash", viewModel.uiNotice.value)

        val trashEntry = viewModel.trashEntries.value.single()
        viewModel.restoreTrashEntries(listOf(trashEntry.id))
        awaitMainUntil {
            viewModel.allProfiles.value.singleOrNull()?.id == invalid.id &&
                viewModel.trashEntries.value.isEmpty() &&
                viewModel.uiNotice.value == "Restored 1 configuration(s)"
        }
        assertEquals("Restored 1 configuration(s)", viewModel.uiNotice.value)

        awaitMainUntil { viewModel.smartCleanupPlan.value.totalSuggested == 1 }
        val restoredItem = viewModel.smartCleanupPlan.value.groups.single().items.single()
        viewModel.moveCleanupItemsToTrash(listOf(restoredItem))
        awaitMainUntil { viewModel.trashEntries.value.size == 1 }
        val finalEntry = viewModel.trashEntries.value.single()
        viewModel.permanentlyDeleteTrashEntries(listOf(finalEntry.id))
        awaitMainUntil {
            viewModel.trashEntries.value.isEmpty() &&
                viewModel.uiNotice.value == "Permanently deleted 1 configuration(s)"
        }
        assertEquals("Permanently deleted 1 configuration(s)", viewModel.uiNotice.value)
    }

    @Test
    fun emptyManualLibraryDoesNotInflateConnectionSourcesOrEnabledSourceCount() = runBlocking {
        val subscription = SubscriptionEntity(
            id = "sub-only",
            name = "Only subscription",
            url = "https://feed.example.test/subscription",
            nodeCount = 4,
            lastUpdatedAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) { repository.saveSubscription(subscription) }

        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        awaitMainUntil {
            viewModel.connectionSources.value.singleOrNull()?.id == subscription.id
        }

        val sources = viewModel.connectionSources.value
        assertEquals(1, sources.size)
        assertEquals(1, sources.count { it.isEnabledForConnection })
        assertEquals(0, sources.single().totalCount)
    }

    @Test
    fun invalidProfilesAreExcludedFromEligibleProfilesAndSourceCounts() = runBlocking {
        val subscription = SubscriptionEntity(
            id = "health-sub",
            name = "Health subscription",
            url = "https://feed.example.test/health"
        )
        val invalid = profile("invalid", HealthStatus.INVALID).copy(
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id
        )
        val valid = profile("valid").copy(
            sourceType = SourceType.SUBSCRIPTION.name,
            subscriptionId = subscription.id
        )
        withContext(Dispatchers.IO) {
            repository.saveSubscription(subscription)
            repository.saveProfiles(listOf(invalid, valid))
        }

        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        awaitMainUntil {
            viewModel.eligibleProfiles.value.map { it.id } == listOf(valid.id) &&
                viewModel.connectionSources.value.singleOrNull()?.eligibleCount == 1
        }

        assertEquals(listOf(valid.id), viewModel.eligibleProfiles.value.map { it.id })
        assertEquals(1, viewModel.connectionSources.value.single().eligibleCount)
    }

    @Test
    fun preferredProfileUpdatesManualCounts() = runBlocking {
        val config = profile("preferred")
        withContext(Dispatchers.IO) { repository.saveProfiles(listOf(config)) }
        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        val countsCollector = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.manualCounts.collect { }
        }
        try {
            awaitMainUntil { viewModel.manualCounts.value.total == 1 }
            assertEquals(0, viewModel.manualCounts.value.preferred)

            viewModel.setProfilePreferred(config.id, true)
            awaitMainUntil {
                viewModel.allProfiles.value.singleOrNull()?.isPinned == true &&
                    viewModel.manualCounts.value.preferred == 1
            }
        } finally {
            countsCollector.cancel()
        }
    }

    @Test
    fun encryptedBackupRoundTripsAndCannotRestoreWhileConnected() = runBlocking {
        val config = profile("backup-profile")
        val subscription = SubscriptionEntity(
            id = "backup-subscription",
            name = "Backup feed",
            url = "https://feed.example.test/subscription"
        )
        withContext(Dispatchers.IO) {
            repository.saveProfiles(listOf(config))
            repository.saveSubscription(subscription)
        }
        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        val passphrase = "integration backup passphrase"
        val backup = viewModel.exportEncryptedBackup(passphrase).getOrThrow()
        assertTrue(backup.isNotBlank())

        withContext(Dispatchers.IO) {
            repository.deleteProfile(config.id)
            repository.deleteSubscriptionAndNodes(subscription.id)
        }
        val summary = viewModel.restoreEncryptedBackup(backup, passphrase).getOrThrow()
        assertEquals(1, summary.restoredProfiles)
        assertEquals(1, summary.restoredSubscriptions)

        val restored = withContext(Dispatchers.IO) { repository.getAllProfiles().single() }
        app.tunnelManager.connectCandidates(listOf(restored))
        assertTrue(app.tunnelManager.isTunnelActiveOrConnecting())
        val blocked = viewModel.restoreEncryptedBackup(backup, passphrase)
        assertTrue(blocked.isFailure)
        assertEquals("Disconnect before restoring a backup", blocked.exceptionOrNull()?.message)
    }

    @Test
    fun detailsAndForegroundStateAreForwardedToTunnelManager() {
        val viewModel = LuxyVpnViewModel(app, FastestServerSelector { 10 })
        assertFalse(viewModel.detailsExpanded.value)

        viewModel.toggleDetailsExpanded()
        assertTrue(viewModel.detailsExpanded.value)
        assertEquals(true, tunnelManagerFlag("detailsExpanded"))

        viewModel.setDetailsExpanded(false)
        assertFalse(viewModel.detailsExpanded.value)
        assertEquals(false, tunnelManagerFlag("detailsExpanded"))

        viewModel.setAppForegrounded(false)
        assertEquals(false, tunnelManagerFlag("appForegrounded"))
        viewModel.setAppForegrounded(true)
        assertEquals(true, tunnelManagerFlag("appForegrounded"))
    }

    private fun tunnelManagerFlag(name: String): Boolean {
        val field = app.tunnelManager.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.getBoolean(app.tunnelManager)
    }
}
