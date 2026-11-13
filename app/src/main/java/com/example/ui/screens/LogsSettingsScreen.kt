package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.local.StabilityEventEntity
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.ui.LuxyVpnUiState
import com.example.ui.components.Formatters
import com.example.ui.theme.LocalTunnelStatusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsSettingsScreen(
    uiState: LuxyVpnUiState,
    onCopyLogs: (String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalTunnelStatusColors.current
    var selectedCategory by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(uiState.stabilityEvents, selectedCategory) {
        if (selectedCategory == "ALL") uiState.stabilityEvents
        else uiState.stabilityEvents.filter { it.category == selectedCategory }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "logs_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = statusColors.connected,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live Event Logs",
                                style = MaterialTheme.typography.headlineMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${filteredLogs.size} entry(s) • Redacted safe view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val text = filteredLogs.joinToString("\n") {
                                    "[${Formatters.formatTime(it.timestamp)}] [${it.level}/${it.category}] ${it.message}"
                                }
                                onCopyLogs(text)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("copy_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy logs",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear logs",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            item(key = "logs_filter_chips") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "ALL",
                        LogCategory.TUNNEL.name,
                        LogCategory.XRAY_CORE.name,
                        LogCategory.ANTI_DPI.name,
                        LogCategory.DNS.name,
                        LogCategory.HANDOFF.name,
                        LogCategory.FAILOVER.name,
                        LogCategory.PROBE.name
                    ).forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, maxLines = 1) }
                        )
                    }
                }
            }

            if (filteredLogs.isEmpty()) {
                item(key = "logs_empty") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Text(
                            text = "No events recorded yet. Connect or run diagnostics to inspect live tunnel telemetry.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(filteredLogs, key = { it.id }) { event ->
                    LogEventRow(event = event)
                }
            }

            item(key = "logs_bottom_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LogEventRow(event: StabilityEventEntity) {
    val statusColors = LocalTunnelStatusColors.current
    val levelColor = when (event.level) {
        LogLevel.RECOVERY.name -> statusColors.connected
        LogLevel.WARN.name -> statusColors.connecting
        LogLevel.ERROR.name -> statusColors.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = levelColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = event.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = Formatters.formatTime(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
