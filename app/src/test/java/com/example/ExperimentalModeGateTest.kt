package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ProtocolGuardrails
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.ConnectionState
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TunnelTelemetry
import com.example.data.repository.LuxyVpnRepository
import com.example.ui.screens.AddEditNodeDialog
import com.example.ui.screens.ExperimentalModeControlSection
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.LuxyVpnTheme
import com.example.xraycore.AdaptiveRoutingEngine
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperimentalModeGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildRichVlessNode(isReality: Boolean = false): ServerProfileEntity =
        ServerProfileEntity(
            id = if (isReality) "gate-vless-reality" else "gate-vless-tls",
            name = if (isReality) "Gate REALITY" else "Gate TLS",
            protocol = ProxyProtocol.VLESS.name,
            address = "203.0.113.45",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = "XHTTP",
            security = if (isReality) SecurityType.REALITY.name else SecurityType.TLS.name,
            sni = "cdn.example.org",
            hostHeader = "cdn.example.org",
            path = "/xhttp",
            fingerprint = "unsafe",
            publicKey = if (isReality) "reality-pbk-abc123" else "",
            shortId = if (isReality) "deadbeef" else "",
            cipherSuites = "TLS_AES_128_GCM_SHA256",
            finalMask = "header-custom",
            echConfigList = "ech-config-blob-xyz",
            echForceQuery = "full",
            mldsa65Verify = if (isReality) "pqv-mldsa-verify-key" else ""
        )

    private fun buildExperimentalSettings(experimentalEnabled: Boolean): StabilitySettings =
        StabilitySettings(
            experimentalModeEnabled = experimentalEnabled,
            antiCensorshipPreset = AntiCensorshipPreset.STRICT_DPI_BYPASS,
            tlsFragmentEnabled = true,
            realityFragmentEnabled = true,
            fragmentHost = "frag.example.org",
            fragmentMaxSplit = "64-128",
            noiseEnabled = true,
            mixedCaseSni = true,
            tlsPaddingEnabled = true,
            utlsFingerprint = "random",
            cipherSuites = "TLS_CHACHA20_POLY1305_SHA256",
            finalMaskType = "header-custom",
            finalMaskFamily = "oob",
            echEnabled = true,
            echConfigList = "ech-settings-blob",
            echForceQuery = "full",
            failClosedEch = true,
            allowUnencryptedVless = true,
            chainingMode = ChainingMode.WARP_TO_PROXY,
            warpEndpoint = "162.159.193.99:2408",
            warpPrivateKey = "priv==",
            warpPublicKey = "pub==",
            awgJc = 7,
            awgJmin = 40,
            awgJmax = 90,
            awgS1 = 15,
            awgS2 = 25,
            awgH1 = 101,
            awgH2 = 102,
            awgH3 = 103,
            awgH4 = 104,
            warpNoisePreset = "quic",
            happyEyeballsEnabled = true,
            tcpHandshakeTimeoutMs = 9500,
            tlsHandshakeTimeoutMs = 9500,
            dnsPoolStrategy = DnsPoolStrategy.PARALLEL_FASTEST,
            dnsIntegrityFilterEnabled = true,
            adaptiveFallbackEnabled = true
        )

    @Test
    fun defaultSettingsOperateAsStandardXrayBaseline() {
        val defaults = StabilitySettings()
        assertFalse("experimentalModeEnabled must default to false", defaults.experimentalModeEnabled)
        assertEquals("Standard Xray Outbound", XrayConfigCompiler.describeDialerChain(defaults))
    }

    @Test
    fun compilerStrictlyExcludesAllExperimentalExtensionsWhenFlagIsFalse() {
        val tlsNode = buildRichVlessNode(isReality = false)
        val realityNode = buildRichVlessNode(isReality = true)
        val settingsOff = buildExperimentalSettings(experimentalEnabled = false)

        val compiledTls = XrayConfigCompiler.compileXrayConfigObject(tlsNode, settingsOff, emptyList())
        val outbounds = compiledTls.getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }

        assertFalse("fragment-out must be excluded in Standard mode", tags.contains("fragment-out"))
        assertFalse("noise-out must be excluded in Standard mode", tags.contains("noise-out"))
        assertFalse("warp-out must be excluded in Standard mode", tags.contains("warp-out"))
        assertEquals(listOf("proxy-out", "direct", "block"), tags)

        val proxyOut = outbounds.getJSONObject(0)
        val stream = proxyOut.getJSONObject("streamSettings")
        val tlsSettings = stream.getJSONObject("tlsSettings")
        val sockopt = stream.getJSONObject("sockopt")
        val xhttpSettings = stream.getJSONObject("xhttpSettings")

        assertEquals("chrome", tlsSettings.getString("fingerprint"))
        assertEquals("cdn.example.org", tlsSettings.getString("serverName"))
        assertFalse(tlsSettings.has("padding"))
        assertFalse(tlsSettings.has("tlsPadding"))
        assertFalse(tlsSettings.has("cipherSuites"))
        assertFalse(tlsSettings.has("echConfigList"))
        assertFalse(tlsSettings.has("echForceQuery"))
        assertFalse(tlsSettings.has("failClosedEch"))
        assertFalse(stream.has("finalmask"))
        assertFalse(sockopt.has("dialerProxy"))
        assertFalse(sockopt.has("happyEyeballs"))
        assertEquals("auto", xhttpSettings.getString("mode"))
        assertFalse(xhttpSettings.has("xPaddingBytes"))
        assertFalse(compiledTls.getJSONObject("dns").has("poolStrategy"))
        assertFalse(compiledTls.getJSONObject("dns").has("integrityFilter"))

        // Verify REALITY skips experimental fragment and mldsa65Verify when Experimental Mode is OFF
        val compiledReality = XrayConfigCompiler.compileXrayConfigObject(realityNode, settingsOff, emptyList())
        val realitySettings = compiledReality.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("realitySettings")
        assertEquals("chrome", realitySettings.getString("fingerprint"))
        assertFalse(realitySettings.has("fragment"))
        assertFalse(realitySettings.has("mldsa65Verify"))

        // Verify AmneziaWG extended parameters are stripped down to standard WireGuard
        val awgNode = ServerProfileEntity(
            id = "gate-awg",
            name = "Gate AWG",
            protocol = ProxyProtocol.WIREGUARD.name,
            address = "198.51.100.77",
            port = 2408,
            uuidOrPassword = "wg-priv==",
            publicKey = "wg-pub==",
            serviceName = "jc=8&jmin=40&jmax=80&s1=10&s2=20&h1=1&h2=2&h3=3&h4=4",
            finalMask = "awg",
            transport = "WG",
            security = SecurityType.NONE.name
        )
        val compiledWg = XrayConfigCompiler.compileXrayConfigObject(awgNode, settingsOff, emptyList())
        val wgSettings = compiledWg.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals("wireguard", compiledWg.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertFalse(wgSettings.has("jc"))
        assertFalse(wgSettings.has("jmin"))
        assertFalse(wgSettings.has("jmax"))
        assertFalse(wgSettings.has("s1"))
        assertFalse(wgSettings.has("h1"))
        assertFalse(wgSettings.has("shaping"))

        fun node(proto: ProxyProtocol) = ServerProfileEntity(
            id = "gate-${proto.name}",
            name = proto.name,
            protocol = proto.name,
            address = "198.51.100.88",
            port = 443,
            uuidOrPassword = "secret",
            publicKey = "pub",
            hostHeader = "t.example.org"
        )

        // MASQUE is implemented but gated: standard mode emits a blackhole instead of leaking.
        val gated = XrayConfigCompiler.compileXrayConfigObject(
            node(ProxyProtocol.MASQUE), settingsOff, emptyList()
        )
        val gatedProxyOut = gated.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("blackhole", gatedProxyOut.getString("protocol"))
        assertTrue(gatedProxyOut.optBoolean("experimentalGated", false))

        // DNSTT / MasterDNS have no outbound in the bundled core (no symbols in libgojni.so),
        // so they must refuse in every mode instead of starting a blackhole tunnel.
        for (proto in listOf(ProxyProtocol.DNSTT, ProxyProtocol.MASTERDNS)) {
            val failure = runCatching {
                XrayConfigCompiler.compileXrayConfigObject(node(proto), settingsOff, emptyList())
            }.exceptionOrNull()
            assertTrue("$proto must refuse", failure is IllegalStateException)
            assertTrue(
                "message for $proto: ${failure?.message}",
                failure?.message?.contains("not compiled") == true
            )
        }
    }

    @Test
    fun compilerRestoresExperimentalFeaturesAndDataStorePreservesSubSettingsAcrossToggles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore = SettingsDataStore(context)
        val db = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val repository = LuxyVpnRepository(db, dataStore)
            val adaptive = AdaptiveRoutingEngine(repository)

            // Save custom experimental preferences while flag is OFF
            dataStore.updateSettings {
                buildExperimentalSettings(experimentalEnabled = false)
            }
            val savedOff = dataStore.settingsFlow.first()
            assertFalse(savedOff.experimentalModeEnabled)
            assertTrue(savedOff.tlsFragmentEnabled)
            assertEquals(ChainingMode.WARP_TO_PROXY, savedOff.chainingMode)
            assertTrue(savedOff.failClosedEch)

            // Adaptive stage stays locked at Stage 0 while Experimental Mode is OFF
            assertEquals(0, adaptive.onNetworkChanged("Wi-Fi", experimentalModeEnabled = false))
            val (escalatedOff, _) = adaptive.escalateStage(savedOff)
            assertEquals(0, escalatedOff)
            assertEquals("Standard Xray", adaptive.state.value.stageLabel)

            // Toggle Experimental Mode ON: all saved sub-settings remain intact & compile
            dataStore.updateSettings { it.copy(experimentalModeEnabled = true) }
            val savedOn = dataStore.settingsFlow.first()
            assertTrue(savedOn.experimentalModeEnabled)
            assertTrue(savedOn.tlsFragmentEnabled)
            assertEquals(ChainingMode.WARP_TO_PROXY, savedOn.chainingMode)

            val tlsNode = buildRichVlessNode(isReality = false)
            val compiledOn = XrayConfigCompiler.compileXrayConfigObject(tlsNode, savedOn, emptyList())
            val tagsOn = (0 until compiledOn.getJSONArray("outbounds").length()).map {
                compiledOn.getJSONArray("outbounds").getJSONObject(it).getString("tag")
            }
            assertTrue(tagsOn.contains("warp-out"))
            assertTrue(tagsOn.contains("fragment-out"))

            val (stage2, _) = adaptive.setStage(2, savedOn)
            assertEquals(2, stage2)

            // Toggle Experimental Mode back OFF: saved sub-settings remain untouched, but compilation reverts
            dataStore.updateSettings { it.copy(experimentalModeEnabled = false) }
            val savedOffAgain = dataStore.settingsFlow.first()
            assertFalse(savedOffAgain.experimentalModeEnabled)
            assertTrue(savedOffAgain.tlsFragmentEnabled)
            assertEquals(ChainingMode.WARP_TO_PROXY, savedOffAgain.chainingMode)
            assertTrue(savedOffAgain.failClosedEch)

            val compiledOffAgain = XrayConfigCompiler.compileXrayConfigObject(tlsNode, savedOffAgain, emptyList())
            val tagsOffAgain = (0 until compiledOffAgain.getJSONArray("outbounds").length()).map {
                compiledOffAgain.getJSONArray("outbounds").getJSONObject(it).getString("tag")
            }
            assertFalse(tagsOffAgain.contains("warp-out"))
            assertFalse(tagsOffAgain.contains("fragment-out"))

            adaptive.syncWithExperimentalMode(false)
            assertEquals(0, adaptive.state.value.currentStage)
            assertEquals("Standard Xray", adaptive.state.value.stageLabel)
        } finally {
            dataStore.updateSettings { StabilitySettings() }
            db.close()
        }
    }

    @Test
    fun uiScreensCleanlyHideAndRevealExperimentalControlsOnToggle() {
        var experimentalState by mutableStateOf(false)

        composeRule.setContent {
            LuxyVpnTheme() {
                ExperimentalModeControlSection(
                    experimentalModeEnabled = experimentalState,
                    onToggleExperimentalMode = { experimentalState = it },
                    onOpenExpertLab = {}
                )
            }
        }

        // Initially OFF: clean Standard Xray subtitle, switch off, Expert Lab card hidden
        composeRule.onNodeWithText("Experimental Mode").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Disabled — Running as a clean, standard Xray client. Turn on to unlock advanced transport tuning, framing, ECH, chaining, and diagnostic lab tools."
        ).assertIsDisplayed()
        composeRule.onNode(isToggleable()).assertIsOff()
        assertEquals(0, composeRule.onAllNodesWithText("Advanced / Expert Lab").fetchSemanticsNodes().size)

        // Toggle ON: unlocked subtitle and Expert Lab card appear
        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()
        assertTrue(experimentalState)
        composeRule.onNode(isToggleable()).assertIsOn()
        composeRule.onNodeWithText("Enabled — Experimental transport extensions and adaptive features are unlocked.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Advanced / Expert Lab").assertIsDisplayed()

        // Toggle back OFF: Expert Lab card disappears immediately
        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()
        assertFalse(experimentalState)
        assertEquals(0, composeRule.onAllNodesWithText("Advanced / Expert Lab").fetchSemanticsNodes().size)
    }

    @Test
    fun tunnelScreenAndNodeDialogGateExperimentalUiElements() {
        var experimentalState by mutableStateOf(false)
        var showNodeDialog by mutableStateOf(false)

        composeRule.setContent {
            LuxyVpnTheme() {
                TunnelScreen(
                    telemetry = TunnelTelemetry(
                        connectionState = ConnectionState.DISCONNECTED,
                        adaptiveStageLabel = "Stage 2 • CDN + Fail-Closed ECH"
                    ),
                    activeNode = buildRichVlessNode(isReality = true),
                    settings = buildExperimentalSettings(experimentalEnabled = experimentalState),
                    onToggleConnect = {},
                    onOpenNodes = {},
                    onOpenSpeedTest = {},
                    onOpenExpertLab = {},
                    subscription = null,
                    onRefreshSubscription = {}
                )
                if (showNodeDialog) {
                    AddEditNodeDialog(
                        initialNode = buildRichVlessNode(isReality = true),
                        experimentalModeEnabled = experimentalState,
                        onDismiss = {},
                        onSave = {}
                    )
                }
            }
        }

        // Home no longer prints the core subtitle or adaptive stage. Speed Test stays reachable.
        composeRule.onNodeWithText("LuxyNet").assertIsDisplayed()
        composeRule.onNodeWithText("Speed Test").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Standard Xray Core").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Expert Lab").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Stage 2 • CDN + Fail-Closed ECH").fetchSemanticsNodes().size)

        // Open Node Dialog in Standard mode: experimental protocols & fields hidden
        showNodeDialog = true
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("AmneziaWG").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("MASQUE").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("DNSTT").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("MasterDNS").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("ML-DSA-65 Verify Key (pqv, optional)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("FinalMask / Framing (fm, optional)").fetchSemanticsNodes().size)
        assertTrue(ProtocolGuardrails.isStandardProtocolKind(com.example.data.model.ProtocolFormKind.VLESS))

        // Experimental controls stay off the home surface until the overflow menu is opened.
        experimentalState = true
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Stage 2 • CDN + Fail-Closed ECH").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Expert Lab").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("AmneziaWG").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("MASQUE").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("DNSTT").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("MasterDNS").fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            composeRule.onAllNodesWithText("ML-DSA-65 Verify Key (pqv, optional)")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithText("FinalMask / Framing (fm, optional)")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
