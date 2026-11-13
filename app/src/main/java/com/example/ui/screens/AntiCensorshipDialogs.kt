package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.ui.CleanIpScanState
import com.example.ui.WarpEndpointScanState
import com.example.ui.components.Formatters
import com.example.ui.theme.LocalTunnelStatusColors
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.RealityReadinessResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanIpScannerDialog(
    scanState: CleanIpScanState,
    defaultSni: String,
    hasActiveNode: Boolean,
    onStartScan: (cidrOrIps: String, sni: String) -> Unit,
    onApplyCleanIp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var cidrOrIps by remember { mutableStateOf(CleanIpScanner.PRESET_RANGES.first().second) }
    var sni by remember { mutableStateOf(defaultSni.ifBlank { "www.cloudflare.com" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "Clean CDN IP Scanner",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Tests candidate CDN endpoints with Fragmented TLS handshakes to bypass IP blocking & throttling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CleanIpScanner.PRESET_RANGES.forEach { (label, cidr) ->
                        FilterChip(
                            selected = cidrOrIps == cidr,
                            onClick = { cidrOrIps = cidr },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }

                OutlinedTextField(
                    value = cidrOrIps,
                    onValueChange = { cidrOrIps = it },
                    label = { Text("CIDR Range or Comma-Separated IPs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("clean_ip_cidr_input")
                )

                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("TLS SNI Hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("clean_ip_sni_input")
                )

                Button(
                    onClick = { onStartScan(cidrOrIps, sni) },
                    enabled = !scanState.isScanning && cidrOrIps.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("start_clean_ip_scan_button")
                ) {
                    Text(
                        if (scanState.isScanning)
                            "Scanning ${scanState.completed}/${scanState.total}…"
                        else "Scan Clean IPs",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (scanState.isScanning && scanState.total > 0) {
                    LinearProgressIndicator(
                        progress = { scanState.completed.toFloat() / scanState.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (scanState.results.isNotEmpty()) {
                    Text(
                        text = "Cleanest Endpoints (${scanState.results.size})",
                        style = MaterialTheme.typography.labelMedium
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scanState.results, key = { it.ip }) { candidate ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            candidate.ip,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${candidate.rttMs} ms • ${candidate.protocol}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Formatters.latencyColor(candidate.rttMs),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (hasActiveNode) {
                                        OutlinedButton(
                                            onClick = {
                                                onApplyCleanIp(candidate.ip)
                                                onDismiss()
                                            }
                                        ) {
                                            Text("Apply", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun RealitySniDialog(
    defaultSni: String,
    isTesting: Boolean,
    result: RealityReadinessResult?,
    onTestSni: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var sniInput by remember { mutableStateOf(defaultSni.ifBlank { "www.cloudflare.com" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColors = LocalTunnelStatusColors.current
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = statusColors.connected,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "REALITY SNI Readiness",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Verify that a target SNI negotiates TLSv1.3 cleanly for VLESS-REALITY stealth camouflage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sniInput,
                    onValueChange = { sniInput = it },
                    label = { Text("Target SNI Domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("reality_sni_input")
                )
                Button(
                    onClick = { onTestSni(sniInput) },
                    enabled = !isTesting && sniInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("run_reality_sni_test_button")
                ) {
                    Text(if (isTesting) "Probing TLS 1.3…" else "Probe REALITY SNI", maxLines = 1)
                }

                result?.let { res ->
                    val statusColors = LocalTunnelStatusColors.current
                    val tone = if (res.isReady) statusColors.connected else statusColors.error
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tone.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, tone.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = tone,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (res.isReady) "REALITY Ready (${res.rttMs} ms)" else "Not Ready",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tone,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (res.tlsVersion.isNotBlank()) {
                                Text(
                                    text = "${res.tlsVersion} • ${res.cipherSuite}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            res.error?.let {
                                Text(text = it, style = MaterialTheme.typography.bodySmall, color = statusColors.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun XrayConfigInspectorDialog(
    compiledJson: String,
    onCopyJson: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "LuxyVPN Xray Core JSON",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                Text(
                    text = compiledJson,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCopyJson(compiledJson)
                    onDismiss()
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy JSON", maxLines = 1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun EchProbeDialog(
    defaultDomain: String,
    isTesting: Boolean,
    result: com.example.xraycore.EchProbeResult?,
    onProbeEch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var domainInput by remember { mutableStateOf(defaultDomain.ifBlank { "cloudflare-ech.com" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "ECH Check",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Queries DNS HTTPS (RR type 65) via encrypted DoH to verify ECH anti-SNI encryption readiness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Domain / SNI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ech_domain_input")
                )
                Button(
                    onClick = { onProbeEch(domainInput) },
                    enabled = !isTesting && domainInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("run_ech_probe_button")
                ) {
                    Text(if (isTesting) "Querying HTTPS RR…" else "Verify ECH via DoH", maxLines = 1)
                }

                result?.let { res ->
                    val statusColors = LocalTunnelStatusColors.current
                    val statusColor = if (res.echSupported) statusColors.connected else statusColors.error
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (res.echSupported)
                                    "ECH HTTPS RR Available (${res.rttMs} ms)"
                                else "ECH Not Detected",
                                style = MaterialTheme.typography.titleSmall,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (res.alpnHints.isNotBlank()) {
                                Text(
                                    text = "ALPN: ${res.alpnHints}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (res.httpsRecords.isNotEmpty()) {
                                Text(
                                    text = res.httpsRecords.first(),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            res.error?.let {
                                Text(text = it, style = MaterialTheme.typography.bodySmall, color = statusColors.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WarpEndpointBenchmarkDialog(
    scanState: WarpEndpointScanState,
    currentEndpoint: String,
    onStartBenchmark: (cidrOrEndpoints: String, ports: List<Int>) -> Unit,
    onApplyEndpoint: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var targetInput by remember { mutableStateOf(CleanIpScanner.WARP_PRESET_RANGES.first().second) }
    var selectedPorts by remember { mutableStateOf(setOf(2408, 1701, 500, 4500)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColors = LocalTunnelStatusColors.current
                Icon(
                    Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = statusColors.connected,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "WARP / MASQUE Benchmark",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Benchmarks Cloudflare/WARP UDP handshake RTT and jitter. Current: $currentEndpoint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CleanIpScanner.WARP_PRESET_RANGES.forEach { (label, cidr) ->
                        FilterChip(
                            selected = targetInput == cidr,
                            onClick = { targetInput = cidr },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }

                OutlinedTextField(
                    value = targetInput,
                    onValueChange = { targetInput = it },
                    label = { Text("CIDR or Endpoint List (ip:port)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("warp_benchmark_cidr_input")
                )

                Text("UDP Candidate Ports", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CleanIpScanner.WARP_STANDARD_PORTS.forEach { port ->
                        val active = port in selectedPorts
                        FilterChip(
                            selected = active,
                            onClick = {
                                val next = selectedPorts.toMutableSet()
                                if (active && next.size > 1) next.remove(port) else next.add(port)
                                selectedPorts = next
                            },
                            label = { Text(":$port", maxLines = 1) }
                        )
                    }
                }

                Button(
                    onClick = { onStartBenchmark(targetInput, selectedPorts.sorted()) },
                    enabled = !scanState.isScanning && targetInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("start_warp_benchmark_button")
                ) {
                    Text(
                        if (scanState.isScanning)
                            "Benchmarking ${scanState.completed}/${scanState.total}…"
                        else "Benchmark WARP / MASQUE Endpoints",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (scanState.isScanning && scanState.total > 0) {
                    LinearProgressIndicator(
                        progress = { scanState.completed.toFloat() / scanState.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (scanState.results.isNotEmpty()) {
                    Text(
                        text = "Ranked Fastest Endpoints (${scanState.results.size})",
                        style = MaterialTheme.typography.labelMedium
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scanState.results, key = { it.endpoint }) { candidate ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            candidate.endpoint,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${candidate.avgRttMs} ms • jitter ${candidate.jitterMs} ms • loss ${candidate.lossPercent}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Formatters.latencyColor(candidate.avgRttMs),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onApplyEndpoint(candidate.endpoint)
                                            onDismiss()
                                        },
                                        modifier = Modifier.testTag("apply_warp_endpoint_button")
                                    ) {
                                        Text("Apply", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
