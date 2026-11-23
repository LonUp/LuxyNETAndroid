package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.ConnectionState
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthStatus
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import com.example.ui.LuxyVpnViewModel
import com.example.vpn.LuxyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@Config(sdk = [34])
class CountrySelectionViewModelTest {
    private fun profile(id: String, name: String) = ServerProfileEntity(
        id = id,
        name = name,
        address = "$id.example.com",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555"
    )

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        Shadows.shadowOf(app).clearStartedServices()
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("test setup")
            app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
            app.repository.updateSettings { StabilitySettings() }
        }
    }

    @After
    fun tearDown() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("test teardown")
            app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
            app.repository.updateSettings { StabilitySettings() }
        }
    }

    private suspend fun awaitMainUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            withContext(Dispatchers.IO) { delay(20) }
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun selectedCountryPersistsAcrossFreshDataStoreAndRepositoryInstances() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        withContext(Dispatchers.IO) {
            app.repository.setSelectedCountry("DE")
            val freshStore = SettingsDataStore(app)
            val freshRepo = LuxyVpnRepository(app.database, freshStore)
            assertEquals("DE", freshRepo.getSettings().selectedCountryCode)

            freshRepo.setSelectedCountry(null)
            assertNull(app.repository.getSettings().selectedCountryCode)
        }
    }

    @Test
    fun automaticConnectsWithoutProbingEveryServerAndCountryRestrictsTheAttempt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        val de1 = profile("de1", "🇩🇪 Germany 1")
        val de2 = profile("de2", "DE-Frankfurt-2")
        val us1 = profile("us1", "🇺🇸 US 1")
        val unknown = profile("unk1", "My server")
        withContext(Dispatchers.IO) {
            app.repository.saveProfiles(listOf(us1, de1, de2, unknown))
            app.repository.setSelectedCountry(null)
        }

        val autoVm = LuxyVpnViewModel(app)
        autoVm.toggleConnect()
        awaitMainUntil {
            !autoVm.isSelectingFastestServer.value &&
                autoVm.telemetry.value.connectionState == ConnectionState.CONNECTING
        }
        assertTrue(autoVm.telemetry.value.activeNode?.id in setOf("us1", "de1", "de2", "unk1"))
        assertFalse(autoVm.uiNotice.value?.contains("No servers responded") == true)
        withContext(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("test cleanup") }

        withContext(Dispatchers.IO) { app.repository.setSelectedCountry("DE") }
        val deVm = LuxyVpnViewModel(app)
        deVm.toggleConnect()
        awaitMainUntil {
            !deVm.isSelectingFastestServer.value &&
                deVm.telemetry.value.connectionState == ConnectionState.CONNECTING
        }
        assertTrue(deVm.telemetry.value.activeNode?.id in setOf("de1", "de2"))
        withContext(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("test cleanup") }
    }

    @Test
    fun missingServersForSelectedCountryShowsClearMessageWithoutSilentlySwitching() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        withContext(Dispatchers.IO) {
            app.repository.saveProfiles(listOf(profile("us1", "🇺🇸 US 1")))
            app.repository.setSelectedCountry("DE")
        }

        val vm = LuxyVpnViewModel(app)
        awaitMainUntil { vm.selectedCountryCode.value == "DE" && vm.profiles.value.size == 1 }
        assertFalse(vm.hasEligibleServersForSelectedCountry())

        vm.toggleConnect()
        awaitMainUntil { vm.uiNotice.value != null }
        assertEquals("No servers available for the selected country", vm.uiNotice.value)
        assertEquals("DE", withContext(Dispatchers.IO) { app.repository.getSettings().selectedCountryCode })
        assertNotEquals(ConnectionState.CONNECTED, vm.telemetry.value.connectionState)
    }
    @Test
    fun failedReconnectReportsFailureWithoutFalselyReportingConnected() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        val de1 = profile("de1", "🇩🇪 Germany 1")
        val de2 = profile("de2", "🇩🇪 Germany 2")
        val us1 = profile("us1", "🇺🇸 US 1")
        withContext(Dispatchers.IO) {
            app.repository.saveProfiles(listOf(us1, de1, de2))
            app.repository.setSelectedCountry("US")
        }

        // Start an active connection attempt on US first.
        app.tunnelManager.connectCandidates(listOf(us1))
        assertEquals(ConnectionState.CONNECTING, app.tunnelManager.telemetry.value.connectionState)
        awaitMainUntil {
            Shadows.shadowOf(app).peekNextStartedService()?.action == LuxyVpnService.ACTION_START
        }
        assertEquals(us1.id, app.tunnelManager.telemetry.value.activeNode?.id)

        val vm = LuxyVpnViewModel(app)
        // Switching to a country with no servers preserves existing selection and connection.
        vm.switchCountryAndReconnect("FR")
        awaitMainUntil { vm.uiNotice.value != null }
        assertEquals("No servers available for the selected country", vm.uiNotice.value)
        assertEquals("US", withContext(Dispatchers.IO) { app.repository.getSettings().selectedCountryCode })
        assertEquals(ConnectionState.CONNECTING, vm.telemetry.value.connectionState)

        // Switching to DE disconnects US, persists DE, and reports ERROR when tunnel startup fails.
        Shadows.shadowOf(app).clearStartedServices()
        vm.switchCountryAndReconnect("DE")
        val startedNodes = mutableListOf<String?>()
        awaitMainUntil {
            while (true) {
                val intent = Shadows.shadowOf(app).nextStartedService ?: break
                if (intent.action == LuxyVpnService.ACTION_START) {
                    startedNodes += vm.telemetry.value.activeNode?.id
                    val attempt = intent.getLongExtra(LuxyVpnService.EXTRA_ATTEMPT_ID, -1L)
                    assertTrue(attempt > 0L)
                    app.tunnelManager.onTunnelFailed(attempt, "Unable to establish the VPN interface")
                }
            }
            vm.telemetry.value.connectionState == ConnectionState.ERROR
        }
        assertEquals(listOf(de1.id, de2.id), startedNodes)
        assertEquals("DE", withContext(Dispatchers.IO) { app.repository.getSettings().selectedCountryCode })
        assertEquals(ConnectionState.ERROR, vm.telemetry.value.connectionState)
        assertEquals("Unable to establish the VPN interface", vm.telemetry.value.lastError)

        withContext(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("reset") }
        assertEquals(ConnectionState.DISCONNECTED, app.tunnelManager.telemetry.value.connectionState)
        app.tunnelManager.failConnection("No servers responded. Check your network and try again")
        assertEquals(ConnectionState.ERROR, app.tunnelManager.telemetry.value.connectionState)
        assertEquals("No servers responded. Check your network and try again", app.tunnelManager.telemetry.value.lastError)
    }

    @Test
    fun connectionStartsWithoutTcpProbeAndDoesNotStampHealth() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        val profiles = listOf(profile("offline-one", "Offline 1"), profile("offline-two", "Offline 2"))
        withContext(Dispatchers.IO) { app.repository.saveProfiles(profiles) }

        val vm = LuxyVpnViewModel(app)
        vm.toggleConnect()
        awaitMainUntil {
            !vm.isSelectingFastestServer.value &&
                vm.telemetry.value.connectionState == ConnectionState.CONNECTING
        }

        assertTrue(vm.telemetry.value.activeNode?.id in setOf("offline-one", "offline-two"))
        assertFalse(vm.uiNotice.value?.contains("No servers responded") == true)

        val stored = withContext(Dispatchers.IO) { app.repository.getAllProfiles() }
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.healthStatus == HealthStatus.UNTESTED.name })
        assertTrue(stored.all {
            it.lastErrorCategory == ErrorCategory.NONE.name && it.lastAttemptAt == null
        })
    }
}
