package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.EncryptedConfigVault
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.ui.LuxyVpnUiState
import com.example.ui.components.Formatters
import com.example.ui.theme.LocalTunnelStatusColors
import kotlinx.coroutines.delay

@Composable
fun TunnelScreen(
    uiState: LuxyVpnUiState,
    onToggleConnection: () -> Unit,
    onImportClipboard: () -> Unit,
    onOpenManualAdd: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onNavigateToNodes: () -> Unit,
    onProbeSelected: (ServerProfileEntity) -> Unit,
    onSelectFastest: () -> Unit,
    onSetRoutingMode: (RoutingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = uiState.telemetry
    val settings = uiState.settings
    val activeOrSelected = telemetry.activeNode ?: uiState.selectedNode

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(telemetry.connectedSinceMs, telemetry.connectionState) {
        while (telemetry.connectedSinceMs != null &&
            telemetry.connectionState != ConnectionState.DISCONNECTED
        ) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Minimal Brand & Network Header
            item(key = "header") {
                val statusColors = LocalTunnelStatusColors.current
                val brandPrimary = MaterialTheme.colorScheme.primary
                val brandSecondary = MaterialTheme.colorScheme.secondary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Canvas(modifier = Modifier.size(28.dp)) {
                            val w = size.width
                            val h = size.height
                            val shield = Path().apply {
                                moveTo(w * 0.50f, h * 0.06f)
                                lineTo(w * 0.90f, h * 0.25f)
                                lineTo(w * 0.84f, h * 0.68f)
                                lineTo(w * 0.50f, h * 0.94f)
                                lineTo(w * 0.16f, h * 0.68f)
                                lineTo(w * 0.10f, h * 0.25f)
                                close()
                            }
                            drawPath(
                                path = shield,
                                color = brandPrimary,
                                style = Stroke(width = w * 0.08f, join = StrokeJoin.Round)
                            )
                            val diamond = Path().apply {
                                moveTo(w * 0.50f, h * 0.24f)
                                lineTo(w * 0.64f, h * 0.42f)
                                lineTo(w * 0.50f, h * 0.68f)
                                lineTo(w * 0.36f, h * 0.42f)
                                close()
                            }
                            drawPath(path = diamond, color = brandSecondary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LuxyVPN",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Secure Private Tunnel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = "Network status",
                                tint = if (telemetry.isNetworkOnline) statusColors.connected else statusColors.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = telemetry.networkTransport,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 1. Clean Power Orb Card
            item(key = "power_orb") {
                MinimalPowerOrbCard(
                    state = telemetry.connectionState,
                    hasActiveNode = activeOrSelected != null,
                    currentRttMs = telemetry.currentRttMs ?: activeOrSelected?.latencyMs,
                    uptime = Formatters.formatDuration(telemetry.connectedSinceMs, nowMs),
                    lastError = telemetry.lastError,
                    onToggleConnection = onToggleConnection
                )
            }

            // 2. Onboarding OR Active Server Selector Card + Simple Mode & Speed Readout
            if (uiState.servers.isEmpty()) {
                item(key = "onboarding") {
                    EmptyNodesOnboardingCard(
                        onImportClipboard = onImportClipboard,
                        onOpenManualAdd = onOpenManualAdd,
                        onOpenSubscriptions = onOpenSubscriptions
                    )
                }
            } else if (activeOrSelected != null) {
                item(key = "active_server") {
                    MinimalActiveServerCard(
                        node = activeOrSelected,
                        onSwitchNode = onNavigateToNodes,
                        onProbe = { onProbeSelected(activeOrSelected) },
                        onSelectFastest = onSelectFastest
                    )
                }
            }

            // 3. Simple Mode Switcher (Smart Auto vs Global) + Speed Readout
            item(key = "mode_and_speed") {
                SimpleModeAndSpeedCard(
                    routingMode = settings.routingMode,
                    downloadBps = telemetry.downloadBps,
                    uploadBps = telemetry.uploadBps,
                    sessionRxBytes = telemetry.sessionRxBytes,
                    sessionTxBytes = telemetry.sessionTxBytes,
                    onSetRoutingMode = onSetRoutingMode
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MinimalPowerOrbCard(
    state: ConnectionState,
    hasActiveNode: Boolean,
    currentRttMs: Int?,
    uptime: String,
    lastError: String?,
    onToggleConnection: () -> Unit
) {
    val statusColors = LocalTunnelStatusColors.current
    val accentColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> statusColors.connecting
            ConnectionState.ERROR -> statusColors.error
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
        },
        label = "orbAccent"
    )

    val simpleStatusLabel = when (state) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> "Reconnecting"
        ConnectionState.ERROR -> "Attention Required"
    }

    val isPulsing = state == ConnectionState.CONNECTED ||
        state == ConnectionState.CONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.FAILOVER

    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val orbScale by animateFloatAsState(
        targetValue = if (state == ConnectionState.CONNECTED) 1.03f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f),
        label = "orbScale"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (state == ConnectionState.CONNECTED) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = accentColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = simpleStatusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state == ConnectionState.DISCONNECTED) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(164.dp)
                    .scale(orbScale)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2f

                    if (isPulsing) {
                        drawCircle(
                            color = accentColor.copy(alpha = pulseAlpha),
                            radius = maxRadius * pulseRadius,
                            center = center
                        )
                    }

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (state == ConnectionState.DISCONNECTED) 0.08f else 0.28f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = maxRadius * 0.92f
                        ),
                        radius = maxRadius * 0.92f,
                        center = center
                    )

                    drawCircle(
                        color = accentColor.copy(alpha = if (state == ConnectionState.DISCONNECTED) 0.35f else 0.85f),
                        radius = maxRadius * 0.66f,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (state == ConnectionState.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = if (state == ConnectionState.CONNECTED) 10.dp else 2.dp,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleConnection)
                        .testTag("power_orb_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = if (state == ConnectionState.CONNECTED) "Disconnect VPN" else "Connect VPN",
                            tint = if (state == ConnectionState.CONNECTED) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when (state) {
                        ConnectionState.CONNECTED -> uptime
                        ConnectionState.DISCONNECTED -> if (hasActiveNode) "Tap to connect" else "Add a server below"
                        ConnectionState.CONNECTING -> "Establishing secure link…"
                        ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> "Restoring connection…"
                        ConnectionState.ERROR -> lastError ?: "Connection interrupted"
                    },
                    style = if (state == ConnectionState.CONNECTED) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.bodyMedium,
                    color = if (state == ConnectionState.ERROR) statusColors.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (state == ConnectionState.CONNECTED && currentRttMs != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Latency ${Formatters.latencyLabel(currentRttMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Formatters.latencyColor(currentRttMs),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNodesOnboardingCard(
    onImportClipboard: () -> Unit,
    onOpenManualAdd: () -> Unit,
    onOpenSubscriptions: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_tunnel_hero_1794556360637),
                    contentDescription = "Encrypted tunnel topology banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                            )
                        )
                )
                Text(
                    text = "Add Your First Server",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Import a configuration link from your clipboard, add a subscription URL, or enter server details manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onImportClipboard,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("empty_import_clipboard_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clipboard", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = onOpenSubscriptions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("empty_subscription_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Subscribe", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                OutlinedButton(
                    onClick = onOpenManualAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_add_manual_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Configure Manually", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MinimalActiveServerCard(
    node: ServerProfileEntity,
    onSwitchNode: () -> Unit,
    onProbe: () -> Unit,
    onSelectFastest: () -> Unit
) {
    val proto = ProxyProtocol.fromString(node.protocol)
    val protoColor = Formatters.protocolColor(proto)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable(onClick = onSwitchNode)
            .testTag("active_server_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = protoColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = proto.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = protoColor,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = EncryptedConfigVault.redactEndpointLabel(node),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = Formatters.latencyLabel(node.latencyMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Formatters.latencyColor(node.latencyMs),
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = onProbe,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("active_node_probe_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Measure latency",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSwitchNode,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("switch_node_button")
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Switch Server", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onSelectFastest,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("auto_fastest_button")
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fastest", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimpleModeAndSpeedCard(
    routingMode: RoutingMode,
    downloadBps: Long,
    uploadBps: Long,
    sessionRxBytes: Long,
    sessionTxBytes: Long,
    onSetRoutingMode: (RoutingMode) -> Unit
) {
    val isGlobal = routingMode == RoutingMode.GLOBAL

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Routing Mode",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isGlobal,
                        onClick = { onSetRoutingMode(RoutingMode.RULE_BASED) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Route,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = { Text("Smart Auto", maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("mode_smart_auto_chip")
                    )
                    FilterChip(
                        selected = isGlobal,
                        onClick = { onSetRoutingMode(RoutingMode.GLOBAL) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = { Text("Global", maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.testTag("mode_global_chip")
                    )
                }
            }

            val statusColors = LocalTunnelStatusColors.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SpeedStatTile(
                    label = "Download",
                    speed = Formatters.formatSpeed(downloadBps),
                    total = Formatters.formatBytes(sessionRxBytes),
                    icon = Icons.Rounded.KeyboardArrowDown,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                SpeedStatTile(
                    label = "Upload",
                    speed = Formatters.formatSpeed(uploadBps),
                    total = Formatters.formatBytes(sessionTxBytes),
                    icon = Icons.Rounded.KeyboardArrowUp,
                    tint = statusColors.connected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SpeedStatTile(
    label: String,
    speed: String,
    total: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.wrapContentHeight()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = speed,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$label • $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
