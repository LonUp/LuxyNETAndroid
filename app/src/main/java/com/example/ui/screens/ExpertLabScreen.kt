package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.ProtocolGuardrails
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.StallSensitivity
import com.example.ui.LuxyVpnUiState
import com.example.ui.components.Formatters
import com.example.ui.theme.LocalTunnelStatusColors
import com.example.xraycore.LuxyVpnCore
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpertLabScreen(
    uiState: LuxyVpnUiState,
    onNavigateBack: () -> Unit,
    onApplyPreset: (AntiCensorshipPreset) -> Unit,
    onSetProbeInterval: (Int) -> Unit,
    onSetMuxEnabled: (Boolean) -> Unit,
    onSetMuxConcurrency: (Int) -> Unit,
    onSetTlsFragment: (Boolean) -> Unit,
    onSetTcpFastOpen: (Boolean) -> Unit,
    onSetFragmentParams: (packets: String, length: String, interval: String, host: String?, maxSplit: String?) -> Unit,
    onSetNoiseEnabled: (Boolean) -> Unit,
    onSetNoiseParams: (packetSize: String, delayMs: String, noiseType: String?, applyTo: String?) -> Unit,
    onSetUtlsFingerprint: (String) -> Unit,
    onSetFakeDns: (Boolean) -> Unit,
    onSetSniffing: (Boolean) -> Unit,
    onSetBlockQuicUdp: (Boolean) -> Unit,
    onSetXhttpConfig: (mode: String, padding: String) -> Unit,
    onSetCipherSuites: (String) -> Unit,
    onSetFinalMaskType: (String) -> Unit,
    onSetEchConfig: (enabled: Boolean, configList: String?, forceQuery: String?) -> Unit,
    onSetHappyEyeballs: (Boolean) -> Unit,
    onSetAllowUnencryptedVless: (Boolean) -> Unit,
    onTestFragmentedTls: () -> Unit,
    onOpenCleanIpScanner: () -> Unit,
    onOpenWarpBenchmark: () -> Unit,
    onOpenRealitySniCheck: () -> Unit,
    onOpenEchCheck: () -> Unit,
    onOpenXrayConfigInspector: () -> Unit,
    onSetChainingConfig: (ChainingMode, String?) -> Unit,
    onSetWarpConfig: (endpoint: String, privateKey: String, publicKey: String, reserved: String, localAddress: String) -> Unit,
    onSetAmneziaWgParams: (jc: Int, jmin: Int, jmax: Int, s1: Int, s2: Int, h1: Long, h2: Long, h3: Long, h4: Long) -> Unit,
    onSetWarpNoisePreset: (String) -> Unit,
    onSetAdvancedTlsOptions: (mixedCaseSni: Boolean, tlsPaddingEnabled: Boolean, realityFragmentEnabled: Boolean) -> Unit,
    onSetSocketMaskAndTimeouts: (finalMaskFamily: String, tcpTimeoutMs: Int, tlsTimeoutMs: Int) -> Unit,
    onSetAdaptiveRoutingConfig: (adaptiveFallbackEnabled: Boolean, failClosedEch: Boolean, stallThresholdSec: Int) -> Unit,
    onClearNetworkStrategies: () -> Unit,
    onReprobeOrResetStage: () -> Unit,
    onSaveDns: (primary: String, fallback: String, doh: Boolean, dohUrl: String) -> Unit,
    onTestDns: () -> Unit,
    onVerifyDoh: () -> Unit,
    onSetDnsPoolConfig: (
        strategy: DnsPoolStrategy,
        integrityFilterEnabled: Boolean,
        dotServer: String,
        dotEnabled: Boolean,
        tcpEnabled: Boolean,
        udpEnabled: Boolean
    ) -> Unit,
    onBenchmarkDnsPool: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)

    val settings = uiState.settings
    val telemetry = uiState.telemetry
    val statusColors = LocalTunnelStatusColors.current
    val compat = remember(settings, uiState.selectedNode) {
        ProtocolGuardrails.evaluateAndSanitizeSettings(settings, uiState.selectedNode)
    }

    var fragmentHostInput by remember(settings.fragmentHost) { mutableStateOf(settings.fragmentHost) }
    var echConfigInput by remember(settings.echConfigList) { mutableStateOf(settings.echConfigList) }
    var warpEndpointInput by remember(settings.warpEndpoint) { mutableStateOf(settings.warpEndpoint) }
    var warpReservedInput by remember(settings.warpReserved) { mutableStateOf(settings.warpReserved) }
    var awgJcInput by remember(settings.awgJc) { mutableStateOf(settings.awgJc.toString()) }
    var awgJminInput by remember(settings.awgJmin) { mutableStateOf(settings.awgJmin.toString()) }
    var awgJmaxInput by remember(settings.awgJmax) { mutableStateOf(settings.awgJmax.toString()) }
    var awgS1Input by remember(settings.awgS1) { mutableStateOf(settings.awgS1.toString()) }
    var awgS2Input by remember(settings.awgS2) { mutableStateOf(settings.awgS2.toString()) }
    var awgH1Input by remember(settings.awgH1) { mutableStateOf(settings.awgH1.toString()) }

    var primaryDns by remember(settings.primaryDns) { mutableStateOf(settings.primaryDns) }
    var fallbackDns by remember(settings.fallbackDns) { mutableStateOf(settings.fallbackDns) }
    var dohEnabled by remember(settings.dohEnabled) { mutableStateOf(settings.dohEnabled) }
    var dohUrl by remember(settings.dohServerUrl) { mutableStateOf(settings.dohServerUrl) }
    var dotServer by remember(settings.dnsDotServer) { mutableStateOf(settings.dnsDotServer) }
    var dotEnabled by remember(settings.dnsDotEnabled) { mutableStateOf(settings.dnsDotEnabled) }
    var tcpDnsEnabled by remember(settings.dnsTcpEnabled) { mutableStateOf(settings.dnsTcpEnabled) }
    var udpDnsEnabled by remember(settings.dnsUdpEnabled) { mutableStateOf(settings.dnsUdpEnabled) }
    var integrityFilter by remember(settings.dnsIntegrityFilterEnabled) {
        mutableStateOf(settings.dnsIntegrityFilterEnabled)
    }

    val awgJminVal = awgJminInput.toIntOrNull() ?: -1
    val awgJmaxVal = awgJmaxInput.toIntOrNull() ?: -1
    val awgRangeValid = awgJminVal in 0..awgJmaxVal && awgJmaxVal <= 1280

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("expert_lab_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Expert Lab", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = LuxyVpnCore.CORE_VERSION,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("expert_lab_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Settings",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Active Protocol Compatibility Notices
                if (compat.notices.isNotEmpty()) {
                    item(key = "compat_notices") {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = statusColors.connecting.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, statusColors.connecting.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .testTag("expert_compat_notices_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Warning,
                                        contentDescription = null,
                                        tint = statusColors.connecting,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Active Node Guardrails",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = statusColors.connecting,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                compat.notices.forEach { notice ->
                                    Text(
                                        text = "• $notice",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // 1. Adaptive Engine & Stage Inspector
                item(key = "adaptive_inspector") {
                    val activeSensitivity = StallSensitivity.fromSeconds(settings.stallThresholdSec)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Adaptive Engine & Stage Inspector",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "Stage ${telemetry.adaptiveStage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val fingerprintShort = telemetry.activeNetworkFingerprint.takeIf { it.isNotBlank() }?.take(8) ?: "standby"
                                    Text(
                                        text = "Network: ${telemetry.networkTransport} (#$fingerprintShort) • Learned: ${uiState.networkStrategies.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Escalation: Stage ${telemetry.adaptiveStage} (${telemetry.adaptiveStageLabel}) • Chain: ${telemetry.activeDialerChain}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onReprobeOrResetStage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reprobe_stage_button")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Re-Probe & Reset Stage 0", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            LabSwitchRow(
                                title = "Per-Network Adaptive Auto-Fallback",
                                subtitle = "Steps Stages 0–4 on stalls without tearing down TUN",
                                checked = settings.adaptiveFallbackEnabled,
                                onCheckedChange = { enabled ->
                                    onSetAdaptiveRoutingConfig(enabled, settings.failClosedEch, settings.stallThresholdSec)
                                },
                                tag = "adaptive_fallback_switch"
                            )

                            LabSwitchRow(
                                title = "Strict Fail-Closed ECH Enforcement",
                                subtitle = "Aborts handshake rather than downgrading ECH to plaintext SNI",
                                checked = settings.failClosedEch,
                                enabled = !compat.isRealityNode && !compat.isDatagramNode,
                                onCheckedChange = { enabled ->
                                    onSetAdaptiveRoutingConfig(settings.adaptiveFallbackEnabled, enabled, settings.stallThresholdSec)
                                },
                                tag = "fail_closed_ech_switch"
                            )

                            Text("Stall Detection Sensitivity", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StallSensitivity.entries.forEach { sensitivity ->
                                    FilterChip(
                                        selected = activeSensitivity == sensitivity,
                                        onClick = {
                                            onSetAdaptiveRoutingConfig(
                                                settings.adaptiveFallbackEnabled,
                                                settings.failClosedEch,
                                                sensitivity.thresholdSec
                                            )
                                        },
                                        label = { Text(sensitivity.label, maxLines = 1) },
                                        modifier = Modifier.testTag("stall_sensitivity_${sensitivity.thresholdSec}")
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onClearNetworkStrategies,
                                enabled = uiState.networkStrategies.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("clear_network_strategies_button")
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Learned Strategies (${uiState.networkStrategies.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // 2. Anti-Censorship Presets & Diagnostic Toolkit
                item(key = "presets_and_toolkit") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text("Anti-Censorship Presets & Toolkit", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Text(
                                text = settings.antiCensorshipPreset.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    AntiCensorshipPreset.BALANCED_STEALTH,
                                    AntiCensorshipPreset.STRICT_DPI_BYPASS,
                                    AntiCensorshipPreset.CDN_FRONTING,
                                    AntiCensorshipPreset.MAX_STEALTH,
                                    AntiCensorshipPreset.DIRECT_LOW_LATENCY
                                ).forEach { preset ->
                                    FilterChip(
                                        selected = settings.antiCensorshipPreset == preset,
                                        onClick = { onApplyPreset(preset) },
                                        label = { Text(preset.label, maxLines = 1) },
                                        modifier = Modifier.testTag("preset_${preset.name.lowercase()}")
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenCleanIpScanner,
                                    modifier = Modifier.weight(1f).testTag("open_clean_ip_scanner_button")
                                ) {
                                    Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Clean IPs", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onOpenRealitySniCheck,
                                    modifier = Modifier.weight(1f).testTag("open_reality_sni_button")
                                ) {
                                    Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("REALITY SNI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenEchCheck,
                                    modifier = Modifier.weight(1f).testTag("open_ech_check_button")
                                ) {
                                    Text("ECH DNS", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onOpenXrayConfigInspector,
                                    modifier = Modifier.weight(1f).testTag("inspect_xray_json_button")
                                ) {
                                    Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Xray JSON", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onTestFragmentedTls,
                                    enabled = !uiState.isTestingFragment,
                                    modifier = Modifier.weight(1f).testTag("test_fragment_tls_button")
                                ) {
                                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (uiState.isTestingFragment) "Probing…" else "Test Fragment", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onOpenWarpBenchmark,
                                    modifier = Modifier.weight(1f).testTag("open_warp_benchmark_button")
                                ) {
                                    Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("WARP Ping", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            uiState.lastFragmentProbe?.let { probe ->
                                val color = if (probe.success) statusColors.connected else statusColors.error
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = color.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (probe.success)
                                            "Fragment TLS OK: ${probe.rttMs} ms • ${probe.fragmentsSent} TCP segments • ${probe.tlsProtocol}"
                                        else "Fragment TLS failed: ${probe.error}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Multi-Hop Outbound Chaining & WARP / AmneziaWG / MASQUE
                item(key = "chaining_and_warp") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, statusColors.connected.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Route, contentDescription = null, tint = statusColors.connected, modifier = Modifier.size(20.dp))
                                Text("Multi-Hop Chaining & WARP / AWG", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                text = settings.chainingMode.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChainingMode.entries.forEach { mode ->
                                    val allowed = (mode != ChainingMode.WARP_TO_PROXY && mode != ChainingMode.PROXY_TO_WARP) ||
                                        compat.isWarpChainingAllowed
                                    FilterChip(
                                        selected = settings.chainingMode == mode,
                                        enabled = allowed,
                                        onClick = { onSetChainingConfig(mode, settings.relayNodeId) },
                                        label = { Text(mode.label, maxLines = 1) },
                                        modifier = Modifier.testTag("chaining_mode_${mode.name.lowercase()}")
                                    )
                                }
                            }

                            if (settings.chainingMode == ChainingMode.NODE_TO_NODE || settings.chainingMode == ChainingMode.PROXY_TO_WARP) {
                                val candidates = uiState.servers.filter { it.id != uiState.selectedNode?.id }
                                if (candidates.isNotEmpty()) {
                                    Text("Relay / Secondary Hop Node", style = MaterialTheme.typography.labelMedium)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        candidates.forEach { candidate ->
                                            FilterChip(
                                                selected = settings.relayNodeId == candidate.id,
                                                onClick = { onSetChainingConfig(settings.chainingMode, candidate.id) },
                                                label = { Text(candidate.name, maxLines = 1) }
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Add a second node in Nodes tab to select a distinct relay hop.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text("WARP / MASQUE Datagram Shaping", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LuxyVpnCore.DATAGRAM_SHAPING_PRESETS.forEach { preset ->
                                    FilterChip(
                                        selected = settings.warpNoisePreset == preset,
                                        onClick = { onSetWarpNoisePreset(preset) },
                                        label = { Text(preset.uppercase(), maxLines = 1) },
                                        modifier = Modifier.testTag("shaping_preset_$preset")
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = warpEndpointInput,
                                    onValueChange = { warpEndpointInput = it },
                                    label = { Text("WARP Endpoint (ip:port)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.4f)
                                )
                                OutlinedTextField(
                                    value = warpReservedInput,
                                    onValueChange = { warpReservedInput = it },
                                    label = { Text("Reserved") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.8f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        onSetWarpConfig(
                                            warpEndpointInput,
                                            settings.warpPrivateKey,
                                            settings.warpPublicKey,
                                            warpReservedInput,
                                            settings.warpLocalAddress
                                        )
                                    }
                                ) {
                                    Text("Save")
                                }
                            }

                            Text("AmneziaWG Junk Padding (Jc / Jmin-Jmax / S1-S2 / H1)", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = awgJcInput,
                                    onValueChange = { awgJcInput = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Jc") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = awgJminInput,
                                    onValueChange = { awgJminInput = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Jmin") },
                                    singleLine = true,
                                    isError = !awgRangeValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = awgJmaxInput,
                                    onValueChange = { awgJmaxInput = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Jmax") },
                                    singleLine = true,
                                    isError = !awgRangeValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!awgRangeValid) {
                                Text(
                                    text = "Requires 0 <= Jmin <= Jmax <= 1280",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = awgS1Input,
                                    onValueChange = { awgS1Input = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("S1") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = awgS2Input,
                                    onValueChange = { awgS2Input = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("S2") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = awgH1Input,
                                    onValueChange = { awgH1Input = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("H1") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    enabled = awgRangeValid,
                                    onClick = {
                                        onSetAmneziaWgParams(
                                            awgJcInput.toIntOrNull() ?: settings.awgJc,
                                            awgJminInput.toIntOrNull() ?: settings.awgJmin,
                                            awgJmaxInput.toIntOrNull() ?: settings.awgJmax,
                                            awgS1Input.toIntOrNull() ?: settings.awgS1,
                                            awgS2Input.toIntOrNull() ?: settings.awgS2,
                                            awgH1Input.toLongOrNull() ?: settings.awgH1,
                                            settings.awgH2,
                                            settings.awgH3,
                                            settings.awgH4
                                        )
                                    },
                                    modifier = Modifier.testTag("save_awg_params_button")
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }

                // 4. Xray Core DPI Evasion & Socket Parameters
                item(key = "xray_dpi_parameters") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Xray Core Anti-DPI Parameters", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            LabSwitchRow(
                                title = "TLS ClientHello Fragmentation",
                                subtitle = if (compat.isDatagramNode) "Unavailable on datagram transport"
                                else if (settings.tcpFastOpen) "Mutually exclusive with TFO (turns TFO off)"
                                else "Splits ClientHello via dialerProxy to bypass SNI DPI",
                                checked = settings.tlsFragmentEnabled,
                                enabled = !compat.isDatagramNode,
                                onCheckedChange = onSetTlsFragment,
                                tag = "stability_fragment_switch"
                            )

                            if (settings.tlsFragmentEnabled && !compat.isDatagramNode) {
                                Text("Fragment Packet Mode", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("tlshello", "1-3", "1-5").forEach { mode ->
                                        FilterChip(
                                            selected = settings.fragmentPackets == mode,
                                            onClick = { onSetFragmentParams(mode, settings.fragmentLength, settings.fragmentInterval, null, null) },
                                            label = { Text(mode, maxLines = 1) }
                                        )
                                    }
                                }

                                Text("Segment Length (Bytes)", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("3-10", "5-12", "10-20", "20-40", "50-100").forEach { len ->
                                        FilterChip(
                                            selected = settings.fragmentLength == len,
                                            onClick = { onSetFragmentParams(settings.fragmentPackets, len, settings.fragmentInterval, null, null) },
                                            label = { Text("${len}B", maxLines = 1) }
                                        )
                                    }
                                }

                                Text("Inter-Segment Delay & MaxSplit", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("5-15", "8-18", "10-20", "20-40").forEach { delayRange ->
                                        FilterChip(
                                            selected = settings.fragmentInterval == delayRange,
                                            onClick = { onSetFragmentParams(settings.fragmentPackets, settings.fragmentLength, delayRange, null, null) },
                                            label = { Text("${delayRange}ms", maxLines = 1) }
                                        )
                                    }
                                    listOf("16-32", "32-64", "64-128", "128-256").forEach { split ->
                                        FilterChip(
                                            selected = settings.fragmentMaxSplit == split,
                                            onClick = {
                                                onSetFragmentParams(settings.fragmentPackets, settings.fragmentLength, settings.fragmentInterval, null, split)
                                            },
                                            label = { Text("MaxSplit $split", maxLines = 1) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = fragmentHostInput,
                                        onValueChange = { fragmentHostInput = it },
                                        label = { Text("Fragment Host SNI Override") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            onSetFragmentParams(
                                                settings.fragmentPackets,
                                                settings.fragmentLength,
                                                settings.fragmentInterval,
                                                fragmentHostInput,
                                                null
                                            )
                                        }
                                    ) {
                                        Text("Set")
                                    }
                                }
                            }

                            LabSwitchRow(
                                title = "TCP Fast Open (TFO)",
                                subtitle = if (compat.isDatagramNode) "Unavailable on datagram transport"
                                else if (settings.tlsFragmentEnabled) "Mutually exclusive with Fragmentation (turns Fragment off)"
                                else "Reduces setup latency during mobile reconnects",
                                checked = settings.tcpFastOpen,
                                enabled = !compat.isDatagramNode,
                                onCheckedChange = onSetTcpFastOpen,
                                tag = "stability_tfo_switch"
                            )

                            LabSwitchRow(
                                title = "Mux / XMux Connection Pooling",
                                subtitle = if (compat.isDatagramNode) "Disabled for datagram transport" else "Multiplex TCP streams to reduce handshake overhead",
                                checked = settings.muxEnabled,
                                enabled = !compat.isDatagramNode,
                                onCheckedChange = onSetMuxEnabled,
                                tag = "stability_mux_switch"
                            )

                            if (settings.muxEnabled && !compat.isDatagramNode) {
                                Column {
                                    Text(
                                        text = "Mux Concurrency: ${settings.muxConcurrency} streams",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Slider(
                                        value = settings.muxConcurrency.toFloat(),
                                        onValueChange = { onSetMuxConcurrency(it.roundToInt()) },
                                        valueRange = 1f..16f,
                                        steps = 14
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            LabSwitchRow(
                                title = "UDP / Packet Noise Obfuscation",
                                subtitle = "Emits typed padding bursts prior to handshakes",
                                checked = settings.noiseEnabled,
                                onCheckedChange = onSetNoiseEnabled,
                                tag = "xray_noise_switch"
                            )

                            if (settings.noiseEnabled) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("rand", "str", "hex", "base64").forEach { nType ->
                                        FilterChip(
                                            selected = settings.noiseType == nType,
                                            onClick = { onSetNoiseParams(settings.noisePacketSize, settings.noiseDelayMs, nType, null) },
                                            label = { Text(nType, maxLines = 1) }
                                        )
                                    }
                                    listOf("10-20", "24-64", "50-100").forEach { pkt ->
                                        FilterChip(
                                            selected = settings.noisePacketSize == pkt,
                                            onClick = { onSetNoiseParams(pkt, settings.noiseDelayMs, null, null) },
                                            label = { Text("${pkt}B", maxLines = 1) }
                                        )
                                    }
                                    listOf("ip", "ipv4", "ipv6").forEach { target ->
                                        FilterChip(
                                            selected = settings.noiseApplyTo == target,
                                            onClick = { onSetNoiseParams(settings.noisePacketSize, settings.noiseDelayMs, null, target) },
                                            label = { Text(target.uppercase(), maxLines = 1) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text("FinalMask Post-Encryption Camouflage", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LuxyVpnCore.FINAL_MASK_PRESETS.forEach { mask ->
                                    FilterChip(
                                        selected = settings.finalMaskType == mask,
                                        onClick = { onSetFinalMaskType(mask) },
                                        label = { Text(mask, maxLines = 1) },
                                        modifier = Modifier.testTag("finalmask_${mask.lowercase()}")
                                    )
                                }
                            }

                            Text("FinalMask Address Family Target", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LuxyVpnCore.FINAL_MASK_FAMILIES.forEach { family ->
                                    FilterChip(
                                        selected = settings.finalMaskFamily == family,
                                        onClick = {
                                            onSetSocketMaskAndTimeouts(family, settings.tcpHandshakeTimeoutMs, settings.tlsHandshakeTimeoutMs)
                                        },
                                        label = { Text(family, maxLines = 1) },
                                        modifier = Modifier.testTag("finalmask_family_$family")
                                    )
                                }
                            }

                            Text("TLS CipherSuites Enforcement", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LuxyVpnCore.CIPHER_SUITE_PRESETS.forEach { (label, value) ->
                                    FilterChip(
                                        selected = settings.cipherSuites == value,
                                        enabled = !compat.isRealityNode,
                                        onClick = { onSetCipherSuites(value) },
                                        label = { Text(label, maxLines = 1) }
                                    )
                                }
                            }

                            LabSwitchRow(
                                title = "Mixed-Case SNI Formatting",
                                subtitle = if (compat.isRealityNode || compat.isDatagramNode) "Ignored for REALITY & datagram protocols" else "Randomizes ASCII case in ClientHello SNI",
                                checked = settings.mixedCaseSni,
                                enabled = !compat.isRealityNode && !compat.isDatagramNode,
                                onCheckedChange = { enabled ->
                                    onSetAdvancedTlsOptions(enabled, settings.tlsPaddingEnabled, settings.realityFragmentEnabled)
                                },
                                tag = "xray_mixed_case_sni_switch"
                            )

                            LabSwitchRow(
                                title = "RFC 7685 TLS ClientHello Padding",
                                subtitle = "Appends length-hiding padding extension to ClientHello",
                                checked = settings.tlsPaddingEnabled,
                                onCheckedChange = { enabled ->
                                    onSetAdvancedTlsOptions(settings.mixedCaseSni, enabled, settings.realityFragmentEnabled)
                                },
                                tag = "xray_tls_padding_switch"
                            )

                            LabSwitchRow(
                                title = "REALITY Handshake Fragmentation",
                                subtitle = "Applies dialerProxy fragmentation on REALITY handshakes",
                                checked = settings.realityFragmentEnabled,
                                enabled = !compat.isDatagramNode,
                                onCheckedChange = { enabled ->
                                    onSetAdvancedTlsOptions(settings.mixedCaseSni, settings.tlsPaddingEnabled, enabled)
                                },
                                tag = "xray_reality_fragment_switch"
                            )

                            Text("TCP / TLS Handshake Timeout", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(2500, 4000, 7000, 10000).forEach { timeoutMs ->
                                    FilterChip(
                                        selected = settings.tlsHandshakeTimeoutMs == timeoutMs,
                                        onClick = { onSetSocketMaskAndTimeouts(settings.finalMaskFamily, timeoutMs, timeoutMs) },
                                        label = { Text("${timeoutMs / 1000.0}s", maxLines = 1) }
                                    )
                                }
                            }

                            LabSwitchRow(
                                title = "ECH (Encrypted Client Hello)",
                                subtitle = if (compat.isRealityNode) "Unavailable when using REALITY" else "Encrypts inner SNI in TLS 1.3 handshake",
                                checked = settings.echEnabled,
                                enabled = !compat.isRealityNode,
                                onCheckedChange = { enabled -> onSetEchConfig(enabled, null, null) },
                                tag = "xray_ech_switch"
                            )

                            if (settings.echEnabled && !compat.isRealityNode) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("none", "half", "full").forEach { mode ->
                                        FilterChip(
                                            selected = settings.echForceQuery == mode,
                                            onClick = { onSetEchConfig(true, null, mode) },
                                            label = { Text("Query: $mode", maxLines = 1) }
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = echConfigInput,
                                        onValueChange = { echConfigInput = it },
                                        label = { Text("ECH Config List (Base64)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { onSetEchConfig(true, echConfigInput, null) }) {
                                        Text("Save")
                                    }
                                }
                            }

                            LabSwitchRow(
                                title = "Happy Eyeballs Dual-Stack Race",
                                subtitle = "Races IPv4/IPv6 connections (${settings.happyEyeballsTryDelayMs}ms stagger)",
                                checked = settings.happyEyeballsEnabled,
                                onCheckedChange = onSetHappyEyeballs,
                                tag = "xray_happy_eyeballs_switch"
                            )

                            LabSwitchRow(
                                title = "Allow Unencrypted VLESS + FinalMask",
                                subtitle = "Permits encryption=none endpoints when wrapped in FinalMask",
                                checked = settings.allowUnencryptedVless,
                                onCheckedChange = onSetAllowUnencryptedVless,
                                tag = "xray_unencrypted_vless_switch"
                            )

                            LabSwitchRow(
                                title = "FakeDNS Anti-Poisoning Pool",
                                subtitle = "Intercepts DNS queries in 198.18.0.0/15",
                                checked = settings.fakeDnsEnabled,
                                onCheckedChange = onSetFakeDns,
                                tag = "xray_fakedns_switch"
                            )

                            LabSwitchRow(
                                title = "Traffic Sniffing (HTTP/TLS/QUIC)",
                                subtitle = "Reconstructs target hostname for clean domain routing",
                                checked = settings.sniffingEnabled,
                                onCheckedChange = onSetSniffing,
                                tag = "xray_sniffing_switch"
                            )

                            LabSwitchRow(
                                title = "Block QUIC UDP/443 (Anti-Throttling)",
                                subtitle = "Forces fallback from throttled UDP/443 QUIC to TCP TLS",
                                checked = settings.blockQuicUdp,
                                onCheckedChange = onSetBlockQuicUdp,
                                tag = "xray_block_quic_switch"
                            )

                            Text("uTLS Fingerprint Spoofing", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("randomized", "chrome", "firefox", "safari", "ios", "random", "unsafe").forEach { fp ->
                                    FilterChip(
                                        selected = settings.utlsFingerprint == fp,
                                        onClick = { onSetUtlsFingerprint(fp) },
                                        label = { Text(fp, maxLines = 1) },
                                        modifier = Modifier.testTag("utls_fp_$fp")
                                    )
                                }
                            }

                            Text("XHTTP Anti-DPI Mode", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("auto", "packet-up", "stream-up", "stream-one").forEach { mode ->
                                    FilterChip(
                                        selected = settings.xhttpMode == mode,
                                        onClick = { onSetXhttpConfig(mode, settings.xhttpPaddingBytes) },
                                        label = { Text(mode, maxLines = 1) }
                                    )
                                }
                            }

                            Text("Keep-Alive Heartbeat Interval", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(5, 10, 15, 30).forEach { sec ->
                                    FilterChip(
                                        selected = settings.probeIntervalSec == sec,
                                        onClick = { onSetProbeInterval(sec) },
                                        label = { Text("${sec}s", maxLines = 1) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Multi-Resolver DNS Pool & Integrity Filter
                item(key = "dns_pool_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text("Multi-Resolver DNS Pool & Leak Guard", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                uiState.dnsProbeResultMs?.let { ms ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Formatters.latencyColor(ms).copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = if (ms > 0) "DNS $ms ms" else "DNS Timeout",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Formatters.latencyColor(ms),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("Cloudflare", "1.1.1.1" to "1.0.0.1", "https://cloudflare-dns.com/dns-query"),
                                    Triple("Quad9", "9.9.9.9" to "149.112.112.112", "https://dns.quad9.net:5053/dns-query"),
                                    Triple("Google", "8.8.8.8" to "8.8.4.4", "https://dns.google/resolve")
                                ).forEach { (label, pair, url) ->
                                    FilterChip(
                                        selected = primaryDns == pair.first,
                                        onClick = {
                                            primaryDns = pair.first
                                            fallbackDns = pair.second
                                            dohUrl = url
                                            onSaveDns(primaryDns, fallbackDns, dohEnabled, dohUrl)
                                        },
                                        label = { Text("$label (${pair.first})", maxLines = 1) }
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = primaryDns,
                                    onValueChange = { primaryDns = it },
                                    label = { Text("Primary DNS") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = fallbackDns,
                                    onValueChange = { fallbackDns = it },
                                    label = { Text("Fallback DNS") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = dohUrl,
                                onValueChange = { dohUrl = it },
                                label = { Text("DoH Anti-Poisoning Endpoint") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            LabSwitchRow(
                                title = "Encrypt Queries via DoH (RFC 8484)",
                                subtitle = "Routes DNS queries over HTTPS with VpnService.protect",
                                checked = dohEnabled,
                                onCheckedChange = {
                                    dohEnabled = it
                                    onSaveDns(primaryDns, fallbackDns, it, dohUrl)
                                },
                                tag = "doh_enabled_switch"
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onSaveDns(primaryDns, fallbackDns, dohEnabled, dohUrl) },
                                    modifier = Modifier.weight(1f).testTag("apply_dns_button")
                                ) {
                                    Text("Apply", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onTestDns,
                                    enabled = !uiState.isTestingDns,
                                    modifier = Modifier.weight(1f).testTag("test_dns_button")
                                ) {
                                    Text(if (uiState.isTestingDns) "Probing…" else "Test DNS", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onVerifyDoh,
                                    enabled = !uiState.isTestingDoh,
                                    modifier = Modifier.weight(1f).testTag("verify_doh_button")
                                ) {
                                    Text(if (uiState.isTestingDoh) "Checking…" else "Verify DoH", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            uiState.lastDohProbe?.let { dohRes ->
                                val color = if (dohRes.success) statusColors.connected else statusColors.error
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = color.copy(alpha = 0.14f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (dohRes.success)
                                            "DoH Clean (${dohRes.rttMs} ms) • ${dohRes.resolvedIps.take(2).joinToString()}"
                                        else "DoH Verification Error: ${dohRes.error}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text("Pool Strategy", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DnsPoolStrategy.entries.forEach { strategy ->
                                    FilterChip(
                                        selected = settings.dnsPoolStrategy == strategy,
                                        onClick = {
                                            onSetDnsPoolConfig(strategy, integrityFilter, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                        },
                                        label = { Text(strategy.label, maxLines = 1) },
                                        modifier = Modifier.testTag("dns_strategy_${strategy.key}")
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = dotServer,
                                onValueChange = { dotServer = it },
                                label = { Text("DoT Server (RFC 7858 host:port)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("dns_dot_server_input")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = dotEnabled,
                                    onClick = {
                                        dotEnabled = !dotEnabled
                                        onSetDnsPoolConfig(settings.dnsPoolStrategy, integrityFilter, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                    },
                                    label = { Text("DoT (853)", maxLines = 1) },
                                    modifier = Modifier.testTag("dns_pool_dot_chip")
                                )
                                FilterChip(
                                    selected = tcpDnsEnabled,
                                    onClick = {
                                        tcpDnsEnabled = !tcpDnsEnabled
                                        onSetDnsPoolConfig(settings.dnsPoolStrategy, integrityFilter, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                    },
                                    label = { Text("TCP DNS (53)", maxLines = 1) },
                                    modifier = Modifier.testTag("dns_pool_tcp_chip")
                                )
                                FilterChip(
                                    selected = udpDnsEnabled,
                                    onClick = {
                                        udpDnsEnabled = !udpDnsEnabled
                                        onSetDnsPoolConfig(settings.dnsPoolStrategy, integrityFilter, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                    },
                                    label = { Text("UDP DNS (53)", maxLines = 1) },
                                    modifier = Modifier.testTag("dns_pool_udp_chip")
                                )
                            }

                            LabSwitchRow(
                                title = "Response Integrity & Poisoning Filter",
                                subtitle = "Rejects TXID mismatches, forged bogon/private IPs & premature NXDOMAIN",
                                checked = integrityFilter,
                                onCheckedChange = {
                                    integrityFilter = it
                                    onSetDnsPoolConfig(settings.dnsPoolStrategy, it, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                },
                                tag = "dns_integrity_filter_switch"
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        onSetDnsPoolConfig(settings.dnsPoolStrategy, integrityFilter, dotServer, dotEnabled, tcpDnsEnabled, udpDnsEnabled)
                                    },
                                    modifier = Modifier.weight(1f).testTag("save_dns_pool_button")
                                ) {
                                    Text("Save Pool", maxLines = 1)
                                }
                                Button(
                                    onClick = onBenchmarkDnsPool,
                                    enabled = !uiState.isBenchmarkingDnsPool,
                                    modifier = Modifier.weight(1f).testTag("benchmark_dns_pool_button")
                                ) {
                                    Text(if (uiState.isBenchmarkingDnsPool) "Benchmarking…" else "Benchmark Pool", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            uiState.lastDnsPoolResult?.let { poolRes ->
                                val color = if (poolRes.success) statusColors.connected else statusColors.error
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = color.copy(alpha = 0.14f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (poolRes.success)
                                                "Winner: ${poolRes.winnerLabel} (${poolRes.rttMs} ms) -> ${poolRes.resolvedIps.take(2).joinToString()}"
                                            else "Pool Error: ${poolRes.error}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color
                                        )
                                        if (poolRes.entries.isNotEmpty()) {
                                            Text(
                                                text = poolRes.entries.joinToString(" • ") { entry ->
                                                    if (entry.success) "${entry.transport.shortTag}:${entry.rttMs}ms"
                                                    else "${entry.transport.shortTag}:ERR"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (poolRes.rejectedForPoisoning.isNotEmpty()) {
                                            Text(
                                                text = "Filtered: ${poolRes.rejectedForPoisoning.joinToString(" | ")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = statusColors.connecting
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LabSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
