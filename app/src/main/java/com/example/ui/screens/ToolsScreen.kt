package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.LuxyVpnViewModel
import com.example.ui.components.IconTile
import com.example.ui.components.LuxyCard
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalLuxyMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tools tab: the reference's two-column icon-tile grid (not a list). Every tile opens a surface
 * that already exists elsewhere in the app; nothing here is a placeholder.
 */
@Composable
fun ToolsScreen(
    viewModel: LuxyVpnViewModel,
    onOpenSpeedTest: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenExpertLab: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val settings by viewModel.settings.collectAsState()
    val events by viewModel.stabilityEvents.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showCleanIpScanner by remember { mutableStateOf(false) }
    var showInspector by remember { mutableStateOf(false) }

    val experimental = settings.experimentalModeEnabled

    val tiles = buildList {
        add(
            ToolTile(
                title = stringResource(R.string.tools_speed_test),
                subtitle = stringResource(R.string.tools_speed_test_note),
                icon = Icons.Rounded.Speed,
                from = colors.primary,
                to = colors.secondary,
                onClick = onOpenSpeedTest
            )
        )
        add(
            ToolTile(
                title = stringResource(R.string.tools_import),
                subtitle = stringResource(R.string.tools_import_note),
                icon = Icons.Rounded.ContentPaste,
                from = colors.secondary,
                to = colors.primary,
                onClick = onOpenImport
            )
        )
        add(
            ToolTile(
                title = stringResource(R.string.tools_appearance),
                subtitle = stringResource(R.string.tools_appearance_note),
                icon = Icons.Rounded.Palette,
                from = colors.primary,
                to = colors.tertiary,
                onClick = onOpenAppearance
            )
        )
        add(
            ToolTile(
                title = stringResource(R.string.tools_logs),
                subtitle = stringResource(R.string.tools_logs_note),
                icon = Icons.Rounded.DeleteSweep,
                from = colors.tertiary,
                to = colors.secondary,
                onClick = onOpenLogs
            )
        )
        add(
            ToolTile(
                title = stringResource(R.string.tools_geo),
                subtitle = stringResource(R.string.tools_geo_note),
                icon = Icons.Rounded.Public,
                from = colors.statusInfo,
                to = colors.primary,
                onClick = { viewModel.refreshGeoDatabase() }
            )
        )
        add(
            ToolTile(
                title = stringResource(R.string.tools_diagnostics),
                subtitle = stringResource(R.string.tools_diagnostics_note),
                icon = Icons.Rounded.BugReport,
                from = colors.secondary,
                to = colors.statusError,
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            buildDiagnostics(
                                versionName = "3.0",
                                nodeCount = profiles.size,
                                stateName = telemetry.connectionState.name,
                                transport = telemetry.networkTransport,
                                logLines = events
                            )
                        )
                    )
                    viewModel.showNotice("Diagnostics copied")
                }
            )
        )
        if (experimental) {
            add(
                ToolTile(
                    title = stringResource(R.string.tools_clean_ip),
                    subtitle = stringResource(R.string.tools_clean_ip_note),
                    icon = Icons.Rounded.WifiTethering,
                    from = colors.primary,
                    to = colors.statusConnected,
                    onClick = { showCleanIpScanner = true }
                )
            )
            add(
                ToolTile(
                    title = stringResource(R.string.tools_inspector),
                    subtitle = stringResource(R.string.tools_inspector_note),
                    icon = Icons.Rounded.Dns,
                    from = colors.tertiary,
                    to = colors.primary,
                    onClick = { showInspector = true }
                )
            )
            add(
                ToolTile(
                    title = stringResource(R.string.tools_expert_lab),
                    subtitle = stringResource(R.string.tools_expert_lab_note),
                    icon = Icons.Rounded.Tune,
                    from = colors.secondary,
                    to = colors.tertiary,
                    onClick = onOpenExpertLab
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.tools_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground
        )
        Text(
            text = stringResource(R.string.tools_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )

        tiles.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { tile -> ToolTileCard(tile, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showCleanIpScanner) {
        CleanIpScannerDialog(viewModel = viewModel, onDismiss = { showCleanIpScanner = false })
    }
    if (showInspector) {
        XrayInspectorDialog(viewModel = viewModel, onDismiss = { showInspector = false })
    }
}

private data class ToolTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val from: Color,
    val to: Color,
    val onClick: () -> Unit
)

@Composable
private fun ToolTileCard(tile: ToolTile, modifier: Modifier) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    LuxyCard(modifier = modifier, onClick = tile.onClick) {
        IconTile(
            icon = tile.icon,
            from = tile.from,
            to = tile.to,
            size = 38.dp,
            contentDescription = null
        )
        Text(
            text = tile.title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface
        )
        Text(
            text = tile.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            minLines = 2
        )
        Spacer(Modifier.height(metrics.cardGap))
    }
}

/** Redacted support payload: no node addresses, no keys, only counts and log lines. */
internal fun buildDiagnostics(
    versionName: String,
    nodeCount: Int,
    stateName: String,
    transport: String,
    logLines: List<com.example.data.local.StabilityEventEntity>,
    nowMs: Long = System.currentTimeMillis()
): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(nowMs))
    val header = buildString {
        appendLine("LuxyVPN $versionName diagnostics")
        appendLine("at $stamp")
        appendLine("state=$stateName transport=$transport nodes=$nodeCount")
        appendLine("--- last ${minOf(logLines.size, 30)} events ---")
    }
    val body = logLines.take(30).joinToString("\n") { ev ->
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.timestamp))
        "$time ${ev.level}/${ev.category} ${ev.message}"
    }
    return header + body + "\n"
}
