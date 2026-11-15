package com.example.ui.screens

import com.example.i18n.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.EncryptedConfigVault
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ConnectionState
import com.example.data.model.StabilitySettings
import com.example.data.model.TunnelTelemetry
import com.example.ui.components.Formatters
import com.example.ui.components.PowerOrb
import com.example.ui.components.SubscriptionHero
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalThemeConfig
import com.example.ui.theme.MonoFontFamily
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelScreen(
    telemetry: TunnelTelemetry,
    activeNode: ServerProfileEntity?,
    settings: StabilitySettings,
    onToggleConnect: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenExpertLab: () -> Unit,
    subscription: SubscriptionEntity?,
    onRefreshSubscription: (SubscriptionEntity) -> Unit
) {
    val colors = LocalLuxyColors.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(telemetry.connectionState) {
        if (telemetry.connectionState == ConnectionState.CONNECTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val isConnected = telemetry.connectionState == ConnectionState.CONNECTED
    val isBusy = telemetry.connectionState == ConnectionState.CONNECTING ||
        telemetry.connectionState == ConnectionState.RECONNECTING ||
        telemetry.connectionState == ConnectionState.FAILOVER

    val statusColor by animateColorAsState(
        targetValue = when (telemetry.connectionState) {
            ConnectionState.CONNECTED -> colors.statusConnected
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            ConnectionState.FAILOVER -> colors.statusConnecting
            ConnectionState.ERROR -> colors.statusError
            ConnectionState.DISCONNECTED -> colors.onSurfaceVariant
        },
        label = "tunnelStatusColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = tr("LuxyVPN"),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground
                )
                Text(
                    text = if (settings.experimentalModeEnabled) "Zero-Leak Adaptive Xray Core" else "Standard Xray Core",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            Surface(
                modifier = Modifier.wrapContentHeight(),
                shape = RoundedCornerShape(10.dp),
                color = colors.surfaceVariant,
                border = BorderStroke(1.dp, colors.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = tr(telemetry.connectionState.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurface
                    )
                }
            }
        }

        // Subscription quota/expiry snapshot (absent when the user has no subscription).
        subscription?.let { sub ->
            SubscriptionHero(
                subscription = sub,
                onRefresh = { onRefreshSubscription(sub) }
            )
        }

        // Hero Card with Power Orb
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(22.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_tunnel_hero_1794556360637),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.18f)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.surface.copy(alpha = 0.65f),
                                    colors.surface.copy(alpha = 0.94f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Power orb: theme shape + five connection states, shared with the dock.
                    PowerOrb(
                        state = telemetry.connectionState,
                        shape = LocalThemeConfig.current.orbShape,
                        label = when {
                            isConnected -> stringResource(R.string.orb_connected)
                            isBusy -> stringResource(R.string.orb_connecting)
                            else -> stringResource(R.string.orb_connect)
                        },
                        onClick = onToggleConnect,
                        size = 112.dp
                    )

                    // Uptime & Transport Chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isConnected) {
                            TelemetryChip(
                                label = Formatters.formatDuration(telemetry.connectedSinceMs, nowMs),
                                bg = colors.primaryContainer,
                                fg = colors.onPrimaryContainer
                            )
                        }
                        if (settings.experimentalModeEnabled) {
                            TelemetryChip(
                                label = telemetry.adaptiveStageLabel,
                                bg = colors.surfaceVariant,
                                fg = colors.onSurface
                            )
                        }
                        TelemetryChip(
                            label = telemetry.networkTransport,
                            bg = colors.surfaceVariant,
                            fg = colors.onSurfaceVariant
                        )
                    }

                    telemetry.lastError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.statusError
                        )
                    }
                }
            }
        }

        // Quick Actions: Dual-Mode Speed Test & (when Experimental Mode ON) Expert Lab
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onOpenSpeedTest,
                modifier = Modifier.weight(1f).height(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(tr("Speed Test"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (settings.experimentalModeEnabled) {
                OutlinedButton(
                    onClick = onOpenExpertLab,
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Expert Lab"), color = colors.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Live RX / TX Throughput Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThroughputCell(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowDownward,
                    tint = colors.primary,
                    rate = telemetry.rxBytesPerSec,
                    total = telemetry.rxBytes
                )
                ThroughputCell(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ArrowUpward,
                    tint = colors.secondary,
                    rate = telemetry.txBytesPerSec,
                    total = telemetry.txBytes
                )
            }
        }

        // Active Server Profile Card (respects Vault isLocked & expiry)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clickable(onClick = onOpenNodes),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (activeNode?.isLocked == true) Icons.Rounded.Lock else Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = activeNode?.name ?: "No server selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            activeNode == null -> "Tap to add a node, paste a URI batch, or unlock a vault"
                            EncryptedConfigVault.isVaultExpired(activeNode) -> "Vault profile expired • cannot connect"
                            else -> buildString {
                                append(EncryptedConfigVault.redactEndpointLabel(activeNode))
                                append(" · ")
                                append(activeNode.protocol)
                                append(" · ")
                                append(tr(settings.routingMode.label))
                                if (telemetry.activeLatencyMs > 0) {
                                    append(" · ")
                                    append(telemetry.activeLatencyMs)
                                    append(" ms")
                                }
                            }
                        },
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = if (activeNode != null && EncryptedConfigVault.isVaultExpired(activeNode)) {
                            colors.statusError
                        } else {
                            colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (activeNode == null) "Select" else "Switch",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
            }
        }

        // Dialer Pipeline Summary Strip (shown only in Experimental Mode)
        if (settings.experimentalModeEnabled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = XrayConfigCompiler.describeDialerChain(settings, activeNode),
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(label: String, bg: Color, fg: Color) {
    Surface(
        modifier = Modifier.wrapContentHeight(),
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = label,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThroughputCell(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    rate: Long,
    total: Long
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = Formatters.formatRate(rate),
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = colors.onSurface,
            maxLines = 1
        )
        Text(
            text = Formatters.formatBytes(total),
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
