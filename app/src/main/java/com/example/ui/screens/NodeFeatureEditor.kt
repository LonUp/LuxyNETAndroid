package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FeatureGate
import com.example.data.model.FeatureMatrix
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.TriState
import com.example.i18n.tr
import com.example.ui.theme.LocalLuxyColors

/**
 * Collapsed by default. Each knob is Inherit / On / Off so a profile can diverge from the
 * global anti-censorship settings without a second settings screen.
 */
@Composable
fun ProfileFeatureSection(
    features: NodeFeatureOverrides,
    matrix: FeatureMatrix,
    experimentalModeEnabled: Boolean,
    onChange: (NodeFeatureOverrides) -> Unit
) {
    val colors = LocalLuxyColors.current
    var open by remember { mutableStateOf(!features.isEmpty()) }
    val custom = listOf(features.mux, features.fragment, features.realityFragment, features.ech, features.noise, features.tlsPadding, features.tcpFastOpen)
        .count { it != TriState.INHERIT }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("Profile features"),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (custom == 0) tr("Inherit globals") else "$custom ${tr("overrides")}",
                    fontSize = 11.sp,
                    color = colors.primary
                )
            }
            if (!open) return@Column
            Text(
                text = tr("On and Off beat the global setting. Forbidden combinations stay off."),
                fontSize = 11.sp,
                color = colors.onSurfaceVariant
            )
            FeatureRow(
                title = tr("MUX"),
                gate = matrix.mux,
                reason = matrix.muxReason,
                value = features.mux,
                experimentalModeEnabled = experimentalModeEnabled,
                onChange = { onChange(features.copy(mux = it)) }
            )
            if (features.mux == TriState.ON && matrix.mux == FeatureGate.ALLOWED) {
                OutlinedTextField(
                    value = if (features.muxConcurrency == 0) "" else features.muxConcurrency.toString(),
                    onValueChange = { raw ->
                        onChange(features.copy(muxConcurrency = raw.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0))
                    },
                    label = { Text(tr("MUX concurrency")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            FeatureRow("Fragment", matrix.fragment, matrix.fragmentReason, features.fragment, experimentalModeEnabled) {
                onChange(features.copy(fragment = it, tcpFastOpen = if (it == TriState.ON) TriState.OFF else features.tcpFastOpen))
            }
            if (features.fragment == TriState.ON && matrix.fragment != FeatureGate.FORBIDDEN) {
                CompactField(tr("Packets"), features.fragmentPackets) { onChange(features.copy(fragmentPackets = it)) }
                CompactField(tr("Length"), features.fragmentLength) { onChange(features.copy(fragmentLength = it)) }
                CompactField(tr("Interval"), features.fragmentInterval) { onChange(features.copy(fragmentInterval = it)) }
            }
            FeatureRow("REALITY fragment", matrix.realityFragment, matrix.realityFragmentReason, features.realityFragment, experimentalModeEnabled) {
                onChange(features.copy(realityFragment = it))
            }
            FeatureRow("ECH", matrix.ech, matrix.echReason, features.ech, experimentalModeEnabled) {
                onChange(features.copy(ech = it))
            }
            if (features.ech == TriState.ON && matrix.ech != FeatureGate.FORBIDDEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("half", "full", "none").forEach { mode ->
                        FilterChip(
                            selected = features.echForceQuery == mode,
                            onClick = { onChange(features.copy(echForceQuery = mode)) },
                            label = { Text(mode, fontSize = 11.sp) }
                        )
                    }
                }
                FeatureRow("Fail-closed ECH", matrix.ech, "", features.failClosedEch, experimentalModeEnabled) {
                    onChange(features.copy(failClosedEch = it))
                }
            }
            FeatureRow("Noise", matrix.noise, matrix.noiseReason, features.noise, experimentalModeEnabled) {
                onChange(features.copy(noise = it))
            }
            if (features.noise == TriState.ON && matrix.noise != FeatureGate.FORBIDDEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("rand", "str", "hex").forEach { kind ->
                        FilterChip(
                            selected = features.noiseType == kind,
                            onClick = { onChange(features.copy(noiseType = kind)) },
                            label = { Text(kind, fontSize = 11.sp) }
                        )
                    }
                }
                CompactField(tr("Noise size"), features.noisePacketSize) { onChange(features.copy(noisePacketSize = it)) }
                CompactField(tr("Noise delay"), features.noiseDelayMs) { onChange(features.copy(noiseDelayMs = it)) }
            }
            FeatureRow("TLS padding", matrix.tlsPadding, matrix.tlsPaddingReason, features.tlsPadding, experimentalModeEnabled) {
                onChange(features.copy(tlsPadding = it))
            }
            FeatureRow("TCP Fast Open", matrix.tcpFastOpen, matrix.tcpFastOpenReason, features.tcpFastOpen, experimentalModeEnabled) {
                onChange(features.copy(tcpFastOpen = it, fragment = if (it == TriState.ON) TriState.OFF else features.fragment))
            }
            if (matrix.allowUnencrypted != FeatureGate.FORBIDDEN) {
                FeatureRow("Force VLESS encryption none", matrix.allowUnencrypted, "", features.allowUnencryptedVless, experimentalModeEnabled) {
                    onChange(features.copy(allowUnencryptedVless = it))
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    gate: FeatureGate,
    reason: String,
    value: TriState,
    experimentalModeEnabled: Boolean,
    onChange: (TriState) -> Unit
) {
    val colors = LocalLuxyColors.current
    val locked = gate == FeatureGate.FORBIDDEN || (gate == FeatureGate.EXPERIMENTAL && !experimentalModeEnabled)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = tr(title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TriState.entries.forEach { state ->
                FilterChip(
                    selected = value == state && !locked,
                    onClick = { if (!locked) onChange(state) },
                    enabled = !locked,
                    label = { Text(tr(state.name.lowercase().replaceFirstChar { it.titlecase() }), fontSize = 10.sp) }
                )
            }
        }
        if (locked && reason.isNotBlank()) {
            Text(tr(reason), fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
