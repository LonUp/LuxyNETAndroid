package com.example.ui.components

import com.example.i18n.tr
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.model.SPEED_TEST_MULTI_THREAD
import com.example.data.model.SPEED_TEST_SINGLE_THREAD
import com.example.data.model.SpeedTestAction
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestProgress
import com.example.data.model.SpeedTestResult
import com.example.data.model.SpeedTestStage
import com.example.ui.SpeedTestUiState
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val GAUGE_MAX_MBPS = 200.0

/**
 * Compact single-screen speed test: pick phases (All / Ping / Download / Upload), pick
 * single- vs multi-threaded throughput, and read the gauge. Advanced context (exit node, direct
 * comparison) stays collapsed unless it carries information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestSheet(
    connectionState: ConnectionState,
    activeNode: ServerProfileEntity?,
    isRunning: Boolean,
    progress: SpeedTestProgress?,
    results: Map<String, SpeedTestResult>,
    errorMessage: String?,
    onConnectVpn: () -> Unit,
    onRunSpeedTest: (mode: SpeedTestMode, action: SpeedTestAction, threads: Int, allowDisconnectedHandshakeProbe: Boolean) -> Unit,
    onCancelSpeedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val isVpnConnected = connectionState == ConnectionState.CONNECTED
    var selectedMode by remember(connectionState) {
        mutableStateOf(if (isVpnConnected) SpeedTestMode.TUNNELED else SpeedTestMode.DIRECT)
    }
    var selectedAction by remember { mutableStateOf(SpeedTestAction.FULL) }
    var threads by remember { mutableIntStateOf(SPEED_TEST_SINGLE_THREAD) }
    var showDetails by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeResult = results[SpeedTestUiState.speedTestKey(selectedMode, selectedAction)]
    val activeProgress = progress?.takeIf { it.mode == selectedMode && it.action == selectedAction }

    val displayStage = activeProgress?.stage
        ?: if (activeResult != null) SpeedTestStage.COMPLETE else SpeedTestStage.IDLE
    val displayMbps = when {
        activeProgress != null && isRunning -> activeProgress.currentMbps
        selectedAction == SpeedTestAction.PING -> 0.0
        activeResult != null -> activeResult.downloadMbps
        else -> 0.0
    }
    val displayPing = activeProgress?.pingMs ?: activeResult?.pingMs
    val displayJitter = activeProgress?.jitterMs ?: activeResult?.jitterMs
    val displayDownload = activeProgress?.downloadMbps
        ?: activeResult?.downloadMbps?.takeIf { selectedAction != SpeedTestAction.PING }
    val displayUpload = activeProgress?.uploadMbps
        ?: activeResult?.uploadMbps?.takeIf { selectedAction != SpeedTestAction.PING }
    val warnings = activeProgress?.warnings?.takeIf { it.isNotEmpty() }
        ?: activeResult?.warnings?.takeIf { it.isNotEmpty() }

    val tunneledFull = results[SpeedTestUiState.speedTestKey(SpeedTestMode.TUNNELED, SpeedTestAction.FULL)]
    val directFull = results[SpeedTestUiState.speedTestKey(SpeedTestMode.DIRECT, SpeedTestAction.FULL)]

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tr("Speed Test"),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (isRunning) {
                    Text(
                        text = tr(displayStage.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    if (isRunning) onCancelSpeedTest()
                    onDismiss()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = tr("Close"), tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            // Phase selector — the whole point of the compact sheet.
            SegmentedRow {
                SpeedTestAction.entries.forEach { action ->
                    SegmentPill(
                        modifier = Modifier.weight(1f),
                        selected = selectedAction == action,
                        enabled = !isRunning,
                        label = action.label,
                        onClick = { selectedAction = action }
                    )
                }
            }

            // Options: tunnel vs direct + stream count, one tight row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentedRow(modifier = Modifier.weight(1f)) {
                    SegmentPill(
                        modifier = Modifier.weight(1f),
                        selected = selectedMode == SpeedTestMode.TUNNELED,
                        enabled = !isRunning,
                        icon = Icons.Rounded.Shield,
                        label = tr("VPN"),
                        onClick = { selectedMode = SpeedTestMode.TUNNELED }
                    )
                    SegmentPill(
                        modifier = Modifier.weight(1f),
                        selected = selectedMode == SpeedTestMode.DIRECT,
                        enabled = !isRunning,
                        icon = Icons.Rounded.Wifi,
                        label = tr("Direct"),
                        onClick = { selectedMode = SpeedTestMode.DIRECT }
                    )
                }
                SegmentedRow(modifier = Modifier.width(96.dp)) {
                    SegmentPill(
                        modifier = Modifier.weight(1f),
                        selected = threads == SPEED_TEST_SINGLE_THREAD,
                        enabled = !isRunning && selectedAction != SpeedTestAction.PING,
                        label = "1×",
                        onClick = { threads = SPEED_TEST_SINGLE_THREAD }
                    )
                    SegmentPill(
                        modifier = Modifier.weight(1f),
                        selected = threads == SPEED_TEST_MULTI_THREAD,
                        enabled = !isRunning && selectedAction != SpeedTestAction.PING,
                        label = "4×",
                        onClick = { threads = SPEED_TEST_MULTI_THREAD }
                    )
                }
            }

            // Gauge: animated, square-root scaled so 10 Mbps is visibly different from 90 Mbps.
            val animatedValue by animateFloatAsState(
                targetValue = displayMbps.toFloat(),
                animationSpec = tween(durationMillis = 350, easing = LinearEasing),
                label = "speed-needle"
            )
            val ratio = if (selectedAction == SpeedTestAction.PING) {
                activeProgress?.progressFraction ?: 0f
            } else {
                speedRatio(animatedValue.toDouble())
            }
            val accentColor = when {
                displayStage == SpeedTestStage.UPLOAD -> colors.secondary
                selectedMode == SpeedTestMode.DIRECT -> colors.statusConnected
                else -> colors.primary
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(146.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(width = 200.dp, height = 132.dp)) {
                    val strokeWidth = 11.dp.toPx()
                    val diameter = size.width - strokeWidth * 2f
                    val topLeft = Offset(strokeWidth, strokeWidth)
                    val arcSize = Size(diameter, diameter)
                    val cx = topLeft.x + diameter / 2f
                    val cy = topLeft.y + diameter / 2f
                    val r = diameter / 2f

                    drawArc(
                        color = colors.outlineVariant,
                        startAngle = 150f,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    for (i in 0..8) {
                        val angleRad = ((150f + 240f * i / 8f) * PI / 180.0)
                        val innerR = r - 13.dp.toPx()
                        val outerR = r - 8.dp.toPx()
                        drawLine(
                            color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            start = Offset((cx + cos(angleRad) * innerR).toFloat(), (cy + sin(angleRad) * innerR).toFloat()),
                            end = Offset((cx + cos(angleRad) * outerR).toFloat(), (cy + sin(angleRad) * outerR).toFloat()),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    if (ratio > 0.005f) {
                        val sweep = 240f * ratio
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
                            color = colors.onSurface,
                            radius = 4.5.dp.toPx(),
                            center = Offset((cx + cos(tipRad) * r).toFloat(), (cy + sin(tipRad) * r).toFloat())
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    val centerValue = if (displayStage == SpeedTestStage.PING && displayPing != null) {
                        String.format(Locale.US, "%.0f", displayPing)
                    } else if (selectedAction == SpeedTestAction.PING) {
                        displayPing?.let { String.format(Locale.US, "%.0f", it) } ?: "—"
                    } else {
                        String.format(Locale.US, "%.1f", displayMbps)
                    }
                    Text(
                        text = centerValue,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (displayStage == SpeedTestStage.PING || selectedAction == SpeedTestAction.PING) "ms" else "Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            // One row of numbers instead of a 2x2 tile grid.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Timer,
                    tint = colors.statusConnected,
                    label = tr("Ping"),
                    value = displayPing?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowDownward,
                    tint = colors.primary,
                    label = tr("Down"),
                    value = displayDownload?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowUpward,
                    tint = colors.secondary,
                    label = tr("Up"),
                    value = displayUpload?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                )
            }

            if (selectedMode == SpeedTestMode.TUNNELED && !isVpnConnected && !isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = tr("VPN off — connect for throughput"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.statusConnecting,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onConnectVpn) {
                        Text(tr("Connect"), fontSize = 12.sp, color = colors.primary)
                    }
                    if (activeNode != null) {
                        TextButton(onClick = {
                            onRunSpeedTest(SpeedTestMode.TUNNELED, SpeedTestAction.PING, SPEED_TEST_SINGLE_THREAD, true)
                        }) {
                            Text(tr("Handshake"), fontSize = 12.sp, color = colors.onSurface)
                        }
                    }
                }
            }

            val note = activeProgress?.statusNote
            val warningText = warnings?.lastOrNull()
            val message = errorMessage ?: warningText ?: note
            if (message != null) {
                Text(
                    text = tr(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        errorMessage != null -> colors.statusError
                        warningText != null -> colors.statusConnecting
                        else -> colors.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = activeProgress?.exitLabel
                        ?: activeResult?.exitLabel
                        ?: SpeedTestEngine.resolveSafeExitLabel(selectedMode, activeNode),
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (showDetails) "Hide" else "Details",
                    fontSize = 10.sp,
                    color = colors.primary
                )
            }

            if (showDetails) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        DetailRow("Threads", if (threads <= 1) "Single stream" else "$threads streams")
                        DetailRow("Jitter", displayJitter?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—")
                        DetailRow(tr("Phases"), tr(selectedAction.label))
                        if (tunneledFull != null && directFull != null) {
                            DetailRow(
                                "Direct / VPN down",
                                String.format(
                                    Locale.US,
                                    "%.1f / %.1f Mbps",
                                    directFull.downloadMbps,
                                    tunneledFull.downloadMbps
                                )
                            )
                        }
                    }
                }
            }

            if (isRunning) {
                Button(
                    onClick = onCancelSpeedTest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.statusError,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Stop"), fontSize = 14.sp)
                }
            } else {
                Button(
                    onClick = {
                        onRunSpeedTest(
                            selectedMode,
                            selectedAction,
                            if (selectedAction == SpeedTestAction.PING) SPEED_TEST_SINGLE_THREAD else threads,
                            false
                        )
                    },
                    enabled = selectedMode == SpeedTestMode.DIRECT || isVpnConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (activeResult != null) {
                            "Run again"
                        } else {
                            "Start ${selectedAction.label.lowercase(Locale.US)}"
                        },
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/** 0..1 needle position: sqrt compresses the top of the scale so low speeds still move the needle. */
internal fun speedRatio(mbps: Double): Float =
    (mbps.coerceIn(0.0, GAUGE_MAX_MBPS) / GAUGE_MAX_MBPS).pow(0.5).toFloat()

@Composable
private fun SegmentedRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceLowest)
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentPill(
    modifier: Modifier,
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    val colors = LocalLuxyColors.current
    val bg = if (selected) colors.primaryContainer else Color.Transparent
    val fg = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricPill(
    modifier: Modifier,
    icon: ImageVector,
    tint: Color,
    label: String,
    value: String
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Column {
                Text(label, fontSize = 9.sp, color = colors.onSurfaceVariant, maxLines = 1)
                Text(
                    text = value,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalLuxyColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant)
        Text(value, fontFamily = MonoFontFamily, fontSize = 11.sp, color = colors.onSurface)
    }
}
