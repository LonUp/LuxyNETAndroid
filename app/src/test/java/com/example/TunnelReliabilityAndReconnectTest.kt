package com.example.vpn

import android.app.Application
import android.app.Notification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.net.LinkAddress
import android.net.LinkProperties
import com.example.core.ExitCountryVerifier
import com.example.core.VpnPosture
import com.example.core.TunnelLeakChecker
import com.example.core.VpnPostureInspector
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
import com.example.R
import com.example.xraycore.TunnelConnectivityCheck
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
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
        trafficStats: () -> Pair<Long, Long> = { 0L to 0L },
        vpnPostureProvider: () -> VpnPosture? = { VpnPosture(hasIpv6TunRoute = true, hasVpnDnsOnly = true) },
        geoIpLookup: (String) -> String? = { "DE" },
        networkSnapshotProvider: () -> NetworkSnapshot = { NetworkSnapshot(true, "Wi-Fi", "wifi:test") }
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
            geoIpLookup = geoIpLookup
        ),
        networkFingerprintProvider = { "wifi:test" },
        leakChecker = TunnelLeakChecker(dnsProbe = dnsProbe),
        networkMonitorFactory = { app ->
            NetworkMonitor(app, debounceMs = 0L, snapshotProvider = networkSnapshotProvider)
        },
        trafficStatsProvider = trafficStats,
        recoveryDelayMs = recoveryDelay,
        vpnPostureProvider = vpnPostureProvider
    ).also { manager = it }

    private fun enqueueCheck(code: Int = 204) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    private fun enqueueExit(country: String? = "DE") {
        val location = country?.let { "loc=$it\n" }.orEmpty()
        server.enqueue(MockResponse().setBody("ip=198.51.100.17\n$location"))
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
        tunnelManager.onNetworkSnapshotForTest(NetworkSnapshot(true, "Wi-Fi", "wifi:test"))
        delay(50L)
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
    fun returningToPreviousNetworkRefreshesExitCountryBeforeKeepingSession() = runBlocking {
        val first = profile("returning-network-first")
        val second = profile("returning-network-fallback")
        saveProfiles(first, second)
        val tm = createManager(
            geoIpLookup = { ip -> if (ip == "198.51.100.18") "NL" else "DE" }
        )
        val initialAttempt = connectAndVerify(tm, listOf(first, second), selectedCountryCode = "DE")

        enqueueCheck()
        enqueueExit()
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:return"))
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.CONNECTED &&
                tm.telemetry.value.networkFingerprint == "cellular:return"
        }

        enqueueCheck()
        server.enqueue(MockResponse().setBody("ip=198.51.100.18\nloc=NL\n"))
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Wi-Fi", "wifi:test"))
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt &&
                tm.telemetry.value.activeNode?.id == second.id
        }
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
    fun postureInspectionSelectsLuxyTunInsteadOfAnUnrelatedActiveVpn() {
        fun vpnWithAddress(address: String) = LinkProperties().apply {
            val linkAddress = ReflectionHelpers.callConstructor(
                LinkAddress::class.java, ReflectionHelpers.ClassParameter.from(String::class.java, address)
            )
            ReflectionHelpers.callInstanceMethod<Boolean>(
                this, "addLinkAddress", ReflectionHelpers.ClassParameter.from(LinkAddress::class.java, linkAddress)
            )
        }
        val unrelatedVpn = vpnWithAddress("10.89.0.2/24")
        val luxyTun = vpnWithAddress("10.89.0.1/24")

        assertSame(
            luxyTun,
            VpnPostureInspector.findLuxyTunLinkProperties(sequenceOf(unrelatedVpn, luxyTun))
        )
        assertNull(VpnPostureInspector.findLuxyTunLinkProperties(sequenceOf(unrelatedVpn)))
    }

    @Test
    fun notificationExtrasUseLocalizedGenericConnectionState() {
        val controller = Robolectric.buildService(LuxyVpnService::class.java).create()
        val service = controller.get()
        try {
            val notification = service.buildNotification(ConnectionState.FAILOVER)
            assertEquals(
                service.getString(R.string.app_name),
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            )
            assertEquals(
                service.getString(R.string.details_status_failover),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            )
            assertEquals(
                service.getString(R.string.minimal_connected),
                service.buildNotification(ConnectionState.CONNECTED)
                    .extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            )
        } finally {
            controller.destroy()
        }
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
        assertTrue(tm.telemetry.value.vpnPostureAvailable)
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
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Wi-Fi", "wifi:test"))
        delay(50L)
        tm.onTunnelEstablished(tm.currentAttemptId())
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.ERROR }
        assertEquals(VerificationState.FAILED, tm.telemetry.value.verificationState)
        assertFalse(tm.telemetry.value.connectivityVerified)
        assertEquals(ErrorCategory.UNKNOWN, tm.telemetry.value.lastErrorCategory)
    }

    @Test
    fun unknownExitCountryAfterConnectivityStaysConnectedAndInconclusive() = runBlocking {
        val node = profile("unknown-exit", "NL", CountryConfidence.ESTIMATED)
        saveProfiles(node)
        val tm = createManager(geoIpLookup = { null })

        enqueueCheck()
        enqueueExit(country = null)
        tm.connectCandidates(listOf(node), selectedCountryCode = "DE")
        awaitUntil {
            tm.currentAttemptId() > 0L && tm.telemetry.value.activeNode?.id == node.id
        }
        tm.onTunnelEstablished(tm.currentAttemptId())
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.CONNECTED }

        val telemetry = tm.telemetry.value
        val saved = repository.getAllProfiles().first { it.id == node.id }
        assertEquals(ConnectionState.CONNECTED, telemetry.connectionState)
        assertEquals(VerificationState.INCONCLUSIVE, telemetry.verificationState)
        assertTrue(telemetry.connectivityVerified)
        assertFalse(telemetry.exitVerified)
        assertEquals(node.consecutiveFailures, saved.consecutiveFailures)
        assertNull(telemetry.lastError)
        assertEquals(ErrorCategory.NONE, telemetry.lastErrorCategory)
        assertNull(telemetry.exitCountryCode)
        assertEquals("198.51.100.17", telemetry.exitIp)
        assertEquals("NL", saved.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, saved.countryConfidence)
        assertNull(saved.countryVerifiedAt)
    }

    @Test
    fun networkChangeWithUnknownExitCountryStaysConnectedAndInconclusive() = runBlocking {
        val node = profile("refresh-unknown-exit")
        saveProfiles(node)
        val tm = createManager(geoIpLookup = { null })
        connectAndVerify(tm, listOf(node), selectedCountryCode = "DE")

        enqueueCheck()
        enqueueExit(country = null)
        tm.onNetworkSnapshotForTest(
            NetworkSnapshot(true, "Cellular", "cellular:unknown-exit")
        )
        awaitUntil {
            tm.telemetry.value.networkFingerprint == "cellular:unknown-exit" &&
                tm.telemetry.value.verificationState == VerificationState.INCONCLUSIVE
        }

        val telemetry = tm.telemetry.value
        val saved = repository.getAllProfiles().first { it.id == node.id }
        assertEquals(ConnectionState.CONNECTED, telemetry.connectionState)
        assertTrue(telemetry.connectivityVerified)
        assertFalse(telemetry.exitVerified)
        assertEquals(ErrorCategory.NONE, telemetry.lastErrorCategory)
        assertNull(telemetry.lastError)
        assertNull(telemetry.exitCountryCode)
        assertEquals(CountryConfidence.UNKNOWN, telemetry.exitCountryConfidence)
        assertEquals("DE", saved.countryCode)
    }


    @Test
    fun offlineStartupWaitsForNetworkInsteadOfFailingVerification() = runBlocking {
        val node = profile("offline")
        saveProfiles(node)
        val tm = createManager(
            networkSnapshotProvider = { NetworkSnapshot(false, "None", "offline") }
        )
        enqueueCheck(code = 500)
        tm.connectCandidates(listOf(node))
        awaitUntil { tm.currentAttemptId() > 0L }
        val attempt = tm.currentAttemptId()
        tm.onNetworkSnapshotForTest(NetworkSnapshot(false, "None", "offline"))
        delay(50L)
        tm.onTunnelEstablished(attempt)

        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.RECONNECTING }
        assertEquals("Waiting for network to reconnect…", tm.telemetry.value.lastError)
        assertNotEquals(VerificationState.VERIFIED, tm.telemetry.value.verificationState)
        assertFalse(tm.telemetry.value.connectivityVerified)
    }

    @Test
    fun networkFlappingPreservesRecoveryAttemptBudget() = runBlocking {
        val first = profile("flap-first")
        val second = profile("flap-second")
        saveProfiles(first, second)
        val tm = createManager()
        val initialAttempt = connectAndVerify(tm, listOf(first, second))

        tm.onTunnelFailed(initialAttempt, "connection timed out")
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt
        }
        val firstRecoveryAttempt = tm.currentAttemptId()
        assertEquals(1, tm.telemetry.value.reconnectAttempt)

        tm.onNetworkSnapshotForTest(NetworkSnapshot(false, "None", "offline"))
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.RECONNECTING }
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:flapped"))
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > firstRecoveryAttempt &&
                tm.telemetry.value.reconnectAttempt == 2
        }
        assertEquals(2, tm.telemetry.value.reconnectAttempt)
    }

    @Test
    fun wrongVerifiedCountryRejectsCandidateBeforeConnected() = runBlocking {
        val wrong = profile("wrong-country", "DE", CountryConfidence.ESTIMATED)
        val next = profile("next-country", "DE", CountryConfidence.ESTIMATED)
        saveProfiles(wrong, next)
        val tm = createManager(geoIpLookup = { null })

        enqueueCheck()
        enqueueExit("NL")
        tm.connectCandidates(listOf(wrong, next), selectedCountryCode = "DE")
        awaitUntil {
            tm.currentAttemptId() > 0L && tm.telemetry.value.activeNode?.id == wrong.id
        }
        val wrongAttempt = tm.currentAttemptId()
        tm.onTunnelEstablished(wrongAttempt)

        awaitUntil {
            tm.currentAttemptId() > wrongAttempt && tm.telemetry.value.activeNode?.id == next.id
        }
        assertEquals(ConnectionState.CONNECTING, tm.telemetry.value.connectionState)
        assertNotEquals(ConnectionState.CONNECTED, tm.telemetry.value.connectionState)
        assertNotEquals(wrong.id, tm.telemetry.value.activeNode?.id)
    }

    @Test
    fun wrongCountryMismatchDoesNotRetryTheSameRecoveryServer() = runBlocking {
        val first = profile("mismatch-first")
        val second = profile("mismatch-second")
        saveProfiles(first, second)
        val tm = createManager(
            geoIpLookup = { ip -> if (ip == "198.51.100.18") "NL" else "DE" }
        )
        val initialAttempt = connectAndVerify(tm, listOf(first, second), selectedCountryCode = "DE")
        enqueueCheck()
        server.enqueue(MockResponse().setBody("ip=198.51.100.18\nloc=NL\n"))
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Cellular", "cellular:wrong-exit"))

        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt &&
                tm.telemetry.value.activeNode?.id != first.id
        }
        assertEquals(second.id, tm.telemetry.value.activeNode?.id)
        assertEquals(
            ErrorCategory.EXIT_COUNTRY_MISMATCH,
            com.example.core.ErrorClassifier.classify("Server exited in a different country").category
        )
    }

    @Test
    fun destroyedServiceDuringFailoverMovesManagerToTerminalError() = runBlocking {
        val first = profile("destroy-first")
        val second = profile("destroy-second")
        saveProfiles(first, second)
        val tm = createManager()
        val initialAttempt = connectAndVerify(tm, listOf(first, second))
        tm.onTunnelFailed(initialAttempt, "connection timed out")
        awaitUntil {
            tm.telemetry.value.connectionState == ConnectionState.FAILOVER &&
                tm.currentAttemptId() > initialAttempt
        }

        tm.onServiceDestroyed(tm.currentAttemptId())
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.ERROR }
        assertEquals(ErrorCategory.UNKNOWN, tm.telemetry.value.lastErrorCategory)
    }

    @Test
    fun alwaysOnStartDuringDisconnectingDoesNotReconnect() = runBlocking {
        val node = profile("disconnecting")
        saveProfiles(node)
        val tm = createManager()
        connectAndVerify(tm, listOf(node))

        tm.disconnect("user disconnect")
        assertEquals(ConnectionState.DISCONNECTING, tm.telemetry.value.connectionState)
        tm.onAlwaysOnStart()
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.DISCONNECTED }
        delay(100L)
        assertEquals(ConnectionState.DISCONNECTED, tm.telemetry.value.connectionState)
        assertNull(tm.telemetry.value.activeNode)
    }

    @Test
    fun unavailablePlatformPostureNeverClaimsVerifiedProtection() = runBlocking {
        val node = profile("posture-unavailable")
        saveProfiles(node)
        val tm = createManager(vpnPostureProvider = { null })
        connectAndVerify(tm, listOf(node), selectedCountryCode = "DE")

        assertEquals(VerificationState.PARTIAL, tm.telemetry.value.verificationState)
        assertTrue(tm.telemetry.value.connectivityVerified)
        assertTrue(tm.telemetry.value.exitVerified)
        assertFalse(tm.telemetry.value.vpnPostureAvailable)
        assertFalse(tm.telemetry.value.dnsLeakProtected)
        assertFalse(tm.telemetry.value.ipv6LeakProtected)
    }


    @Test
    fun trafficCountersAccumulateAndResetWithSession() = runBlocking {
        val profile = profile("traffic")
        saveProfiles(profile)
        val stats = AtomicReference(0L to 0L)
        val tm = createManager(trafficStats = { stats.get() })
        tm.setAppForegrounded(true)
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

    @Test
    fun backgroundTrafficSamplingPausesAndAccumulatesTotalsAndDurationOnResume() = runBlocking {
        val profile = profile("background-traffic")
        saveProfiles(profile)
        val stats = AtomicReference(0L to 0L)
        val statsReads = AtomicInteger()
        val tm = createManager(trafficStats = {
            statsReads.incrementAndGet()
            stats.get()
        })
        tm.setDetailsExpanded(true)
        connectAndVerify(tm, listOf(profile))

        delay(2_100L)
        assertEquals(1, statsReads.get())

        tm.setAppForegrounded(true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        awaitUntil {
            statsReads.get() > 1 && tm.telemetry.value.sessionDurationMs > 0L
        }
        tm.setAppForegrounded(false)
        delay(100L)
        val backgroundReadCount = statsReads.get()
        stats.set(5_000L to 7_000L)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        delay(2_100L)
        assertEquals(backgroundReadCount, statsReads.get())
        assertEquals(0L, tm.telemetry.value.rxBytes)
        assertEquals(0L, tm.telemetry.value.txBytes)

        tm.setAppForegrounded(true)
        awaitUntil {
            tm.telemetry.value.rxBytes == 5_000L && tm.telemetry.value.txBytes == 7_000L
        }
        assertTrue(tm.telemetry.value.sessionDurationMs >= 10_000L)
        assertTrue(tm.telemetry.value.rxBytesPerSec in 1L..999L)
        tm.disconnectAndAwait("background sampling test")
    }
}
