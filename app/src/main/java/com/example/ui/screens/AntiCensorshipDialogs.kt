package com.example.ui.screens

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import com.example.xraycore.CleanIpScanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanIpScannerDialog(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val state by viewModel.cleanIpState.collectAsState()
    val activeNode by viewModel.activeNode.collectAsState()
    var cidrInput by remember { mutableStateOf("104.16.0.0/24") }
    var sniInput by remember { mutableStateOf(activeNode?.sni?.ifBlank { "www.cloudflare.com" } ?: "www.cloudflare.com") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("CDN Clean IP Scanner", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CleanIpScanner.CLEAN_IP_PRESET_RANGES.forEach { (label, cidr) ->
                        FilterChip(selected = cidrInput == cidr, onClick = { cidrInput = cidr }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(
                    value = cidrInput,
                    onValueChange = { cidrInput = it },
                    label = { Text("CIDR Range or IPs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sniInput,
                    onValueChange = { sniInput = it },
                    label = { Text("SNI Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.isScanning && state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = { viewModel.startCleanIpScan(cidrInput, 443, sniInput, useFragment = true) },
                    enabled = !state.isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) {
                    Text(if (state.isScanning) "Scanning (${state.done}/${state.total})…" else "Start Clean IP Scan")
                }

                state.results.take(8).forEach { cand ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(cand.ip, fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${cand.rttMs} ms • ${cand.protocol}", fontFamily = MonoFontFamily, fontSize = 11.sp, color = colors.statusConnected)
                            }
                            if (activeNode != null && !activeNode!!.isLocked) {
                                TextButton(onClick = { viewModel.applyCleanIpToActiveNode(cand.ip) }) {
                                    Text("Apply IP")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WarpBenchmarkDialog(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val state by viewModel.warpBenchState.collectAsState()
    var cidrInput by remember { mutableStateOf("162.159.192.0/24") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("WARP / AWG Endpoint Benchmarker", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CleanIpScanner.WARP_PRESET_RANGES.forEach { (label, cidr) ->
                        FilterChip(selected = cidrInput == cidr, onClick = { cidrInput = cidr }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(
                    value = cidrInput,
                    onValueChange = { cidrInput = it },
                    label = { Text("WARP CIDR / Endpoints") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.startWarpBenchmark(cidrInput, listOf(2408, 1701, 500, 4500)) },
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) {
                    Text(if (state.isRunning) "Benchmarking (${state.done}/${state.total})…" else "Benchmark WARP Endpoints")
                }
                state.results.take(8).forEach { cand ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(cand.endpoint, fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${cand.avgRttMs} ms • loss ${cand.lossPercent}%",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    color = if (cand.lossPercent == 0) colors.statusConnected else colors.statusConnecting
                                )
                            }
                            TextButton(onClick = { viewModel.applyWarpEndpoint(cand.endpoint) }) { Text("Apply") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun TlsAndRealityProbeDialog(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val fragRes by viewModel.fragmentProbeState.collectAsState()
    val realityRes by viewModel.realityProbeState.collectAsState()
    val busy by viewModel.isProbingTool.collectAsState()
    var targetHost by remember { mutableStateOf("www.cloudflare.com") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Fragment TLS & REALITY SNI Probe", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    label = { Text("Target Host / REALITY SNI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.runFragmentTlsProbe(targetHost, 443, targetHost, true) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                    ) {
                        Text("Fragment TLS", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.runRealityReadinessProbe(targetHost, 443) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, colors.outline)
                    ) {
                        Text("REALITY SNI", fontSize = 12.sp)
                    }
                }
                fragRes?.let { r ->
                    Text(
                        text = if (r.success) "Fragment TLS OK: ${r.rttMs} ms • ${r.tlsProtocol} (${r.fragmentsSent} chunks)" else "Fragment TLS Failed: ${r.error}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = if (r.success) colors.statusConnected else colors.statusError
                    )
                }
                realityRes?.let { r ->
                    Text(
                        text = if (r.isReady) "REALITY Ready: ${r.sni} (${r.tlsVersion}, ${r.rttMs} ms)" else "Not REALITY-ready: ${r.error ?: r.tlsVersion}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = if (r.isReady) colors.statusConnected else colors.statusError
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun DnsAndEchProbeDialog(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val settings by viewModel.settings.collectAsState()
    val dohRes by viewModel.dohProbeState.collectAsState()
    val echRes by viewModel.echProbeState.collectAsState()
    val poolRes by viewModel.dnsPoolState.collectAsState()
    val busy by viewModel.isProbingTool.collectAsState()
    var domain by remember { mutableStateOf("cloudflare.com") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("DNS Pool & ECH Probe", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Probe Domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.runDnsPoolBenchmark(domain) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                    ) {
                        Text("Test DNS Pool", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.runEchProbe(domain, settings.dohServerUrl) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, colors.outline)
                    ) {
                        Text("ECH HTTPS RR", fontSize = 12.sp)
                    }
                }
                poolRes?.let { r ->
                    Text(
                        text = if (r.success) "Winner: ${r.winnerLabel} (${r.rttMs} ms) → ${r.resolvedIps.joinToString()}" else "DNS Pool Error: ${r.error}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = if (r.success) colors.statusConnected else colors.statusError
                    )
                }
                echRes?.let { r ->
                    Text(
                        text = if (r.echSupported) "ECH Supported (${r.rttMs} ms) • ALPN ${r.alpnHints}" else "No ECH key found: ${r.error ?: "No HTTPS type-65 record"}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = if (r.echSupported) colors.statusConnected else colors.statusConnecting
                    )
                }
                dohRes?.let { r ->
                    Text(
                        text = if (r.success) "DoH Anti-Poison OK: ${r.rttMs} ms" else "DoH Error: ${r.error}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = if (r.success) colors.statusConnected else colors.statusError
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun XrayInspectorDialog(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var compiledJson by remember { mutableStateOf("Compiling Vault-redacted Xray JSON…") }

    LaunchedEffect(Unit) {
        compiledJson = viewModel.compileInspectorJsonAsync()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Compiled Xray JSON (Vault-Redacted)", color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surfaceLowest,
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Text(
                        text = compiledJson,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = colors.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LuxyVPN Xray JSON", compiledJson)))
                        }
                    },
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Text("Copy Compiled JSON")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
