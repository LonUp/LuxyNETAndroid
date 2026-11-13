package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.EncryptedConfigVault
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.LuxyVpnDatabase
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.xraycore.AdaptiveRoutingEngine
import com.example.xraycore.EchProbeResult
import com.example.xraycore.LiveStallTracker
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.MultiResolverDnsEngine
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase4AdaptiveEchTest {

    private lateinit var db: LuxyVpnDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun networkFingerprintingPersistsRecallsPerNetworkAndRedactsLockedEndpoints() = runBlocking {
        val dao = db.networkStrategyDao()
        val wifiDescriptor = NetworkDescriptorFactory.wifi("LuxyVPN-Lab", "aa:bb:cc:dd:ee:01")
        val lteDescriptor = NetworkDescriptorFactory.cellular("43211", "13")

        val wifiFp = AdaptiveRoutingEngine.computeFingerprint(wifiDescriptor)
        val lteFp = AdaptiveRoutingEngine.computeFingerprint(lteDescriptor)
        assertEquals(64, wifiFp.length)
        assertEquals(64, lteFp.length)
        assertNotEquals(wifiFp, lteFp)
        assertEquals(wifiFp.take(8).uppercase(), AdaptiveRoutingEngine.shortFingerprint(wifiFp))

        val lockedNode = ServerProfileEntity(
            id = "locked-vless-1",
            name = "Locked Secret Edge",
            protocol = ProxyProtocol.VLESS.name,
            address = "secret-edge.hidden.example",
            port = 8443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            security = SecurityType.REALITY.name,
            sni = "www.cloudflare.com",
            isLocked = true,
            vaultKeyScheme = EncryptedConfigVault.SCHEME_X25519
        )
        val unlockedNode = lockedNode.copy(
            id = "open-vless-1",
            address = "198.51.100.42",
            port = 443,
            isLocked = false,
            vaultKeyScheme = ""
        )

        // Wi-Fi learns Stage 2 with a locked profile -> endpoint MUST be redacted!
        AdaptiveRoutingEngine.recordStageOutcome(
            dao = dao,
            fingerprint = wifiFp,
            transportType = "Wi-Fi",
            stage = 2,
            success = true,
            activeNode = lockedNode,
            nowMs = 1000L
        )
        // Cellular records a stall at Stage 0 then succeeds at Stage 3 with unlocked node
        AdaptiveRoutingEngine.recordStageOutcome(
            dao = dao,
            fingerprint = lteFp,
            transportType = "Cellular",
            stage = 0,
            success = false,
            activeNode = unlockedNode,
            nowMs = 2000L
        )
        AdaptiveRoutingEngine.recordStageOutcome(
            dao = dao,
            fingerprint = lteFp,
            transportType = "Cellular",
            stage = 3,
            success = true,
            activeNode = unlockedNode,
            nowMs = 3000L
        )

        val recalledWifi = dao.getByFingerprint(wifiFp)!!
        assertEquals(2, recalledWifi.preferredEscalationLevel)
        assertEquals(AntiCensorshipPreset.CDN_FRONTING.name, recalledWifi.preferredPreset)
        assertEquals(EncryptedConfigVault.REDACTED_VALUE, recalledWifi.lastCleanEndpoint)
        assertFalse(recalledWifi.lastCleanEndpoint.contains("secret-edge.hidden.example"))
        assertFalse(recalledWifi.lastCleanEndpoint.contains("8443"))

        val recalledLte = dao.getByFingerprint(lteFp)!!
        assertEquals(3, recalledLte.preferredEscalationLevel)
        assertEquals(1, recalledLte.stallCount)
        assertEquals(1, recalledLte.successCount)
        assertEquals("198.51.100.42:443", recalledLte.lastCleanEndpoint)

        dao.clearAll()
        assertNull(dao.getByFingerprint(wifiFp))
        assertNull(dao.getByFingerprint(lteFp))
    }

    @Test
    fun liveStallTrackerDetectsZeroDownlinkStallsAndIgnoresHealthyOrIdleFlows() {
        val tracker = LiveStallTracker(stallThresholdMs = 6000L)

        // Idle network (0 tx, 0 rx) must never trigger stall
        assertFalse(tracker.sample(deltaTxBytes = 0L, deltaRxBytes = 0L, nowMs = 1_000L))
        assertFalse(tracker.sample(deltaTxBytes = 0L, deltaRxBytes = 0L, nowMs = 10_000L))

        // Uplink active, zero downlink starts stall timer
        assertFalse(tracker.sample(deltaTxBytes = 1200L, deltaRxBytes = 0L, nowMs = 11_000L))
        assertFalse(tracker.sample(deltaTxBytes = 800L, deltaRxBytes = 0L, nowMs = 15_000L))

        // Downlink response resets stall timer cleanly
        assertFalse(tracker.sample(deltaTxBytes = 500L, deltaRxBytes = 4096L, nowMs = 16_500L))

        // Sustained zero-downlink with uplink activity past 6s triggers stall
        assertFalse(tracker.sample(deltaTxBytes = 900L, deltaRxBytes = 0L, nowMs = 20_000L))
        assertTrue(tracker.sample(deltaTxBytes = 900L, deltaRxBytes = 0L, nowMs = 26_100L))
    }

    @Test
    fun fiveStageFallbackLadderCompilesAndSelectsDnsFallbackWhilePreservingLockRedaction() {
        val baseSettings = StabilitySettings(
            warpPublicKey = "warpPubKeyBase64Test==",
            warpEndpoint = "162.159.192.1:2408",
            chainingMode = ChainingMode.NONE
        )
        val primaryNode = ServerProfileEntity(
            id = "vless-main",
            name = "Primary Locked Edge",
            protocol = ProxyProtocol.VLESS.name,
            address = "locked-main.secret.org",
            port = 443,
            uuidOrPassword = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            transport = TransportType.XHTTP.name,
            security = SecurityType.TLS.name,
            sni = "cdn.locked.secret",
            isLocked = true,
            vaultKeyScheme = EncryptedConfigVault.SCHEME_PBKDF2
        )
        val dnsttNode = ServerProfileEntity(
            id = "dnstt-backup",
            name = "DNSTT Emergency Backup",
            protocol = ProxyProtocol.DNSTT.name,
            address = "1.1.1.1",
            port = 53,
            uuidOrPassword = "key",
            publicKey = "pubHex99",
            hostHeader = "t.backup.example",
            encryption = "udp"
        )

        // Stage 0: baseline
        val s0 = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, 0)
        assertFalse(s0.tlsFragmentEnabled)

        // Stage 1: Fragment + FinalMask + Mixed-Case SNI
        val s1 = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, 1)
        assertTrue(s1.tlsFragmentEnabled)
        assertTrue(s1.mixedCaseSni)
        assertEquals("header-custom", s1.finalMaskType)

        // Stage 2: CDN + Strict Fail-Closed ECH
        val s2 = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, 2)
        assertTrue(s2.echEnabled)
        assertEquals("full", s2.echForceQuery)
        assertTrue(s2.failClosedEch)
        val stage2Json = JSONObject(XrayConfigCompiler.compile(primaryNode.copy(isLocked = false), s2, emptyList()))
        val stage2Tls = stage2Json.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("full", stage2Tls.getString("echForceQuery"))
        assertTrue(stage2Tls.getBoolean("failClosedEch"))

        // Stage 3: WARP / AmneziaWG multi-hop wrap
        val s3 = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, 3, hasWarpConfigured = true)
        assertEquals(ChainingMode.WARP_TO_PROXY, s3.chainingMode)
        assertTrue(s3.awgJc >= 6)

        // Stage 4: DNS Transport Fallback & Parallel DoH
        val s4 = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, 4)
        assertEquals(DnsPoolStrategy.PARALLEL_FASTEST, s4.dnsPoolStrategy)
        assertTrue(s4.dnsIntegrityFilterEnabled)
        assertTrue(s4.noiseEnabled)
        val fallbackDnsNode = AdaptiveRoutingEngine.selectFallbackDnsTunnelNode(listOf(primaryNode, dnsttNode))
        assertNotNull(fallbackDnsNode)
        assertEquals("dnstt-backup", fallbackDnsNode!!.id)

        // Locked node stays redacted across all adaptive stages
        for (stage in 0..4) {
            val st = AdaptiveRoutingEngine.applyStageToSettings(baseSettings, stage)
            val redacted = XrayConfigCompiler.compileRedactedForInspector(primaryNode, st, emptyList())
            assertFalse(redacted.contains("locked-main.secret.org"))
            assertFalse(redacted.contains("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            assertTrue(redacted.contains(EncryptedConfigVault.REDACTED_VALUE))
        }
    }

    @Test
    fun strictFailClosedEchAbortsOnMissingOrMalformedKeysAndAcceptsValidEch() = runBlocking {
        // Build a valid RFC 9460 framed ECHConfigList payload:
        // [0..1] = 46 (remaining length), [2..3] = 0xFE0D (ECH version), [4..5] = 42 (config contents length)
        val validBytes = ByteArray(48) { (it + 7).toByte() }.apply {
            this[0] = 0x00
            this[1] = 46.toByte()
            this[2] = 0xFE.toByte()
            this[3] = 0x0D.toByte()
            this[4] = 0x00
            this[5] = 42.toByte()
        }
        val validEchB64 = Base64.getEncoder().encodeToString(validBytes)
        assertTrue(MultiResolverDnsEngine.validateEchConfigList(validEchB64))
        assertFalse(MultiResolverDnsEngine.validateEchConfigList("not-valid-base64!!!"))
        assertFalse(MultiResolverDnsEngine.validateEchConfigList("AQID")) // too short (<16B)

        // 1. Valid inline ECH config passes in fail-closed mode
        val inlinePass = LuxyVpnCore.verifyStrictEchReadiness(
            outerSni = "cloudflare-ech.com",
            inlineEchConfigList = validEchB64,
            failClosed = true
        )
        assertTrue(inlinePass.allowed)
        assertTrue(inlinePass.enforcedFailClosed)
        assertEquals(validEchB64, inlinePass.echConfigList)

        // 2. Malformed inline ECH aborts immediately in fail-closed mode (no plaintext SNI downgrade)
        val malformedAbort = LuxyVpnCore.verifyStrictEchReadiness(
            outerSni = "cloudflare-ech.com",
            inlineEchConfigList = "bad@@payload",
            failClosed = true
        )
        assertFalse(malformedAbort.allowed)
        assertTrue(malformedAbort.enforcedFailClosed)
        assertTrue(malformedAbort.error!!.contains("downgrade blocked", ignoreCase = true))

        // 3. DoH HTTPS RR type-65 with valid ech=<b64> passes
        val dohPass = LuxyVpnCore.verifyStrictEchReadiness(
            outerSni = "cloudflare-ech.com",
            inlineEchConfigList = "",
            failClosed = true
        ) { domain, _ ->
            EchProbeResult(
                domain = domain,
                echSupported = true,
                httpsRecords = listOf("1 . alpn=h2 ech=$validEchB64"),
                rttMs = 31
            )
        }
        assertTrue(dohPass.allowed)
        assertEquals(validEchB64, dohPass.echConfigList)

        // 4. Empty / censored HTTPS RR aborts when failClosed=true, allows fallback when failClosed=false
        val emptyProbe: suspend (String, String) -> EchProbeResult = { domain, _ ->
            EchProbeResult(domain = domain, echSupported = false, httpsRecords = emptyList(), rttMs = 45, error = "No HTTPS RR")
        }
        val missingAbort = LuxyVpnCore.verifyStrictEchReadiness(
            outerSni = "blocked-ech.example",
            failClosed = true,
            customProbe = emptyProbe
        )
        assertFalse(missingAbort.allowed)
        assertTrue(missingAbort.enforcedFailClosed)

        val permissiveFallback = LuxyVpnCore.verifyStrictEchReadiness(
            outerSni = "blocked-ech.example",
            failClosed = false,
            customProbe = emptyProbe
        )
        assertTrue(permissiveFallback.allowed)
        assertFalse(permissiveFallback.enforcedFailClosed)
    }

    @Test
    fun settingsDataStorePersistsAdaptiveRoutingAndAutoEnforcesFailClosedOnFullEch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.setAdaptiveRoutingConfig(
            adaptiveFallbackEnabled = false,
            failClosedEch = true,
            stallThresholdSec = 6
        )
        var loaded = store.settingsFlow.first()
        assertFalse(loaded.adaptiveFallbackEnabled)
        assertTrue(loaded.failClosedEch)
        assertEquals(6, loaded.stallThresholdSec)

        // Setting echEnabled=true & echForceQuery="full" auto-enforces failClosedEch=true
        store.setAdaptiveRoutingConfig(
            adaptiveFallbackEnabled = true,
            failClosedEch = false,
            stallThresholdSec = 12
        )
        store.setEchConfig(enabled = true, configList = null, forceQuery = "full")
        loaded = store.settingsFlow.first()
        assertTrue(loaded.adaptiveFallbackEnabled)
        assertEquals(12, loaded.stallThresholdSec)
        assertTrue(loaded.echEnabled)
        assertEquals("full", loaded.echForceQuery)
        assertTrue(loaded.failClosedEch)
    }

    private object NetworkDescriptorFactory {
        fun wifi(ssid: String, bssid: String) = com.example.xraycore.NetworkDescriptor(
            transportType = "Wi-Fi",
            wifiSsid = ssid,
            wifiBssid = bssid
        )

        fun cellular(operator: String, radioType: String) = com.example.xraycore.NetworkDescriptor(
            transportType = "Cellular",
            cellularOperator = operator,
            radioType = radioType
        )
    }
}
