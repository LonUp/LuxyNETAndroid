package com.example.vpn

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ExitCountryVerifier
import com.example.core.TunnelLeakChecker
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.StabilitySettings
import com.example.data.model.VerificationState
import com.example.data.repository.LuxyVpnRepository
import com.example.xraycore.TunnelConnectivityCheck
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TunnelReliabilityAndReconnectTest {
    private lateinit var context: Application
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository
    private lateinit var server: MockWebServer
    private var manager: TunnelManager? = null

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java).build()
        repository = LuxyVpnRepository(database, SettingsDataStore(context))
        repository.updateSettings { StabilitySettings() }
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = runBlocking {
        manager?.disconnectAndAwait("test cleanup")
        server.shutdown()
        database.close()
    }

    private fun profile(
        id: String,
        country: String? = "DE",
        confidence: CountryConfidence = CountryConfidence.ESTIMATED
    ) = ServerProfileEntity(
        id = id,
        name = id,
        address = "$id.example.com",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        countryCode = country,
        countryConfidence = confidence.name,
        countrySource = if (confidence == CountryConfidence.VERIFIED) {
            CountrySource.EXIT_VERIFIED.name
        } else {
            CountrySource.METADATA.name
        }
    )

    private fun createManager(
        recoveryDelay: (Int) -> Long = { 0L },
        dnsProbe: suspend () -> Boolean = { true },
        trafficStats: () -> Pair<Long, Long> = { 0L to 0L }
    ): TunnelManager = TunnelManager(
        context = context,
        repository = repository,
        connectivityCheck = TunnelConnectivityCheck(
            endpoints = listOf(server.url("/check").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        ),
        exitCountryVerifier = ExitCountryVerifier(
            endpoints = listOf(server.url("/exit").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L,
            geoIpLookup = { "DE" }
        ),
        networkFingerprintProvider = { "wifi:test" },
        leakChecker = TunnelLeakChecker(dnsProbe = dnsProbe),
        networkMonitorFactory = { app -> NetworkMonitor(app, debounceMs = 0L) },
        trafficStatsProvider = trafficStats,
        recoveryDelayMs = recoveryDelay
    ).also { manager = it }

    private fun enqueueCheck(code: Int = 204) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    private fun enqueueExit(country: String = "DE") {
        server.enqueue(MockResponse().setBody("ip=198.51.100.17\nloc=$country\n"))
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10L)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    private suspend fun saveProfiles(vararg profiles: ServerProfileEntity) {
        repository.saveProfiles(profiles.toList())
    }

    private suspend fun connectAndVerify(
        tunnelManager: TunnelManager,
        profiles: List<ServerProfileEntity>,
        selectedCountryCode: String? = null,
        alwaysOn: Boolean = false,
        lockdown: Boolean = false
    ): Long {
        enqueueCheck()
        enqueueExit()
        tunnelManager.connectCandidates(profiles, selectedCountryCode)
        awaitUntil { tunnelManager.currentAttemptId() > 0L && tunnelManager.telemetry.value.activeNode?.id == profiles.first().id }
        val attempt = tunnelManager.currentAttemptId()
        tunnelManager.onTunnelEstablished(attempt, alwaysOn, lockdown)
        awaitUntil { tunnelManager.telemetry.value.connectionState == ConnectionState.CONNECTED }
        return attempt
    }

    @Test
    fun unexpectedCoreFailureRetriesCurrentServerAndRecovers() = runBlocking {
        val first = profile("first")
        val alternate = profile("alternate")
        saveProfiles(first, alternate)
        val tm = createManager()
        val initialAttempt = connectAndVerify(tm, listOf(first, alternate))

        tm.onTunnelFailed(initialAttempt, "connection timed out")
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt && tm.telemetry.value.activeNode?.id == first.id
        }
        enqueueCheck()
        enqueueExit()
        val retryAttempt = tm.currentAttemptId()
        tm.onTunnelEstablished(retryAttempt)
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.CONNECTED }

        assertEquals(first.id, tm.telemetry.value.activeNode?.id)
        assertEquals(VerificationState.VERIFIED, tm.telemetry.value.verificationState)
        assertTrue(tm.telemetry.value.reconnectAttempt > 0)
        assertEquals(ErrorCategory.NONE, tm.telemetry.value.lastErrorCategory)
    }

    @Test
    fun healthyNetworkHandoffKeepsServerAndBrokenHandoffStartsRecovery() = runBlocking {
        val first = profile("first")
        val alternate = profile("alternate")
        saveProfiles(first, alternate)
        val tm = createManager()
        val initialAttempt = connectAndVerify(tm, listOf(first, alternate))

        enqueueCheck()
        enqueueExit()
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:one"))
        awaitUntil { tm.telemetry.value.networkFingerprint == "cellular:one" }
        assertEquals(ConnectionState.CONNECTED, tm.telemetry.value.connectionState)
        assertEquals(initialAttempt, tm.currentAttemptId())
        assertEquals(first.id, tm.telemetry.value.activeNode?.id)

        enqueueCheck(code = 500)
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Wi-Fi", "wifi:two"))
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt
        }
        assertEquals(first.id, tm.telemetry.value.activeNode?.id)
    }

    @Test
    fun networkLossWaitsWithoutCooldownAndRecoveryResumesWhenRestored() = runBlocking {
        val first = profile("first")
        val alternate = profile("alternate")
        saveProfiles(first, alternate)
        val tm = createManager()
        val attempt = connectAndVerify(tm, listOf(first, alternate))

        tm.onNetworkSnapshotForTest(NetworkSnapshot(false, "None", "offline"))
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.RECONNECTING }
        tm.onTunnelFailed(attempt, "VPN core stopped")
        delay(50L)
        assertEquals(0, repository.getAllProfiles().first { it.id == first.id }.consecutiveFailures)
        assertEquals("Waiting for network to reconnect…", tm.telemetry.value.lastError)

        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:restored"))
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > attempt
        }
        assertTrue(tm.telemetry.value.reconnectAttempt > 0)
    }
    @Test
    fun newConnectCancelsPendingRecovery() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        saveProfiles(first, second)
        val tm = createManager(recoveryDelay = { 5_000L })
        val initialAttempt = connectAndVerify(tm, listOf(first, second))

        tm.onTunnelFailed(initialAttempt, "connection timed out")
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.FAILOVER }
        tm.connectCandidates(listOf(second), selectedCountryCode = "DE")
        awaitUntil {
            tm.currentAttemptId() > initialAttempt &&
                tm.telemetry.value.activeNode?.id == second.id &&
                tm.telemetry.value.connectionState == ConnectionState.CONNECTING
        }
        val explicitAttempt = tm.currentAttemptId()
        delay(100L)

        assertEquals(explicitAttempt, tm.currentAttemptId())
        assertEquals(second.id, tm.telemetry.value.activeNode?.id)
        assertEquals(ConnectionState.CONNECTING, tm.telemetry.value.connectionState)
        tm.disconnectAndAwait("cancel new connection")
    }

    @Test
    fun networkMonitorStartStopIsIdempotent() {
        val monitor = NetworkMonitor(context, debounceMs = 0L)
        monitor.start()
        monitor.start()
        monitor.stop()
        monitor.stop()
        monitor.start()
        monitor.stop()
    }

    @Test
    fun explicitDisconnectPreventsReconnectAfterNetworkEvents() = runBlocking {
        val first = profile("first")
        saveProfiles(first)
        val tm = createManager(recoveryDelay = { 10_000L })
        val attempt = connectAndVerify(tm, listOf(first))

        tm.onTunnelFailed(attempt, "connection timed out")
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.FAILOVER }
        tm.disconnectAndAwait("user disconnected")
        val disconnectedAttempt = tm.currentAttemptId()
        assertEquals(ConnectionState.DISCONNECTED, tm.telemetry.value.connectionState)

        tm.onNetworkSnapshotForTest(NetworkSnapshot(false, "None", "offline"))
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:again"))
        delay(100L)

        assertEquals(ConnectionState.DISCONNECTED, tm.telemetry.value.connectionState)
        assertEquals(disconnectedAttempt, tm.currentAttemptId())
    }

    @Test
    fun recoveryStopsAtFiveAttemptsAndUsesExponentialBackoff() = runBlocking {
        val profiles = (1..7).map { profile("node-$it") }
        saveProfiles(*profiles.toTypedArray())
        val requestedBackoffs = mutableListOf<Int>()
        val tm = createManager(recoveryDelay = { index -> requestedBackoffs += index; 0L })
        val initialAttempt = connectAndVerify(tm, profiles)

        tm.onTunnelFailed(initialAttempt, "connection timed out")
        var failedAttempt = initialAttempt
        repeat(5) {
            awaitUntil {
                tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                    tm.currentAttemptId() > failedAttempt
            }
            failedAttempt = tm.currentAttemptId()
            tm.onTunnelFailed(failedAttempt, "connection timed out")
        }
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.ERROR }

        assertEquals(listOf(1, 2, 3, 4, 5), requestedBackoffs)
        assertEquals(5, tm.telemetry.value.reconnectAttempt)
        assertEquals(5, tm.telemetry.value.maxReconnectAttempts)
        assertEquals("Connection timed out", tm.telemetry.value.lastError)
        assertEquals(ErrorCategory.TIMEOUT, tm.telemetry.value.lastErrorCategory)
        assertEquals(VerificationState.FAILED, tm.telemetry.value.verificationState)
    }

    @Test
    fun failoverRequeriesSelectorWithoutLeavingRequestedCountry() = runBlocking {
        val de1 = profile("de-1", "DE", CountryConfidence.ESTIMATED)
        val de2 = profile("de-2", "DE", CountryConfidence.ESTIMATED)
        val us = profile("us-1", "US", CountryConfidence.VERIFIED)
        saveProfiles(de1, de2, us)
        val tm = createManager()
        val initialAttempt = connectAndVerify(tm, listOf(de1, de2, us), selectedCountryCode = "DE")

        tm.onTunnelFailed(initialAttempt, "connection timed out")
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt && tm.telemetry.value.activeNode?.id == de1.id
        }
        val retryAttempt = tm.currentAttemptId()
        tm.onTunnelFailed(retryAttempt, "connection timed out")
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > retryAttempt
        }

        assertEquals(de2.id, tm.telemetry.value.activeNode?.id)
        assertNotEquals(us.id, tm.telemetry.value.activeNode?.id)
        assertEquals("DE", tm.telemetry.value.activeNode?.countryCode)
    }

    @Test
    fun verificationStateAndLeakFlagsReflectEachVerificationOutcome() = runBlocking {
        val first = profile("first")
        val failed = profile("failed")
        saveProfiles(first, failed)
        val dnsAvailable = AtomicBoolean(true)
        val tm = createManager(dnsProbe = { dnsAvailable.get() })

        enqueueCheck()
        enqueueExit()
        tm.connectCandidates(listOf(first), "DE")
        awaitUntil { tm.currentAttemptId() > 0L }
        val attempt = tm.currentAttemptId()
        tm.onTunnelEstablished(attempt, alwaysOn = true, lockdown = true)
        assertEquals(VerificationState.CHECKING, tm.telemetry.value.verificationState)
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.CONNECTED }
        assertEquals(VerificationState.VERIFIED, tm.telemetry.value.verificationState)
        assertTrue(tm.telemetry.value.connectivityVerified)
        assertTrue(tm.telemetry.value.exitVerified)
        assertTrue(tm.telemetry.value.dnsLeakProtected)
        assertTrue(tm.telemetry.value.ipv6LeakProtected)
        assertTrue(tm.telemetry.value.alwaysOnEnabled)
        assertTrue(tm.telemetry.value.lockdownEnabled)

        dnsAvailable.set(false)
        enqueueCheck()
        enqueueExit()
        tm.retryVerification()
        awaitUntil { tm.telemetry.value.verificationState == VerificationState.PARTIAL }
        assertTrue(tm.telemetry.value.connectivityVerified)
        assertTrue(tm.telemetry.value.exitVerified)
        assertFalse(tm.telemetry.value.dnsLeakProtected)
        assertTrue(tm.telemetry.value.ipv6LeakProtected)

        tm.disconnectAndAwait("switch test profile")
        enqueueCheck(code = 500)
        tm.connectCandidates(listOf(failed), null)
        awaitUntil { tm.currentAttemptId() > attempt && tm.telemetry.value.activeNode?.id == failed.id }
        tm.onTunnelEstablished(tm.currentAttemptId())
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.ERROR }
        assertEquals(VerificationState.FAILED, tm.telemetry.value.verificationState)
        assertFalse(tm.telemetry.value.connectivityVerified)
        assertEquals(ErrorCategory.UNKNOWN, tm.telemetry.value.lastErrorCategory)
    }

    @Test
    fun trafficCountersAccumulateAndResetWithSession() = runBlocking {
        val profile = profile("traffic")
        saveProfiles(profile)
        val stats = AtomicReference(0L to 0L)
        val tm = createManager(trafficStats = { stats.get() })
        tm.setDetailsExpanded(true)
        val attempt = connectAndVerify(tm, listOf(profile))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        stats.set(900L to 1_700L)

        awaitUntil(timeoutMs = 4_000L) {
            tm.telemetry.value.rxBytes >= 900L && tm.telemetry.value.txBytes >= 1_700L
        }
        assertTrue(tm.telemetry.value.connectedAtElapsedMs > 0L)
        assertTrue(tm.telemetry.value.connectedSinceMs > 0L)
        assertTrue(tm.telemetry.value.sessionDurationMs > 0L)
        assertTrue(tm.telemetry.value.rxBytesPerSec > 0L)

        tm.disconnectAndAwait("end session")
        assertEquals(ConnectionState.DISCONNECTED, tm.telemetry.value.connectionState)
        assertEquals(0L, tm.telemetry.value.connectedSinceMs)
        assertEquals(0L, tm.telemetry.value.connectedAtElapsedMs)
        assertEquals(0L, tm.telemetry.value.sessionDurationMs)
        assertEquals(0L, tm.telemetry.value.rxBytes)
        assertEquals(0L, tm.telemetry.value.txBytes)
        assertEquals(0L, tm.telemetry.value.rxBytesPerSec)
        assertEquals(0L, tm.telemetry.value.txBytesPerSec)
        assertEquals(VerificationState.IDLE, tm.telemetry.value.verificationState)
        assertNull(tm.telemetry.value.exitIp)
        assertNull(tm.telemetry.value.exitCountryCode)
        assertNotEquals(attempt, tm.currentAttemptId())
    }
}
