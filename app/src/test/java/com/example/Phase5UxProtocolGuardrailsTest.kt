package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.core.ProtocolGuardrails
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.ChainingMode
import com.example.data.model.ConnectionState
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.data.model.TunnelTelemetry
import com.example.ui.LuxyVpnUiState
import com.example.ui.screens.ExpertLabScreen
import com.example.ui.screens.NodeEditorDialog
import com.example.ui.screens.StabilityRulesScreen
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.LuxyVpnTheme
import com.example.vpn.TunnelManager
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5UxProtocolGuardrailsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun nodeEditorDynamicallyShowsOnlyRelevantFieldsPerProtocolTransportAndSecurity() {
        composeTestRule.setContent {
            LuxyVpnTheme {
                NodeEditorDialog(
                    existing = null,
                    onDismiss = {},
                    onSave = {}
                )
            }
        }

        // Default VLESS + TCP + TLS: Flow is shown, Path/Host & WireGuard keys are hidden
        composeTestRule.onNodeWithTag("editor_flow_chip_none").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_ciphersuites_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_ech_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_allow_insecure_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_path_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_wg_pubkey_input").assertDoesNotExist()

        // Switch transport to XHTTP -> Path & Host appear
        composeTestRule.onNodeWithTag("editor_transport_chip_xhttp").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("editor_path_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_host_input").performScrollTo().assertIsDisplayed()

        // Switch transport to gRPC -> Path hides and gRPC Service Name appears
        composeTestRule.onNodeWithTag("editor_transport_chip_grpc").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("editor_path_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_grpc_service_input").performScrollTo().assertIsDisplayed()

        // Switch security to REALITY -> pbk/sid appear, TLS-only CipherSuites/ECH/AllowInsecure hide
        composeTestRule.onNodeWithTag("editor_security_chip_reality").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("editor_reality_pbk_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_reality_sid_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_ciphersuites_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_ech_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_allow_insecure_switch").assertDoesNotExist()

        // Switch protocol to WireGuard -> Flow & REALITY fields hide, WireGuard Peer PubKey appears
        composeTestRule.onNodeWithTag("proto_chip_wireguard").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("editor_flow_chip_none").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_reality_sid_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("editor_wg_pubkey_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_jc_input").assertDoesNotExist()

        // Switch protocol to AmneziaWG -> AWG Jc/Jmin/Jmax/S1/S2/H1 appear
        composeTestRule.onNodeWithTag("proto_chip_amnezia_wg").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("editor_awg_jc_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_jmin_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_jmax_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_s1_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_s2_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_awg_h1_input").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nodeEditorBlocksSaveUntilPortUuidAndWireGuardKeysAreValid() {
        var saved: ServerProfileEntity? = null

        composeTestRule.setContent {
            LuxyVpnTheme {
                NodeEditorDialog(
                    existing = null,
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }

        // Initially invalid (empty host and UUID) -> Save disabled
        composeTestRule.onNodeWithTag("save_node_button").assertIsNotEnabled()

        // Enter invalid port and malformed UUID -> inline errors shown, Save still disabled
        composeTestRule.onNodeWithTag("editor_address_input").performScrollTo().performTextInput("edge.example.com")
        composeTestRule.onNodeWithTag("editor_port_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("editor_port_input").performTextInput("99999")
        composeTestRule.onNodeWithTag("editor_secret_input").performScrollTo().performTextInput("not-a-valid-uuid")

        composeTestRule.onNodeWithTag("editor_port_input").performScrollTo()
        composeTestRule.onNodeWithTag("node_validation_error_port", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_secret_input").performScrollTo()
        composeTestRule.onNodeWithTag("node_validation_error_uuid", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_node_button").assertIsNotEnabled()

        // Fix port & UUID -> Save enabled and emits sanitized profile
        composeTestRule.onNodeWithTag("editor_port_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("editor_port_input").performTextInput("443")
        composeTestRule.onNodeWithTag("editor_secret_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("editor_secret_input").performTextInput("11111111-2222-3333-4444-555555555555")

        composeTestRule.onNodeWithTag("save_node_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("save_node_button").performClick()

        assertNotNull(saved)
        assertEquals("edge.example.com", saved?.address)
        assertEquals(443, saved?.port)
        assertEquals("11111111-2222-3333-4444-555555555555", saved?.uuidOrPassword)
    }

    @Test
    fun wireGuardKeyValidationEnforces32ByteBase64Keys() {
        val validKey = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)
        val validPeer = Base64.encodeToString(ByteArray(32) { (it + 10).toByte() }, Base64.NO_WRAP)

        assertTrue(ProtocolGuardrails.isValidWireGuardKey(validKey))
        assertFalse(ProtocolGuardrails.isValidWireGuardKey("short-key"))
        assertFalse(ProtocolGuardrails.isValidWireGuardKey("not_32_bytes_base64_value"))

        var savedWg: ServerProfileEntity? = null
        composeTestRule.setContent {
            LuxyVpnTheme {
                NodeEditorDialog(
                    existing = null,
                    onDismiss = {},
                    onSave = { savedWg = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("proto_chip_wireguard").performClick()
        composeTestRule.onNodeWithTag("editor_address_input").performTextInput("engage.cloudflareclient.com")
        composeTestRule.onNodeWithTag("editor_secret_input").performTextInput("invalid-key")
        composeTestRule.onNodeWithTag("editor_wg_pubkey_input").performTextInput("invalid-peer")
        composeTestRule.onNodeWithTag("save_node_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("editor_secret_input").performTextClearance()
        composeTestRule.onNodeWithTag("editor_secret_input").performTextInput(validKey)
        composeTestRule.onNodeWithTag("editor_wg_pubkey_input").performTextClearance()
        composeTestRule.onNodeWithTag("editor_wg_pubkey_input").performTextInput(validPeer)

        composeTestRule.onNodeWithTag("save_node_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("save_node_button").performClick()

        assertNotNull(savedWg)
        assertEquals(ProxyProtocol.WIREGUARD.name, savedWg?.protocol)
        assertEquals(2408, savedWg?.port)
    }

    @Test
    fun settingsDataStoreAndCompilerEnforceMutualExclusionsAndProtocolGuardrails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        // Enabling TLS Fragmentation clears TFO
        store.setTcpFastOpen(true)
        assertTrue(store.settingsFlow.first().tcpFastOpen)

        store.setTlsFragmentEnabled(true)
        val afterFragment = store.settingsFlow.first()
        assertTrue(afterFragment.tlsFragmentEnabled)
        assertFalse(afterFragment.tcpFastOpen)

        // Enabling TFO clears TLS Fragmentation
        store.setTcpFastOpen(true)
        val afterTfo = store.settingsFlow.first()
        assertTrue(afterTfo.tcpFastOpen)
        assertFalse(afterTfo.tlsFragmentEnabled)

        // Datagram protocol strips fragment-out, mux, and TFO in XrayConfigCompiler
        val wgNode = ServerProfileEntity(
            id = "wg-1",
            name = "WG Test",
            protocol = ProxyProtocol.WIREGUARD.name,
            address = "162.159.192.1",
            port = 2408,
            uuidOrPassword = Base64.encodeToString(ByteArray(32) { 1 }, Base64.NO_WRAP),
            publicKey = Base64.encodeToString(ByteArray(32) { 2 }, Base64.NO_WRAP),
            transport = TransportType.WG.name,
            security = SecurityType.NONE.name
        )
        val hostileSettings = StabilitySettings(
            tlsFragmentEnabled = true,
            muxEnabled = true,
            tcpFastOpen = true,
            chainingMode = ChainingMode.WARP_TO_PROXY
        )
        val compiledWg = JSONObject(XrayConfigCompiler.compile(wgNode, hostileSettings, emptyList()))
        val outbounds = compiledWg.getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
        assertFalse("Datagram WireGuard must not emit fragment-out", tags.contains("fragment-out"))
        assertFalse("WireGuard must not chain via warp-out", tags.contains("warp-out"))
        val proxyOutbound = outbounds.getJSONObject(0)
        assertFalse("Mux must be omitted on datagram node", proxyOutbound.has("mux"))

        // Prevent NODE_TO_NODE self-chaining & WARP chaining on DNSTT
        val selfPair = ProtocolGuardrails.sanitizeChaining(ChainingMode.NODE_TO_NODE, "wg-1", wgNode)
        assertEquals(ChainingMode.NODE_TO_NODE, selfPair.first)
        assertNull(selfPair.second)

        val dnsttNode = wgNode.copy(id = "dns-1", protocol = ProxyProtocol.DNSTT.name, hostHeader = "t.example.com")
        val warpBlocked = ProtocolGuardrails.sanitizeChaining(ChainingMode.WARP_TO_PROXY, null, dnsttNode)
        assertEquals(ChainingMode.NONE, warpBlocked.first)

        // REALITY ignores cipherSuites, allowInsecure, ECH, and mixedCaseSni
        val realityNode = ServerProfileEntity(
            id = "rel-1",
            name = "REALITY Node",
            protocol = ProxyProtocol.VLESS.name,
            address = "1.2.3.4",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            security = SecurityType.REALITY.name,
            sni = "www.cloudflare.com",
            publicKey = "pbk123",
            shortId = "ab12",
            allowInsecure = true,
            cipherSuites = "TLS_AES_128_GCM_SHA256",
            echConfigList = "AQID"
        )
        val realitySettings = StabilitySettings(
            mixedCaseSni = true,
            echEnabled = true,
            echConfigList = "AQID",
            cipherSuites = "TLS_AES_128_GCM_SHA256"
        )
        val compiledReality = JSONObject(XrayConfigCompiler.compile(realityNode, realitySettings, emptyList()))
        val realityStream = compiledReality.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val realityObj = realityStream.getJSONObject("realitySettings")
        assertEquals("www.cloudflare.com", realityObj.getString("serverName"))
        assertFalse(realityStream.has("tlsSettings"))
    }

    @Test
    fun tunnelScreenRemainsMinimalistWithoutExpertClutterAndGatesForegroundTelemetry() {
        val node = ServerProfileEntity(
            id = "node-1",
            name = "Zurich Edge",
            protocol = ProxyProtocol.VLESS.name,
            address = "zurich.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            latencyMs = 42
        )
        val uiState = LuxyVpnUiState(
            servers = listOf(node),
            selectedNode = node,
            settings = StabilitySettings(routingMode = RoutingMode.RULE_BASED),
            telemetry = TunnelTelemetry(
                connectionState = ConnectionState.CONNECTED,
                activeNode = node,
                connectedSinceMs = System.currentTimeMillis() - 65_000L,
                currentRttMs = 42,
                downloadBps = 128_000L,
                uploadBps = 24_000L,
                adaptiveStage = 2,
                adaptiveStageLabel = "CDN Edge / Strict Fail-Closed ECH",
                activeNetworkFingerprint = "deadbeef12345678"
            )
        )

        composeTestRule.setContent {
            LuxyVpnTheme {
                TunnelScreen(
                    uiState = uiState,
                    onToggleConnection = {},
                    onImportClipboard = {},
                    onOpenManualAdd = {},
                    onOpenSubscriptions = {},
                    onNavigateToNodes = {},
                    onProbeSelected = {},
                    onSelectFastest = {},
                    onSetRoutingMode = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("power_orb_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("active_server_card"))
        composeTestRule.onNodeWithTag("active_server_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zurich Edge").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("mode_smart_auto_chip"))
        composeTestRule.onNodeWithTag("mode_smart_auto_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_global_chip").assertIsDisplayed()

        // Ensure expert diagnostics / hashes are NOT cluttered onto main screen
        composeTestRule.onNodeWithText("#DEAD1234", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Escalation", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("LuxyVpnCore", substring = true).assertDoesNotExist()

        // Foreground telemetry gate check
        TunnelManager.setAppForeground(false)
        assertFalse(TunnelManager.isAppInForeground())
        TunnelManager.setAppForeground(true)
        assertTrue(TunnelManager.isAppInForeground())
    }

    @Test
    fun categorizedSettingsNavigatesToExpertLabAndDisplaysCompatibilityNotice() {
        val wgNode = ServerProfileEntity(
            id = "wg-2",
            name = "WARP Edge",
            protocol = ProxyProtocol.WIREGUARD.name,
            address = "162.159.192.1",
            port = 2408,
            uuidOrPassword = Base64.encodeToString(ByteArray(32) { 1 }, Base64.NO_WRAP),
            publicKey = Base64.encodeToString(ByteArray(32) { 2 }, Base64.NO_WRAP)
        )
        val uiState = LuxyVpnUiState(
            servers = listOf(wgNode),
            selectedNode = wgNode,
            settings = StabilitySettings(tlsFragmentEnabled = true, tcpFastOpen = true)
        )

        var openedExpertLab = false
        composeTestRule.setContent {
            LuxyVpnTheme {
                StabilityRulesScreen(
                    uiState = uiState,
                    onSetThemeMode = {},
                    onSetColorPalette = {},
                    onSetAutoConnectOnLaunch = {},
                    onSetAutoReconnect = {},
                    onSetSmartFailover = {},
                    onSetMtu = {},
                    onSetRoutingMode = {},
                    onToggleRule = { _, _ -> },
                    onSaveRule = {},
                    onDeleteRule = {},
                    onSetSplitTunnelConfig = { _, _, _ -> },
                    onSetPrivacyOpSecConfig = { _, _, _, _ -> },
                    onOpenExpertLab = { openedExpertLab = true }
                )
            }
        }

        composeTestRule.onNodeWithText("General & Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-Connect on Launch").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Routing & Split Tunnel"))
        composeTestRule.onNodeWithText("Routing & Split Tunnel").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Privacy & Security"))
        composeTestRule.onNodeWithText("Privacy & Security").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open_expert_lab_button"))
        composeTestRule.onNodeWithTag("open_expert_lab_button").performClick()
        assertTrue(openedExpertLab)
    }

    @Test
    fun expertLabDisplaysProtocolCompatibilityNoticeForDatagramNode() {
        val wgNode = ServerProfileEntity(
            id = "wg-3",
            name = "WARP Edge",
            protocol = ProxyProtocol.WIREGUARD.name,
            address = "162.159.192.1",
            port = 2408,
            uuidOrPassword = Base64.encodeToString(ByteArray(32) { 1 }, Base64.NO_WRAP),
            publicKey = Base64.encodeToString(ByteArray(32) { 2 }, Base64.NO_WRAP)
        )
        val uiState = LuxyVpnUiState(
            servers = listOf(wgNode),
            selectedNode = wgNode,
            settings = StabilitySettings(tlsFragmentEnabled = true, tcpFastOpen = true)
        )

        composeTestRule.setContent {
            LuxyVpnTheme {
                ExpertLabScreen(
                    uiState = uiState,
                    onNavigateBack = {},
                    onApplyPreset = {},
                    onSetProbeInterval = {},
                    onSetMuxEnabled = {},
                    onSetMuxConcurrency = {},
                    onSetTlsFragment = {},
                    onSetTcpFastOpen = {},
                    onSetFragmentParams = { _, _, _, _, _ -> },
                    onSetNoiseEnabled = {},
                    onSetNoiseParams = { _, _, _, _ -> },
                    onSetUtlsFingerprint = {},
                    onSetFakeDns = {},
                    onSetSniffing = {},
                    onSetBlockQuicUdp = {},
                    onSetXhttpConfig = { _, _ -> },
                    onSetCipherSuites = {},
                    onSetFinalMaskType = {},
                    onSetEchConfig = { _, _, _ -> },
                    onSetHappyEyeballs = {},
                    onSetAllowUnencryptedVless = {},
                    onTestFragmentedTls = {},
                    onOpenCleanIpScanner = {},
                    onOpenWarpBenchmark = {},
                    onOpenRealitySniCheck = {},
                    onOpenEchCheck = {},
                    onOpenXrayConfigInspector = {},
                    onSetChainingConfig = { _, _ -> },
                    onSetWarpConfig = { _, _, _, _, _ -> },
                    onSetAmneziaWgParams = { _, _, _, _, _, _, _, _, _ -> },
                    onSetWarpNoisePreset = {},
                    onSetAdvancedTlsOptions = { _, _, _ -> },
                    onSetSocketMaskAndTimeouts = { _, _, _ -> },
                    onSetAdaptiveRoutingConfig = { _, _, _ -> },
                    onClearNetworkStrategies = {},
                    onReprobeOrResetStage = {},
                    onSaveDns = { _, _, _, _ -> },
                    onTestDns = {},
                    onVerifyDoh = {},
                    onSetDnsPoolConfig = { _, _, _, _, _, _ -> },
                    onBenchmarkDnsPool = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("expert_lab_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("expert_compat_notices_card").assertIsDisplayed()
    }
}
