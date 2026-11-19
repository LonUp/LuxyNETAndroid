package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.CdnAndAnycastDetector
import com.example.core.CountryDetector
import com.example.core.ExitCountryVerifier
import com.example.core.GeoIpDatabase
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthStatus
import com.example.data.model.HealthPolicy
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import com.example.vpn.TunnelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.File
import java.net.InetAddress
import java.net.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CountryDetectionAndVerificationTest {
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

    private fun node(
        id: String,
        name: String,
        address: String,
        security: String = "TLS",
        transport: String = "TCP",
        sni: String = "",
        hostHeader: String = ""
    ) = ServerProfileEntity(
        id = id,
        name = name,
        address = address,
        port = 443,
        protocol = "VLESS",
        security = security,
        transport = transport,
        sni = sni,
        hostHeader = hostHeader,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        publicKey = if (security == "REALITY") "pbk-test" else ""
    )

    @Test
    fun bundledGeoIpAssetParsesAndResolvesIpv4Ipv6AndPrivateRanges() {
        val assetFile = File("src/main/assets/geoip.dat").takeIf { it.isFile }
            ?: File("app/src/main/assets/geoip.dat")
        assertTrue("Expected bundled geoip.dat at ${assetFile.absolutePath}", assetFile.isFile)
        val geoDb = assetFile.inputStream().use { GeoIpDatabase.fromStream(it) }
        val (v4Count, v6Count) = geoDb.entryCounts()
        assertTrue(v4Count > 500_000)
        assertTrue(v6Count > 700_000)

        assertEquals("IR", geoDb.lookupCountry("5.160.0.1"))
        assertEquals("NL", geoDb.lookupCountry("91.108.4.1"))
        assertNull(geoDb.lookupCountry("192.168.1.1"))
        assertNull(geoDb.lookupCountry("10.0.0.1"))
        assertNull(geoDb.lookupCountry("::1"))
    }

    @Test
    fun cdnAnycastAndMultiHopAreNotMisattributedByEndpointGeoIp() = runBlocking {
        val geoDb = GeoIpDatabase.fromCidrs(
            listOf(
                "104.16.0.0/12" to "US",
                "198.51.100.0/24" to "DE",
                "5.160.0.0/16" to "IR"
            )
        )
        val detector = CountryDetector(
            geoIpLookup = { addr -> geoDb.lookupCountry(addr) },
            dnsResolver = { host ->
                when (host) {
                    "direct-de.example" -> listOf(InetAddress.getByName("198.51.100.10"))
                    "split.example" -> listOf(
                        InetAddress.getByName("198.51.100.10"),
                        InetAddress.getByName("5.160.0.10")
                    )
                    "partial.example" -> listOf(
                        InetAddress.getByName("198.51.100.10"),
                        InetAddress.getByName("192.0.2.10")
                    )
                    "cf.workers.dev" -> listOf(InetAddress.getByName("104.16.1.1"))
                    else -> emptyList()
                }
            }
        )

        // Direct IP literal -> ESTIMATED / GEOIP
        val directIp = detector.detectOffline(node("1", "node-raw-24", "198.51.100.24"))
        assertEquals("DE", directIp.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, directIp.countryConfidence)
        assertEquals(CountrySource.GEOIP.name, directIp.countrySource)

        // Cloudflare IP -> skipped -> UNKNOWN
        val cfIp = detector.detectOffline(node("2", "cf-node", "104.16.1.1"))
        assertNull(cfIp.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, cfIp.countryConfidence)

        // Multi-hop arrow with unlabeled exit -> skipped -> UNKNOWN
        val relay = detector.detectOffline(node("3", "🇮🇷 -> Relay 01", "5.160.0.1"))
        assertNull(relay.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, relay.countryConfidence)

        // WS+TLS fronted by CDN hostHeader -> skipped -> UNKNOWN
        val wsCdn = detector.detectOffline(
            node("4", "ws-node", "198.51.100.24", transport = "WS", hostHeader = "edge.workers.dev")
        )
        assertNull(wsCdn.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, wsCdn.countryConfidence)

        // REALITY with www.cloudflare.com SNI camouflage on direct VPS IP -> still resolves VPS IP!
        val reality = detector.detectOffline(
            node("5", "reality-node", "198.51.100.24", security = "REALITY", sni = "www.cloudflare.com")
        )
        assertEquals("DE", reality.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, reality.countryConfidence)

        // Stage 2 DNS resolution: single country -> ESTIMATED, conflicting countries -> UNKNOWN
        val domainDe = detector.detectWithEndpointGeoIp(node("6", "unlabeled", "direct-de.example"))
        assertEquals("DE", domainDe.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, domainDe.countryConfidence)

        val domainSplit = detector.detectWithEndpointGeoIp(node("7", "unlabeled", "split.example"))
        assertNull(domainSplit.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, domainSplit.countryConfidence)

        val partialGeoIp = detector.detectWithEndpointGeoIp(node("8", "unlabeled", "partial.example"))
        assertNull(partialGeoIp.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, partialGeoIp.countryConfidence)


        assertTrue(CdnAndAnycastDetector.isCdnOrAnycastHost("my.workers.dev"))
        assertFalse(CdnAndAnycastDetector.isCdnOrAnycastHost("198.51.100.24"))
    }

    @Test
    fun offlineDetectionExpiresOldVerifiedCountriesAndFallsBackSafely() {
        val nowMs = System.currentTimeMillis()
        val detector = CountryDetector(geoIpLookup = { "US" })
        val staleVerified = node("stale", "🇩🇪 Germany", "198.51.100.10").copy(
            countryCode = "FR",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countrySource = CountrySource.EXIT_VERIFIED.name,
            countryVerifiedAt = nowMs - HealthPolicy.EXIT_VERIFICATION_STALE_MS - 1L,
            exitIp = "203.0.113.5"
        )

        val metadata = detector.detectOffline(staleVerified, nowMs)
        assertEquals("DE", metadata.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, metadata.countryConfidence)
        assertEquals(CountrySource.METADATA.name, metadata.countrySource)
        assertNull(metadata.countryVerifiedAt)
        assertNull(metadata.exitIp)

        val manual = detector.detectOffline(staleVerified.copy(manualCountryCode = "NL"), nowMs)
        assertEquals("NL", manual.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, manual.countryConfidence)
        assertEquals(CountrySource.MANUAL.name, manual.countrySource)

        val withoutTimestamp = detector.detectOffline(
            staleVerified.copy(name = "🇺🇸 United States", countryVerifiedAt = null),
            nowMs
        )
        assertEquals("US", withoutTimestamp.countryCode)
        assertEquals(CountryConfidence.ESTIMATED.name, withoutTimestamp.countryConfidence)
        assertEquals(CountrySource.METADATA.name, withoutTimestamp.countrySource)

        val unknown = detector.detectOffline(staleVerified.copy(name = "Unlabeled"), nowMs)
        assertNull(unknown.countryCode)
        assertEquals(CountryConfidence.UNKNOWN.name, unknown.countryConfidence)
        assertEquals(CountrySource.NONE.name, unknown.countrySource)
        assertNull(unknown.countryVerifiedAt)
        assertNull(unknown.exitIp)

        val fresh = staleVerified.copy(countryVerifiedAt = nowMs)
        assertEquals(fresh, detector.detectOffline(fresh, nowMs))
    }

    @Test
    fun exitCountryVerifierParsesTraceAndPlainIpAndRejectsPrivateIps() = runBlocking {
        val geoDb = GeoIpDatabase.fromCidrs(listOf("203.0.113.0/24" to "NL"))
        server.enqueue(MockResponse().setBody("fl=123\nip=203.0.113.42\nloc=NL\n"))
        val verifier = ExitCountryVerifier(
            endpoints = listOf(server.url("/cdn-cgi/trace").toString()),
            proxy = Proxy.NO_PROXY,
            geoIpLookup = { ip -> geoDb.lookupCountry(ip) }
        )
        val res = verifier.verifyExit()
        assertNotNull(res)
        assertEquals("203.0.113.42", res?.exitIp)
        assertEquals("NL", res?.countryCode)

        // Private IP must be rejected
        server.enqueue(MockResponse().setBody("192.168.1.10\n"))
        val privateRes = verifier.verifyExit()
        assertNull(privateRes)
    }

}
