package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.SpeedTestEngine
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestProgress
import com.example.data.model.SpeedTestResult
import com.example.data.model.SpeedTestStage
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedTestSheet(
    connectionState: ConnectionState,
    activeNode: ServerProfileEntity?,
    isRunning: Boolean,
    progress: SpeedTestProgress?,
    results: Map<SpeedTestMode, SpeedTestResult>,
    errorMessage: String?,
    onConnectVpn: () -> Unit,
    onRunSpeedTest: (mode: SpeedTestMode, allowDisconnectedHandshakeProbe: Boolean) -> Unit,
    onCancelSpeedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val isVpnConnected = connectionState == ConnectionState.CONNECTED
    var selectedMode by remember(connectionState) {
        mutableStateOf(if (isVpnConnected) SpeedTestMode.TUNNELED else SpeedTestMode.DIRECT)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeResult = results[selectedMode]
    val tunneledResult = results[SpeedTestMode.TUNNELED]
    val directResult = results[SpeedTestMode.DIRECT]
    val activeProgress = progress?.takeIf { it.mode == selectedMode }

    val displayStage = activeProgress?.stage
        ?: if (activeResult != null) SpeedTestStage.COMPLETE else SpeedTestStage.IDLE

    val displayMbps = if (activeProgress != null && isRunning) {
        activeProgress.currentMbps
    } else {
        activeResult?.downloadMbps ?: 0.0
    }

    val displayPing = activeProgress?.pingMs ?: activeResult?.pingMs
    val displayJitter = activeProgress?.jitterMs ?: activeResult?.jitterMs
    val displayDownload = activeProgress?.downloadMbps ?: activeResult?.downloadMbps
    val displayUpload = activeProgress?.uploadMbps ?: activeResult?.uploadMbps

    val exitLabel = activeProgress?.exitLabel
        ?: activeResult?.exitLabel
        ?: SpeedTestEngine.resolveSafeExitLabel(selectedMode, activeNode)

    ModalBottomSheet(
        onDismissRequest = {
            if (isRunning) onCancelSpeedTest()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Dual-Mode Speed Test",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = {
                    if (isRunning) onCancelSpeedTest()
                    onDismiss()
                }) {
                    Text("Close", color = colors.onSurfaceVariant)
                }
            }

            // Segmented Pill Mode Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceLowest)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModePillButton(
                    modifier = Modifier.weight(1f),
                    selected = selectedMode == SpeedTestMode.TUNNELED,
                    enabled = !isRunning,
                    icon = Icons.Rounded.Shield,
                    label = "With VPN (Tunnel)",
                    selectedBg = colors.primaryContainer,
                    selectedContent = colors.onPrimaryContainer,
                    onClick = { selectedMode = SpeedTestMode.TUNNELED }
                )
                ModePillButton(
                    modifier = Modifier.weight(1f),
                    selected = selectedMode == SpeedTestMode.DIRECT,
                    enabled = !isRunning,
                    icon = Icons.Rounded.Wifi,
                    label = "Without VPN (Direct)",
                    selectedBg = colors.secondaryContainer,
                    selectedContent = colors.onSecondaryContainer,
                    onClick = { selectedMode = SpeedTestMode.DIRECT }
                )
            }

            // Exit / Bypass label with Vault Lock redaction compliance
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeNode?.isLocked == true && selectedMode == SpeedTestMode.TUNNELED) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = colors.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = exitLabel,
                        fontFamily = MonoFontFamily,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Disconnected notice for Tunneled Mode
            if (selectedMode == SpeedTestMode.TUNNELED && !isVpnConnected && !isRunning) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.statusConnecting.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, colors.statusConnecting.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "VPN Disconnected — Connect to measure real SOCKS5 tunnel speed, or test node handshake latency.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.statusConnecting,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (activeNode != null) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onConnectVpn,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary,
                                        contentColor = colors.onPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Connect VPN", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { onRunSpeedTest(SpeedTestMode.TUNNELED, true) },
                                    border = BorderStroke(1.dp, colors.outline),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Node Handshake Probe", fontSize = 12.sp, color = colors.onSurface)
                                }
                            }
                        }
                    }
                }
            }

            // Custom 240-Degree Material 3 Canvas Speedometer Gauge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.Center
            ) {
                val trackColor = colors.outlineVariant
                val accentColor = if (displayStage == SpeedTestStage.UPLOAD) {
                    colors.secondary
                } else if (selectedMode == SpeedTestMode.TUNNELED) {
                    colors.primary
                } else {
                    colors.statusConnected
                }
                val tickColor = colors.onSurfaceVariant
                val dotColor = colors.onSurface

                val fillRatio = if (displayStage == SpeedTestStage.PING) {
                    ((activeProgress?.progressFraction ?: 0.2f) * 2.5f).coerceIn(0.12f, 1f)
                } else {
                    (displayMbps / 150.0).toFloat().coerceIn(0f, 1f)
                }

                Canvas(modifier = Modifier.size(width = 240.dp, height = 180.dp)) {
                    val strokeWidth = 14.dp.toPx()
                    val diameter = size.width - strokeWidth * 2f
                    val topLeft = Offset(strokeWidth, strokeWidth)
                    val arcSize = Size(diameter, diameter)

                    // 240-deg track
                    drawArc(
                        color = trackColor,
                        startAngle = 150f,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Tick marks
                    val cx = topLeft.x + diameter / 2f
                    val cy = topLeft.y + diameter / 2f
                    val r = diameter / 2f
                    for (i in 0..12) {
                        val angleRad = ((150f + 240f * i / 12f) * PI / 180.0)
                        val innerR = r - 18.dp.toPx()
                        val outerR = r - 12.dp.toPx()
                        drawLine(
                            color = tickColor,
                            start = Offset(
                                (cx + cos(angleRad) * innerR).toFloat(),
                                (cy + sin(angleRad) * innerR).toFloat()
                            ),
                            end = Offset(
                                (cx + cos(angleRad) * outerR).toFloat(),
                                (cy + sin(angleRad) * outerR).toFloat()
                            ),
                            strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx()
                        )
                    }

                    // Active sweep
                    if (fillRatio > 0.01f) {
                        val sweep = 240f * fillRatio
                        drawArc(
                            color = accentColor,
                            startAngle = 150f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        val tipRad = ((150f + sweep) * PI / 180.0)
                        drawCircle(
                            color = dotColor,
                            radius = 5.dp.toPx(),
                            center = Offset(
                                (cx + cos(tipRad) * r).toFloat(),
                                (cy + sin(tipRad) * r).toFloat()
                            )
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 18.dp)
                ) {
                    val centerValue = if (displayStage == SpeedTestStage.PING && displayPing != null) {
                        String.format(Locale.US, "%.0f", displayPing)
                    } else {
                        String.format(Locale.US, "%.1f", displayMbps)
                    }
                    Text(
                        text = centerValue,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (displayStage == SpeedTestStage.PING) "ms" else "Mbps",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = displayStage.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            activeProgress?.statusNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.statusError,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // 4 Balanced Metric Tiles (2x2 Grid)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SpeedMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ArrowDownward,
                        iconTint = colors.primary,
                        label = "Download",
                        value = displayDownload?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—"
                    )
                    SpeedMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ArrowUpward,
                        iconTint = colors.secondary,
                        label = "Upload",
                        value = displayUpload?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—"
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SpeedMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Timer,
                        iconTint = colors.statusConnected,
                        label = "Ping",
                        value = displayPing?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"
                    )
                    SpeedMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Tune,
                        iconTint = colors.statusConnecting,
                        label = "Jitter",
                        value = displayJitter?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
                    )
                }
            }

            // Side-by-Side Comparison Summary
            if (tunneledResult != null && directResult != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceVariant,
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Direct vs. VPN Tunnel Comparison",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        ComparisonRow("Metric", "Direct", "With VPN", colors.onSurfaceVariant, colors.statusConnected, colors.primary)
                        ComparisonRow(
                            "Download",
                            String.format(Locale.US, "%.1f Mbps", directResult.downloadMbps),
                            String.format(Locale.US, "%.1f Mbps", tunneledResult.downloadMbps),
                            colors.onSurfaceVariant,
                            colors.onSurface,
                            colors.onSurface
                        )
                        ComparisonRow(
                            "Upload",
                            String.format(Locale.US, "%.1f Mbps", directResult.uploadMbps),
                            String.format(Locale.US, "%.1f Mbps", tunneledResult.uploadMbps),
                            colors.onSurfaceVariant,
                            colors.onSurface,
                            colors.onSurface
                        )
                        ComparisonRow(
                            "Ping / Jitter",
                            String.format(Locale.US, "%.0f / %.1f ms", directResult.pingMs, directResult.jitterMs),
                            String.format(Locale.US, "%.0f / %.1f ms", tunneledResult.pingMs, tunneledResult.jitterMs),
                            colors.onSurfaceVariant,
                            colors.onSurface,
                            colors.onSurface
                        )
                    }
                }
            }

            // Action Footer
            if (isRunning) {
                Button(
                    onClick = onCancelSpeedTest,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.statusError,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Test")
                }
            } else {
                Button(
                    onClick = { onRunSpeedTest(selectedMode, false) },
                    enabled = selectedMode == SpeedTestMode.DIRECT || isVpnConnected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (activeResult != null) "Run Again" else "Start Speed Test")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModePillButton(
    modifier: Modifier,
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    selectedBg: Color,
    selectedContent: Color,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val bg = if (selected) selectedBg else Color.Transparent
    val fg = if (selected) selectedContent else colors.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpeedMetricTile(
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = modifier.wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(
                    text = value,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    directVal: String,
    vpnVal: String,
    labelColor: Color,
    directColor: Color,
    vpnColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = labelColor, modifier = Modifier.weight(1f))
        Text(directVal, fontFamily = MonoFontFamily, fontSize = 12.sp, color = directColor, modifier = Modifier.weight(1f))
        Text(vpnVal, fontFamily = MonoFontFamily, fontSize = 12.sp, color = vpnColor, modifier = Modifier.weight(1f))
    }
}
