package com.example.vpn

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ExitCountryVerifier
import com.example.core.VpnPosture
import com.example.core.GeoIpDatabase
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthStatus
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import com.example.xraycore.TunnelConnectivityCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TunnelManagerExitVerificationTest {
    private lateinit var context: Application
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java).build()
        repository = LuxyVpnRepository(database, SettingsDataStore(context))
        runBlocking { repository.updateSettings { StabilitySettings() } }
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private fun node(id: String, name: String, country: String?, confidence: CountryConfidence) = ServerProfileEntity(
        id = id,
        name = name,
        address = "$id.example.com",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        countryCode = country,
        countryConfidence = confidence.name,
        countrySource = if (confidence == CountryConfidence.VERIFIED) CountrySource.EXIT_VERIFIED.name else CountrySource.METADATA.name
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
    private suspend fun publishOnline(tm: TunnelManager) {
        tm.onNetworkSnapshotForTest(NetworkSnapshot(true, "Wi-Fi", "wifi:test"))
        delay(50)
    }


    @Test
    fun selectedCountryRejectsWrongExitAndUnverifiedEstimatedThenConnectsOnVerifiedMatch() = runBlocking {
        val geoDb = GeoIpDatabase.fromCidrs(
            listOf(
                "203.0.113.0/24" to "NL",
                "198.51.100.0/24" to "DE"
            )
        )
        // Candidate 1: 204 succeeds, exit trace returns NL (mismatch for DE!)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("ip=203.0.113.5\nloc=NL\n"))
        // Candidate 2: 204 succeeds, exit trace returns 500 (unavailable for ESTIMATED -> rejected!)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(500))
        // Candidate 3: 204 succeeds, exit trace returns DE (verified match -> CONNECTED!)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("ip=198.51.100.9\nloc=DE\n"))

        val check = TunnelConnectivityCheck(
            endpoints = listOf(server.url("/generate_204").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        )
        val verifier = ExitCountryVerifier(
            endpoints = listOf(server.url("/trace").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L,
            geoIpLookup = { ip -> geoDb.lookupCountry(ip) }
        )
        val tm = TunnelManager(
            context = context,
            repository = repository,
            connectivityCheck = check,
            exitCountryVerifier = verifier,
            networkFingerprintProvider = { "wifi:test" },
            networkMonitorFactory = { app ->
                NetworkMonitor(app, snapshotProvider = { NetworkSnapshot(true, "Wi-Fi", "wifi:test") })
            },
            vpnPostureProvider = { VpnPosture(hasIpv6TunRoute = true, hasVpnDnsOnly = true) }
        )

        val c1 = node("c1", "🇩🇪 Fake DE", "DE", CountryConfidence.ESTIMATED)
        val c2 = node("c2", "🇩🇪 Unverified DE", "DE", CountryConfidence.ESTIMATED)
        val c3 = node("c3", "🇩🇪 Real DE", "DE", CountryConfidence.ESTIMATED)
        repository.saveProfiles(listOf(c1, c2, c3))

        tm.connectCandidates(listOf(c1, c2, c3), selectedCountryCode = "DE")
        awaitUntil { tm.telemetry.value.activeNode?.id == "c1" && tm.currentAttemptId() > 0L }
        publishOnline(tm)
        val a1 = tm.currentAttemptId()
        tm.onTunnelEstablished(a1)

        // Should reject c1 (NL != DE) and advance to c2
        awaitUntil { tm.telemetry.value.activeNode?.id == "c2" && tm.currentAttemptId() > a1 }
        assertNotEquals(ConnectionState.CONNECTED, tm.telemetry.value.connectionState)
        val a2 = tm.currentAttemptId()
        publishOnline(tm)
        tm.onTunnelEstablished(a2)

        // Should reject c2 (unverified estimated) and advance to c3
        awaitUntil { tm.telemetry.value.activeNode?.id == "c3" && tm.currentAttemptId() > a2 }
        assertNotEquals(ConnectionState.CONNECTED, tm.telemetry.value.connectionState)
        val a3 = tm.currentAttemptId()
        publishOnline(tm)
        tm.onTunnelEstablished(a3)

        // Should connect on c3 and persist verified exit info
        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.CONNECTED }
        assertEquals("c3", tm.telemetry.value.activeNode?.id)

        val stored = repository.getAllProfiles().associateBy { it.id }
        // c1 was updated with its true verified country NL so it won't be picked for DE again
        assertEquals("NL", stored.getValue("c1").countryCode)
        assertEquals(CountryConfidence.VERIFIED.name, stored.getValue("c1").countryConfidence)
        assertEquals(HealthStatus.WORKING.name, stored.getValue("c1").healthStatus)

        // c2 recorded EXIT_VERIFICATION_FAILED
        assertEquals(HealthStatus.FAILED.name, stored.getValue("c2").healthStatus)
        assertEquals(ErrorCategory.EXIT_VERIFICATION_FAILED.name, stored.getValue("c2").lastErrorCategory)

        // c3 recorded WORKING + VERIFIED DE + exitIp
        assertEquals(HealthStatus.WORKING.name, stored.getValue("c3").healthStatus)
        assertEquals("DE", stored.getValue("c3").countryCode)
        assertEquals(CountryConfidence.VERIFIED.name, stored.getValue("c3").countryConfidence)
        assertEquals("198.51.100.9", stored.getValue("c3").exitIp)

        tm.disconnectAndAwait("cleanup")
    }

    @Test
    fun automaticModeConnectsEvenWhenExitVerificationIsUnavailable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(500))

        val check = TunnelConnectivityCheck(
            endpoints = listOf(server.url("/generate_204").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        )
        val verifier = ExitCountryVerifier(
            endpoints = listOf(server.url("/trace").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        )
        val tm = TunnelManager(
            context = context,
            repository = repository,
            connectivityCheck = check,
            exitCountryVerifier = verifier,
            networkFingerprintProvider = { "wifi:test" },
            networkMonitorFactory = { app ->
                NetworkMonitor(app, snapshotProvider = { NetworkSnapshot(true, "Wi-Fi", "wifi:test") })
            },
            vpnPostureProvider = { VpnPosture(hasIpv6TunRoute = true, hasVpnDnsOnly = true) }
        )

        val unknown = node("u1", "Unlabeled", null, CountryConfidence.UNKNOWN)
        repository.saveProfiles(listOf(unknown))

        tm.connectCandidates(listOf(unknown), selectedCountryCode = null)
        awaitUntil { tm.telemetry.value.activeNode?.id == "u1" && tm.currentAttemptId() > 0L }
        publishOnline(tm)
        tm.onTunnelEstablished(tm.currentAttemptId())

        awaitUntil { tm.telemetry.value.connectionState == ConnectionState.CONNECTED }
        val stored = repository.getAllProfiles().single()
        assertEquals(HealthStatus.WORKING.name, stored.healthStatus)
        assertEquals(CountryConfidence.UNKNOWN.name, stored.countryConfidence)

        tm.disconnectAndAwait("cleanup")
    }
}
