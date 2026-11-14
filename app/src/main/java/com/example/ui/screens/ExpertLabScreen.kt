package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.ProtocolGuardrails
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import com.example.xraycore.XrayConfigCompiler

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpertLabScreen(
    viewModel: LuxyVpnViewModel,
    onBack: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val settings by viewModel.settings.collectAsState()
    val activeNode by viewModel.activeNode.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    val report = remember(settings, activeNode) {
        ProtocolGuardrails.evaluateAndSanitizeSettings(settings, activeNode)
    }

    var showCleanIpDialog by remember { mutableStateOf(false) }
    var showWarpBenchDialog by remember { mutableStateOf(false) }
    var showTlsRealityProbeDialog by remember { mutableStateOf(false) }
    var showDnsEchProbeDialog by remember { mutableStateOf(false) }
    var showXrayInspector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.onBackground, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Anti-Censorship Expert Lab", style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                Text(
                    "Stage ${telemetry.activeAdaptiveStage} • ${XrayConfigCompiler.LUXY_CORE_VERSION}",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
        }

        // Guardrail Notices
        if (report.notices.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.statusConnecting.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, colors.statusConnecting.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Protocol Guardrail Enforcement", style = MaterialTheme.typography.labelMedium, color = colors.statusConnecting)
                    report.notices.forEach { notice ->
                        Text("• $notice", style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                    }
                }
            }
        }

        // Diagnostic & Probe Action Grid
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showCleanIpDialog = true }, border = BorderStroke(1.dp, colors.outline)) {
                Icon(Icons.Rounded.WifiFind, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                Text(" Clean IP Scan", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showWarpBenchDialog = true }, border = BorderStroke(1.dp, colors.outline)) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                Text(" WARP Bench", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showTlsRealityProbeDialog = true }, border = BorderStroke(1.dp, colors.outline)) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = colors.statusConnected, modifier = Modifier.size(16.dp))
                Text(" TLS/REALITY Probe", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showDnsEchProbeDialog = true }, border = BorderStroke(1.dp, colors.outline)) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.statusInfo, modifier = Modifier.size(16.dp))
                Text(" DNS/ECH Probe", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showXrayInspector = true }, border = BorderStroke(1.dp, colors.outline)) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                Text(" Xray JSON", fontSize = 12.sp)
            }
        }

        // Anti-Censorship Presets
        LabCard("Anti-Censorship Presets") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AntiCensorshipPreset.entries.filter { it != AntiCensorshipPreset.CUSTOM }.forEach { preset ->
                    FilterChip(
                        selected = settings.antiCensorshipPreset == preset,
                        onClick = { viewModel.applyAntiCensorshipPreset(preset) },
                        label = { Text(preset.label, fontSize = 11.sp) }
                    )
                }
            }
            Text(settings.antiCensorshipPreset.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        // Adaptive 5-Stage Ladder
        LabCard("Adaptive 5-Stage Anti-DPI Ladder") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                XrayConfigCompiler.ADAPTIVE_STAGES.forEach { spec ->
                    FilterChip(
                        selected = telemetry.activeAdaptiveStage == spec.stage,
                        onClick = { viewModel.setAdaptiveStage(spec.stage) },
                        label = { Text("Stage ${spec.stage}", fontSize = 11.sp) }
                    )
                }
            }
            Text(XrayConfigCompiler.stageSpec(telemetry.activeAdaptiveStage).summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        // TLS Fragmentation & TFO
        LabCard("TLS ClientHello Fragmentation & Noise") {
            LabSwitch(
                label = "TLS Fragmentation",
                checked = settings.tlsFragmentEnabled,
                onCheckedChange = { v ->
                    viewModel.updateSettings {
                        val (frag, tfo) = ProtocolGuardrails.enforceFragmentVsTfo(v, it.tcpFastOpen, lastChangedIsFragment = true)
                        it.copy(tlsFragmentEnabled = frag, tcpFastOpen = tfo, antiCensorshipPreset = AntiCensorshipPreset.CUSTOM)
                    }
                }
            )
            LabSwitch(
                label = "TCP Fast Open (TFO)",
                checked = settings.tcpFastOpen,
                onCheckedChange = { v ->
                    viewModel.updateSettings {
                        val (frag, tfo) = ProtocolGuardrails.enforceFragmentVsTfo(it.tlsFragmentEnabled, v, lastChangedIsFragment = false)
                        it.copy(tlsFragmentEnabled = frag, tcpFastOpen = tfo, antiCensorshipPreset = AntiCensorshipPreset.CUSTOM)
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.fragmentPackets,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(fragmentPackets = v) } },
                    label = { Text("Packets") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.fragmentLength,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(fragmentLength = v) } },
                    label = { Text("Length (B)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.fragmentInterval,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(fragmentInterval = v) } },
                    label = { Text("Interval (ms)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            LabSwitch(
                label = "Mixed-Case SNI Randomization",
                checked = settings.mixedCaseSni,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(mixedCaseSni = v) } }
            )
            LabSwitch(
                label = "RFC 7685 TLS Record Padding",
                checked = settings.tlsPaddingEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(tlsPaddingEnabled = v) } }
            )
            LabSwitch(
                label = "Noise Padding Outbound",
                checked = settings.noiseEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(noiseEnabled = v) } }
            )
            LabSwitch(
                label = "Encrypted Client Hello (ECH)",
                checked = settings.echEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(echEnabled = v) } }
            )
            LabSwitch(
                label = "Fail-Closed Strict ECH",
                checked = settings.failClosedEch,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(failClosedEch = v) } }
            )
            Text("FinalMask Protocol Shaping", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                XrayConfigCompiler.FINAL_MASK_PRESETS.forEach { mask ->
                    FilterChip(
                        selected = settings.finalMaskType == mask,
                        onClick = { viewModel.updateSettings { it.copy(finalMaskType = mask) } },
                        label = { Text(mask, fontSize = 11.sp) }
                    )
                }
            }
        }

        // Multi-Hop Chaining & AmneziaWG / WARP
        LabCard("Multi-Hop Chaining & AmneziaWG") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChainingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.chainingMode == mode,
                        onClick = { viewModel.updateSettings { it.copy(chainingMode = mode) } },
                        label = { Text(mode.label, fontSize = 11.sp) }
                    )
                }
            }
            if (settings.chainingMode == ChainingMode.NODE_TO_NODE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profiles.filter { it.id != activeNode?.id }.take(6).forEach { relay ->
                        FilterChip(
                            selected = settings.relayNodeId == relay.id,
                            onClick = { viewModel.updateSettings { it.copy(relayNodeId = relay.id) } },
                            label = { Text(relay.name, fontSize = 11.sp) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = settings.warpEndpoint,
                onValueChange = { v -> viewModel.updateSettings { it.copy(warpEndpoint = v) } },
                label = { Text("WARP / AWG Endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.awgJc.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { num -> viewModel.updateSettings { it.copy(awgJc = num) } } },
                    label = { Text("Jc") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.awgJmin.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { num -> viewModel.updateSettings { it.copy(awgJmin = num) } } },
                    label = { Text("Jmin") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.awgJmax.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { num -> viewModel.updateSettings { it.copy(awgJmax = num) } } },
                    label = { Text("Jmax") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Multi-Resolver DNS Pool
        LabCard("Multi-Resolver DNS Pool & FakeDNS") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DnsPoolStrategy.entries.forEach { strat ->
                    FilterChip(
                        selected = settings.dnsPoolStrategy == strat,
                        onClick = { viewModel.updateSettings { it.copy(dnsPoolStrategy = strat) } },
                        label = { Text(strat.label, fontSize = 11.sp) }
                    )
                }
            }
            LabSwitch("DoH Enabled", settings.dohEnabled) { v -> viewModel.updateSettings { it.copy(dohEnabled = v) } }
            LabSwitch("DoT Enabled", settings.dnsDotEnabled) { v -> viewModel.updateSettings { it.copy(dnsDotEnabled = v) } }
            LabSwitch("FakeDNS 198.18.0.0/15", settings.fakeDnsEnabled) { v -> viewModel.updateSettings { it.copy(fakeDnsEnabled = v) } }
            LabSwitch("DNS Anti-Poisoning Integrity Filter", settings.dnsIntegrityFilterEnabled) { v -> viewModel.updateSettings { it.copy(dnsIntegrityFilterEnabled = v) } }
            LabSwitch("Block QUIC UDP 443", settings.blockQuicUdp) { v -> viewModel.updateSettings { it.copy(blockQuicUdp = v) } }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showCleanIpDialog) {
        CleanIpScannerDialog(viewModel = viewModel, onDismiss = { showCleanIpDialog = false })
    }
    if (showWarpBenchDialog) {
        WarpBenchmarkDialog(viewModel = viewModel, onDismiss = { showWarpBenchDialog = false })
    }
    if (showTlsRealityProbeDialog) {
        TlsAndRealityProbeDialog(viewModel = viewModel, onDismiss = { showTlsRealityProbeDialog = false })
    }
    if (showDnsEchProbeDialog) {
        DnsAndEchProbeDialog(viewModel = viewModel, onDismiss = { showDnsEchProbeDialog = false })
    }
    if (showXrayInspector) {
        XrayInspectorDialog(viewModel = viewModel, onDismiss = { showXrayInspector = false })
    }
}

@Composable
private fun LabCard(title: String, content: @Composable () -> Unit) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            content()
        }
    }
}

@Composable
private fun LabSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
