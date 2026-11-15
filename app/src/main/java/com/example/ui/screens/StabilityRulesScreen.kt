package com.example.ui.screens

import com.example.i18n.tr
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.GeoRulesEngine
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SocialServiceId
import com.example.data.model.ThemeMode
import com.example.data.model.ThemePreset
import com.example.ui.LuxyVpnViewModel
import com.example.ui.components.Formatters
import com.example.i18n.LocaleManager
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.schemeForPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StabilityRulesScreen(
    viewModel: LuxyVpnViewModel,
    onOpenExpertLab: () -> Unit,
    onOpenAppearance: () -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    val settings by viewModel.settings.collectAsState()
    val rules by viewModel.routingRules.collectAsState()
    val geoDbStatus by viewModel.geoDatabaseStatus.collectAsState()

    var showCustomRuleDialog by remember { mutableStateOf(false) }
    var expandRulesList by remember { mutableStateOf(false) }
    var domesticCodeDraft by remember(settings.domesticCountryCode) { mutableStateOf(settings.domesticCountryCode) }
    var countryInput by remember { mutableStateOf("") }
    var countryAction by remember { mutableStateOf(RuleAction.DIRECT) }
    var splitPackagesDraft by remember(settings.splitTunnelPackages) { mutableStateOf(settings.splitTunnelPackages) }
    var userAgentDraft by remember(settings.subscriptionUserAgent) { mutableStateOf(settings.subscriptionUserAgent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(tr("Settings & Routing"), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)

        ExperimentalModeControlSection(
            experimentalModeEnabled = settings.experimentalModeEnabled,
            onToggleExperimentalMode = { enabled -> viewModel.setExperimentalModeEnabled(enabled) },
            onOpenExpertLab = onOpenExpertLab
        )

        // General & Connection Behavior
        SectionCard(title = tr("Tunnel & Stability")) {
            SettingSwitchRow(
                title = tr("Auto-Connect on Launch"),
                subtitle = tr("Start tunnel automatically with active profile"),
                checked = settings.autoConnectOnLaunch,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoConnectOnLaunch = v) } }
            )
            SettingSwitchRow(
                title = tr("Seamless Network Handoff"),
                subtitle = tr("Re-bind tunnel cleanly across Wi-Fi / 5G switches"),
                checked = settings.autoReconnect,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoReconnect = v) } }
            )
            SettingSwitchRow(
                title = tr("Smart Auto-Failover"),
                subtitle = tr("Switch to lowest-latency healthy node on stall"),
                checked = settings.smartFailover,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(smartFailover = v) } }
            )
            Text(tr("TUN MTU: ${settings.mtu}"), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            Slider(
                value = settings.mtu.toFloat(),
                onValueChange = { raw -> viewModel.updateSettings { it.copy(mtu = raw.roundToInt()) } },
                valueRange = 1200f..1500f,
                steps = 14
            )
        }

        // Theme engine: mode + 10 presets (full editor lives in P2)
        SectionCard(title = tr("Theme & Color Preset"), icon = Icons.Rounded.Palette) {
            val isDark = LocalIsDarkTheme.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme.mode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(tr(mode.label), fontSize = 12.sp) }
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreset.entries.forEach { preset ->
                    val isSelected = settings.theme.presetId == preset.id
                    val preview = schemeForPalette(if (isDark) preset.dark else preset.light, isDark)
                    Surface(
                        modifier = Modifier
                            .wrapContentHeight()
                            .clickable { viewModel.setThemePreset(preset) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) colors.primaryContainer else colors.surfaceVariant,
                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) colors.primary else colors.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SwatchDot(preview.background)
                            SwatchDot(preview.primary)
                            SwatchDot(preview.surface)
                            Text(
                                text = tr(preset.label),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(com.example.R.string.settings_theme_presets_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            androidx.compose.material3.TextButton(
                onClick = onOpenAppearance,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(com.example.R.string.settings_appearance),
                    color = colors.primary,
                    fontSize = 12.sp
                )
            }

            Text(
                text = stringResource(com.example.R.string.settings_language),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LocaleManager.available.forEach { tag ->
                    FilterChip(
                        selected = settings.language == tag,
                        onClick = { viewModel.setLanguage(tag) },
                        label = {
                            Text(
                                text = stringResource(
                                    if (tag == "fa") com.example.R.string.language_fa
                                    else com.example.R.string.language_en
                                ),
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }
        }

        // Routing & Split Tunnel + GeoIP & GeoSite Rules Engine
        SectionCard(title = tr("Routing & Split Tunnel"), icon = Icons.Rounded.Public) {
            Text(tr("Routing Mode"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.routingMode == mode,
                        onClick = { viewModel.setRoutingMode(mode) },
                        label = { Text(tr(mode.label), fontSize = 12.sp) }
                    )
                }
            }

            // 1-Tap Domestic Country Bypass Card
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitchRow(
                        title = tr("Domestic Country Bypass (DIRECT)"),
                        subtitle = tr("Route local banks, portals & country IPs directly without VPN"),
                        checked = settings.domesticBypassEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDomesticBypassConfig(enabled, domesticCodeDraft)
                        }
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GeoRulesEngine.DOMESTIC_COUNTRY_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = domesticCodeDraft.equals(preset.code, ignoreCase = true),
                                onClick = {
                                    domesticCodeDraft = preset.code
                                    viewModel.setDomesticBypassConfig(true, preset.code)
                                },
                                label = { Text(tr(preset.label), fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = domesticCodeDraft,
                            onValueChange = { domesticCodeDraft = it.uppercase().take(2) },
                            label = { Text(tr("ISO Code")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { viewModel.setDomesticBypassConfig(settings.domesticBypassEnabled, domesticCodeDraft) },
                            border = BorderStroke(1.dp, colors.outline)
                        ) {
                            Text(tr("Apply"))
                        }
                    }
                }
            }

            // 1-Tap Block Ads & Block Adult Content
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Block, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(18.dp))
                        Text(tr("1-Tap Traffic Filters (blackhole)"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    }
                    SettingSwitchRow(
                        title = tr("Block Ads & Trackers"),
                        subtitle = tr("Drop geosite:category-ads-all connections immediately"),
                        checked = settings.blockAdsEnabled,
                        onCheckedChange = { viewModel.setBlockAdsEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = tr("Block Adult / Porn Content"),
                        subtitle = tr("Drop geosite:category-porn domains immediately"),
                        checked = settings.blockPornEnabled,
                        onCheckedChange = { viewModel.setBlockPornEnabled(it) }
                    )
                }
            }

            // Social Media 3-Way Policy Matrix
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Language, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                        Text(tr("Social Media GeoSite Policies"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    }
                    SocialServiceId.entries.forEach { service ->
                        val currentAction = settings.socialPolicies[service] ?: RuleAction.PROXY
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tr(service.label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(RuleAction.PROXY, RuleAction.DIRECT, RuleAction.BLOCK).forEach { act ->
                                    FilterChip(
                                        selected = currentAction == act,
                                        onClick = { viewModel.setSocialServicePolicy(service, act) },
                                        label = { Text(tr(act.label), fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Country Policies (GeoIP + GeoSite)
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Additional Country Policies (GeoIP & GeoSite)"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = countryInput,
                            onValueChange = { countryInput = it.uppercase().take(2) },
                            label = { Text(tr("ISO (US, RU, DE)")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            viewModel.addOrUpdateCountryPolicy(countryInput, countryAction)
                            countryInput = ""
                        }) {
                            Icon(Icons.Rounded.Add, contentDescription = tr("Add Country Policy"), tint = colors.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = countryAction == RuleAction.DIRECT,
                            onClick = { countryAction = RuleAction.DIRECT },
                            label = { Text(tr("Direct"), fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = countryAction == RuleAction.BLOCK,
                            onClick = { countryAction = RuleAction.BLOCK },
                            label = { Text(tr("Block"), fontSize = 11.sp) }
                        )
                    }
                    if (settings.countryPolicies.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settings.countryPolicies.forEach { entry ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = colors.surface,
                                    border = BorderStroke(1.dp, colors.outline)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(tr("${entry.code}: ${entry.action.label}"), fontFamily = MonoFontFamily, fontSize = 11.sp, color = colors.onSurface)
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = tr("Remove ${entry.code}"),
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.removeCountryPolicy(entry.code) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Geo-Database Status & Verify Card
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("GeoIP & GeoSite Ruleset"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(tr("${geoDbStatus.geoipVersion} • ${geoDbStatus.builtInRuleCount} signatures"),
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant
                        )
                        geoDbStatus.lastCheckedAt?.let { ts ->
                            Text(tr("Verified at ${Formatters.formatTimestamp(ts)}"), fontSize = 11.sp, color = colors.statusConnected)
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshGeoDatabase() },
                        border = BorderStroke(1.dp, colors.outline)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Verify"), fontSize = 12.sp)
                    }
                }
            }

            // Custom Rules Expandable Editor + List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { expandRulesList = !expandRulesList }) {
                    Text(if (expandRulesList) "Hide Rules (${rules.size})" else "Inspect All Rules (${rules.size})")
                }
                OutlinedButton(
                    onClick = { showCustomRuleDialog = true },
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Add Custom Rule"), fontSize = 12.sp)
                }
            }

            if (expandRulesList) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.forEach { rule ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(tr("${rule.matchType}:${rule.pattern} → ${rule.action}"), fontFamily = MonoFontFamily, fontSize = 11.sp, color = colors.onSurfaceVariant)
                                }
                                Switch(checked = rule.isEnabled, onCheckedChange = { viewModel.toggleRoutingRule(rule) })
                                if (rule.managedKey == null) {
                                    IconButton(onClick = { viewModel.deleteRoutingRule(rule) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = tr("Delete Rule"), tint = colors.statusError, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Per-App Split Tunneling
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Text(tr("Per-App UID Split Tunneling"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppSplitTunnelMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.appSplitTunnelMode == mode,
                        onClick = { viewModel.setAppSplitTunnelMode(mode) },
                        label = { Text(tr(mode.label), fontSize = 11.sp) }
                    )
                }
            }
            if (settings.appSplitTunnelMode != AppSplitTunnelMode.DISABLED) {
                OutlinedTextField(
                    value = splitPackagesDraft,
                    onValueChange = { splitPackagesDraft = it },
                    label = { Text(tr("Package Names (comma-separated)")) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { viewModel.setSplitTunnelPackages(splitPackagesDraft) },
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Text(tr("Save App List"))
                }
            }
            SettingSwitchRow(
                title = tr("Strict UID Isolation"),
                subtitle = tr("Fail-closed if Android UID resolution is unavailable"),
                checked = settings.strictUidIsolationEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(strictUidIsolationEnabled = v) } }
            )
        }

        // Privacy, OPSEC & Subscriptions
        SectionCard(title = tr("Privacy & Subscription OPSEC"), icon = Icons.Rounded.Security) {
            SettingSwitchRow(
                title = tr("Screenshot Protection (FLAG_SECURE)"),
                subtitle = tr("Block screenshots & screen recording of vault views"),
                checked = settings.flagSecureEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(flagSecureEnabled = v) } }
            )
            SettingSwitchRow(
                title = tr("Exclude from Android Recents"),
                subtitle = tr("Hide LuxyVPN preview card from app switcher"),
                checked = settings.excludeFromRecents,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(excludeFromRecents = v) } }
            )
            SettingSwitchRow(
                title = tr("App Launch Lock"),
                subtitle = tr("Require explicit unlock prompt before viewing tunnels"),
                checked = settings.appLockEnabled,
                onCheckedChange = { v -> viewModel.updateSettings { it.copy(appLockEnabled = v) } }
            )

            Text(tr("Subscription User-Agent"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("LuxyVPN/3.0", "v2rayNG/1.9.5", "Shadowrocket/2.2", "curl/8.7.1").forEach { ua ->
                    FilterChip(
                        selected = userAgentDraft == ua,
                        onClick = {
                            userAgentDraft = ua
                            viewModel.updateSettings { it.copy(subscriptionUserAgent = ua) }
                        },
                        label = { Text(ua, fontSize = 11.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = userAgentDraft,
                    onValueChange = { userAgentDraft = it },
                    label = { Text(tr("Custom User-Agent")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { viewModel.updateSettings { it.copy(subscriptionUserAgent = userAgentDraft.ifBlank { "LuxyVPN/3.0" }) } },
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Text(tr("Save"))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showCustomRuleDialog) {
        AddCustomRuleDialog(
            onDismiss = { showCustomRuleDialog = false },
            onAdd = { name, type, pattern, action ->
                viewModel.addCustomRoutingRule(name, type, pattern, action)
                showCustomRuleDialog = false
            }
        )
    }
}

@Composable
fun ExperimentalModeControlSection(
    experimentalModeEnabled: Boolean,
    onToggleExperimentalMode: (Boolean) -> Unit,
    onOpenExpertLab: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, if (experimentalModeEnabled) colors.primary else colors.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = tr("Experimental Mode"),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (experimentalModeEnabled) {
                            "Enabled — Experimental transport extensions and adaptive features are unlocked."
                        } else {
                            "Disabled — Running as a clean, standard Xray client. Turn on to unlock advanced transport tuning, framing, ECH, chaining, and diagnostic lab tools."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = experimentalModeEnabled,
                    onCheckedChange = onToggleExperimentalMode
                )
            }
        }

        if (experimentalModeEnabled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clickable(onClick = onOpenExpertLab),
                shape = RoundedCornerShape(16.dp),
                color = colors.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, colors.primary)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(tr("Advanced / Expert Lab"),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface
                            )
                            Text(tr("Fragmentation, Noise, FinalMask, ECH, WARP/AWG & DNS Pool"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwatchDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, type: RuleMatchType, pattern: String, action: RuleAction) -> Unit
) {
    val colors = LocalLuxyColors.current
    var name by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(RuleMatchType.DOMAIN_SUFFIX) }
    var pattern by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.DIRECT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(tr("Add Custom Routing Rule"), color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Match Type"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuleMatchType.entries.forEach { t ->
                        FilterChip(selected = matchType == t, onClick = { matchType = t }, label = { Text(tr(t.label), fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(tr("Pattern (e.g. ir, category-ads-all, 10.0.0.0/8, example.com)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Rule Label (optional)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(tr("Action"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleAction.entries.forEach { act ->
                        FilterChip(selected = action == act, onClick = { action = act }, label = { Text(tr(act.label), fontSize = 11.sp) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, matchType, pattern, action) },
                enabled = pattern.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
            ) {
                Text(tr("Add Rule"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}
