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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.example.core.EncryptedConfigVault
import com.example.data.model.LogLevel
import com.example.ui.LuxyVpnViewModel
import com.example.ui.components.Formatters
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsSettingsScreen(
    viewModel: LuxyVpnViewModel,
    onBack: (() -> Unit)? = null
) {
    val colors = LocalLuxyColors.current
    val events by viewModel.stabilityEvents.collectAsState()
    val activeNode by viewModel.activeNode.collectAsState()
    var levelFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(events, levelFilter) {
        events.filter { levelFilter == null || it.level.equals(levelFilter, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = colors.onBackground
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Stability Logs", style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                Text(
                    "Live Xray, DNS, GeoRule & Vault-redacted events",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { viewModel.clearLogs() },
                border = BorderStroke(1.dp, colors.outline),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear", fontSize = 12.sp, color = colors.onSurface)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("All (${events.size})", fontSize = 11.sp) }
            )
            LogLevel.entries.forEach { lvl ->
                FilterChip(
                    selected = levelFilter == lvl.name,
                    onClick = { levelFilter = if (levelFilter == lvl.name) null else lvl.name },
                    label = { Text(lvl.name, fontSize = 11.sp) }
                )
            }
        }

        if (filtered.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outline)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null, tint = colors.primary, modifier = Modifier.size(26.dp))
                    Text("No Log Events", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text("Connect LuxyVPN or run probes to see event records.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { ev ->
                    val badgeColor = when (ev.level) {
                        LogLevel.ERROR.name -> colors.statusError
                        LogLevel.WARN.name -> colors.statusConnecting
                        LogLevel.RECOVERY.name -> colors.statusConnected
                        else -> colors.statusInfo
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${ev.level} • ${ev.category}",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                                Text(
                                    text = Formatters.formatTimestamp(ev.timestamp),
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                text = EncryptedConfigVault.sanitizeLogText(ev.message, activeNode),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
