package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AppBlocking
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.SubscriptionFetcher
import com.example.data.local.RoutingRuleEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ColorPalette
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.ThemeMode
import com.example.ui.LuxyVpnUiState
import com.example.ui.theme.LocalTunnelStatusColors
import com.example.ui.theme.paletteColorScheme
import com.example.ui.theme.resolveIsDarkTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StabilityRulesScreen(
    uiState: LuxyVpnUiState,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetColorPalette: (ColorPalette) -> Unit,
    onSetAutoConnectOnLaunch: (Boolean) -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
    onSetSmartFailover: (Boolean) -> Unit,
    onSetMtu: (Int) -> Unit,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onSaveRule: (RoutingRuleEntity) -> Unit,
    onDeleteRule: (String) -> Unit,
    onSetSplitTunnelConfig: (
        mode: AppSplitTunnelMode,
        packagesCsv: String,
        strictUidIsolation: Boolean
    ) -> Unit,
    onSetPrivacyOpSecConfig: (
        flagSecureEnabled: Boolean,
        excludeFromRecents: Boolean,
        appLockEnabled: Boolean,
        subscriptionUserAgent: String
    ) -> Unit,
    onOpenExpertLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val statusColors = LocalTunnelStatusColors.current
    val context = LocalContext.current
    val isDark = resolveIsDarkTheme(settings.themeMode, isSystemInDarkTheme())
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var splitPackages by remember(settings.splitTunnelPackages) { mutableStateOf(settings.splitTunnelPackages) }
    var strictUidIsolation by remember(settings.strictUidIsolationEnabled) {
        mutableStateOf(settings.strictUidIsolationEnabled)
    }
    var customUserAgent by remember(settings.subscriptionUserAgent) {
        mutableStateOf(settings.subscriptionUserAgent)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "settings_header") {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "General, Routing, Privacy & Expert Lab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Category 1: General & Appearance
            item(key = "category_general") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "General & Appearance",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onSetThemeMode(mode) },
                                    label = { Text(mode.label, maxLines = 1) },
                                    modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        SettingSwitchRow(
                            title = "Auto-Connect on Launch",
                            subtitle = "Automatically connect the active server when opening LuxyVPN",
                            checked = settings.autoConnectOnLaunch,
                            onCheckedChange = onSetAutoConnectOnLaunch,
                            tag = "auto_connect_launch_switch"
                        )

                        SettingSwitchRow(
                            title = "Seamless Network Handoff",
                            subtitle = "Automatically restores VPN on Wi-Fi to cellular switches",
                            checked = settings.autoReconnect,
                            onCheckedChange = onSetAutoReconnect,
                            tag = "stability_auto_reconnect_switch"
                        )

                        SettingSwitchRow(
                            title = "Smart Node Auto-Failover",
                            subtitle = "Switches to the fastest healthy server after repeated failures",
                            checked = settings.smartFailover,
                            onCheckedChange = onSetSmartFailover,
                            tag = "stability_smart_failover_switch"
                        )

                        Column {
                            Text(
                                text = "TUN Interface MTU: ${settings.mtu}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = settings.mtu.toFloat(),
                                onValueChange = { onSetMtu((it / 20f).roundToInt() * 20) },
                                valueRange = 1280f..1500f,
                                steps = 10
                            )
                        }
                    }
                }
            }

            // Category 1b: Color Palette Gallery
            item(key = "category_color_palette") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Color Palette",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ColorPalette.entries.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { palette ->
                                    val isSelected = settings.colorPalette == palette
                                    val previewScheme = remember(palette, isDark) {
                                        paletteColorScheme(palette, isDark, context)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onSetColorPalette(palette) }
                                            .testTag("palette_card_${palette.name.lowercase()}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                listOf(
                                                    previewScheme.primary,
                                                    previewScheme.secondary,
                                                    previewScheme.background
                                                ).forEach { swatch ->
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = swatch,
                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                                        modifier = Modifier.size(10.dp)
                                                    ) {}
                                                }
                                            }
                                            Text(
                                                text = palette.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = "Selected palette",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Category 2: Routing & Split Tunnel
            item(key = "category_routing") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Route,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Routing & Split Tunnel",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { showAddRuleDialog = true },
                                modifier = Modifier.testTag("add_routing_rule_button")
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Rule", maxLines = 1)
                            }
                        }

                        Text("Routing Strategy", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoutingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.routingMode == mode,
                                    onClick = { onSetRoutingMode(mode) },
                                    label = { Text(mode.label, maxLines = 1) }
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AppBlocking,
                                contentDescription = null,
                                tint = statusColors.connecting,
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Per-App UID Split-Tunneling", style = MaterialTheme.typography.titleSmall)
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppSplitTunnelMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.appSplitTunnelMode == mode,
                                    onClick = { onSetSplitTunnelConfig(mode, splitPackages, strictUidIsolation) },
                                    label = { Text(mode.label, maxLines = 1) },
                                    modifier = Modifier.testTag("split_tunnel_mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        Text(
                            text = settings.appSplitTunnelMode.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        SettingSwitchRow(
                            title = "Strict UID Socket Isolation (Android 10+)",
                            subtitle = "Fail-closed rejection when socket owner UID cannot be resolved",
                            checked = strictUidIsolation,
                            onCheckedChange = {
                                strictUidIsolation = it
                                onSetSplitTunnelConfig(settings.appSplitTunnelMode, splitPackages, it)
                            },
                            tag = "strict_uid_isolation_switch"
                        )

                        OutlinedTextField(
                            value = splitPackages,
                            onValueChange = { splitPackages = it },
                            label = { Text("App Package IDs (comma-separated)") },
                            placeholder = { Text("com.android.chrome, org.telegram.messenger") },
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("split_tunnel_packages_input")
                        )

                        Button(
                            onClick = {
                                onSetSplitTunnelConfig(settings.appSplitTunnelMode, splitPackages, strictUidIsolation)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apply_split_tunnel_button")
                        ) {
                            Text("Apply Split-Tunnel Policy", maxLines = 1)
                        }
                    }
                }
            }

            if (uiState.routingRules.isNotEmpty()) {
                items(uiState.routingRules, key = { it.id }) { rule ->
                    RoutingRuleCard(
                        rule = rule,
                        onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                        onDelete = { onDeleteRule(rule.id) }
                    )
                }
            }

            // Category 3: Privacy & Security
            item(key = "category_privacy") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = statusColors.connected,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Privacy & Security",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        SettingSwitchRow(
                            title = "Screenshot Guard (FLAG_SECURE)",
                            subtitle = "Blocks screenshots & screen recording across app screens",
                            checked = settings.flagSecureEnabled,
                            onCheckedChange = { enabled ->
                                onSetPrivacyOpSecConfig(enabled, settings.excludeFromRecents, settings.appLockEnabled, customUserAgent)
                            },
                            tag = "flag_secure_switch"
                        )

                        SettingSwitchRow(
                            title = "Hide from Android Recents",
                            subtitle = "Excludes LuxyVPN preview from system app switcher",
                            checked = settings.excludeFromRecents,
                            onCheckedChange = { enabled ->
                                onSetPrivacyOpSecConfig(settings.flagSecureEnabled, enabled, settings.appLockEnabled, customUserAgent)
                            },
                            tag = "exclude_recents_switch"
                        )

                        SettingSwitchRow(
                            title = "Biometric / PIN App Lock",
                            subtitle = "Requires device authentication before opening LuxyVPN",
                            checked = settings.appLockEnabled,
                            onCheckedChange = { enabled ->
                                onSetPrivacyOpSecConfig(settings.flagSecureEnabled, settings.excludeFromRecents, enabled, customUserAgent)
                            },
                            tag = "app_lock_switch"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Text("Subscription HTTP User-Agent", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SubscriptionFetcher.USER_AGENT_PRESETS.forEach { preset ->
                                FilterChip(
                                    selected = customUserAgent == preset,
                                    onClick = {
                                        customUserAgent = preset
                                        onSetPrivacyOpSecConfig(settings.flagSecureEnabled, settings.excludeFromRecents, settings.appLockEnabled, preset)
                                    },
                                    label = { Text(preset, maxLines = 1) },
                                    modifier = Modifier.testTag("ua_preset_${preset.substringBefore("/")}")
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customUserAgent,
                                onValueChange = { customUserAgent = it },
                                label = { Text("Custom User-Agent") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("custom_user_agent_input")
                            )
                            Button(
                                onClick = {
                                    onSetPrivacyOpSecConfig(
                                        settings.flagSecureEnabled,
                                        settings.excludeFromRecents,
                                        settings.appLockEnabled,
                                        customUserAgent.trim().ifBlank { SubscriptionFetcher.DEFAULT_USER_AGENT }
                                    )
                                },
                                modifier = Modifier.testTag("apply_user_agent_button")
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                }
            }

            // Category 4: Advanced / Expert Lab Entry Card
            item(key = "category_expert_lab") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenExpertLab)
                        .testTag("open_expert_lab_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Science,
                                contentDescription = "Expert Lab",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Advanced / Expert Lab",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "DPI evasion, TLS Fragmentation, ECH, Multi-Hop Chaining, WARP/AmneziaWG, DNS Pool & Xray Inspector",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Expert Lab",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showAddRuleDialog) {
        AddRoutingRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onSave = { rule ->
                onSaveRule(rule)
                showAddRuleDialog = false
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun RoutingRuleCard(
    rule: RoutingRuleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val statusColors = LocalTunnelStatusColors.current
    val actionColor = when (rule.action) {
        RuleAction.PROXY.name -> MaterialTheme.colorScheme.primary
        RuleAction.DIRECT.name -> statusColors.connected
        else -> statusColors.error
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = actionColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = rule.action,
                            style = MaterialTheme.typography.labelSmall,
                            color = actionColor,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${rule.matchType}: ${rule.pattern}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete rule",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRoutingRuleDialog(
    onDismiss: () -> Unit,
    onSave: (RoutingRuleEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(RuleMatchType.DOMAIN_SUFFIX) }
    var action by remember { mutableStateOf(RuleAction.DIRECT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Routing Rule", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rule_name_input")
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern (e.g. .example.com or 192.168.0.0/16)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rule_pattern_input")
                )

                Text("Match Type", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RuleMatchType.entries.forEach { type ->
                        FilterChip(
                            selected = matchType == type,
                            onClick = { matchType = type },
                            label = { Text(type.label, maxLines = 1) }
                        )
                    }
                }

                Text("Action", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RuleAction.entries.forEach { act ->
                        FilterChip(
                            selected = action == act,
                            onClick = { action = act },
                            label = { Text(act.label, maxLines = 1) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RoutingRuleEntity(
                            name = name.trim().ifBlank { pattern.trim() },
                            matchType = matchType.name,
                            pattern = pattern.trim(),
                            action = action.name
                        )
                    )
                },
                enabled = pattern.isNotBlank(),
                modifier = Modifier.testTag("save_rule_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
