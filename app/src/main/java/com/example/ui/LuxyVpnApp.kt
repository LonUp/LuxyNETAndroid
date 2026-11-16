package com.example.ui

import com.example.i18n.UiText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ConnectionState
import com.example.data.model.OrbDockPosition
import com.example.ui.components.AuroraBackground
import com.example.ui.components.DockItem
import com.example.ui.components.ImportSheet
import com.example.ui.components.LuxyDock
import com.example.ui.components.LuxyTopBar
import com.example.ui.components.PowerOrb
import com.example.ui.components.SpeedTestSheet
import com.example.ui.screens.AppearanceScreen
import com.example.ui.screens.ExpertLabScreen
import com.example.ui.screens.LogsSettingsScreen
import com.example.ui.screens.NodesScreen
import com.example.ui.screens.StabilityRulesScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalThemeConfig

private enum class LuxyDestination(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Rounded.Home),
    SERVERS(R.string.tab_servers, Icons.Rounded.Dns),
    TOOLS(R.string.tab_tools, Icons.AutoMirrored.Rounded.ListAlt),
    SETTINGS(R.string.tab_settings, Icons.Rounded.Settings)
}

/** Full-screen surfaces that replace the tab body and hide the dock. */
private enum class Overlay { EXPERT_LAB, APPEARANCE, LOGS }

@Composable
fun LuxyVpnApp(
    viewModel: LuxyVpnViewModel,
    onRequestConnect: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val theme = LocalThemeConfig.current
    var currentTab by remember { mutableStateOf(LuxyDestination.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var showSpeedTestSheet by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }

    val settings by viewModel.settings.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val activeNode by viewModel.activeNode.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val uiNotice by viewModel.uiNotice.collectAsState()
    val speedTestState by viewModel.speedTestState.collectAsState()

    LaunchedEffect(settings.experimentalModeEnabled) {
        if (!settings.experimentalModeEnabled && overlay == Overlay.EXPERT_LAB) overlay = null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiLanguage = LocalConfiguration.current.locales.let { if (it.size() > 0) it.get(0)?.language else null }
    LaunchedEffect(uiNotice) {
        val msg = uiNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(UiText.translate(uiLanguage, msg))
        viewModel.clearUiNotice()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        val homeBleed = overlay == null && currentTab == LuxyDestination.HOME
        AuroraBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (homeBleed) PaddingValues() else innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val homeSurface = overlay == null && currentTab == LuxyDestination.HOME
                if (!homeSurface) {
                    LuxyTopBar(
                        modifier = Modifier.statusBarsPadding(),
                        actions = {
                            if (settings.experimentalModeEnabled) {
                                TopBarAction(Icons.Rounded.Apps, stringResource(R.string.top_apps)) {
                                    overlay = Overlay.EXPERT_LAB
                                }
                            }
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (overlay) {
                        Overlay.EXPERT_LAB -> ExpertLabScreen(
                            viewModel = viewModel,
                            onBack = { overlay = null }
                        )
                        Overlay.APPEARANCE -> AppearanceScreen(
                            theme = settings.theme,
                            onChange = { next -> viewModel.updateTheme { next } },
                            onBack = { overlay = null },
                            onMessage = { viewModel.showNotice(it) }
                        )
                        Overlay.LOGS -> LogsSettingsScreen(
                            viewModel = viewModel,
                            onBack = { overlay = null }
                        )
                        null -> when (currentTab) {
                            LuxyDestination.HOME -> TunnelScreen(
                                telemetry = telemetry,
                                activeNode = activeNode,
                                settings = settings,
                                onToggleConnect = onRequestConnect,
                                onOpenNodes = { currentTab = LuxyDestination.SERVERS },
                                onOpenSpeedTest = { showSpeedTestSheet = true },
                                onOpenExpertLab = {
                                    if (settings.experimentalModeEnabled) {
                                        overlay = Overlay.EXPERT_LAB
                                    }
                                },
                                subscription = subscriptions.firstOrNull { it.id == activeNode?.subscriptionId },
                                onRefreshSubscription = { viewModel.refreshSubscription(it) },
                                serverCount = profiles.size,
                                onOpenTools = { currentTab = LuxyDestination.TOOLS },
                                onOpenSettings = { currentTab = LuxyDestination.SETTINGS },
                                onOpenAppearance = { overlay = Overlay.APPEARANCE },
                                onOpenLogs = { overlay = Overlay.LOGS },
                                onOpenImport = { showImportSheet = true }
                            )
                            LuxyDestination.SERVERS -> NodesScreen(
                                viewModel = viewModel,
                                onConnectNode = { node ->
                                    // Persist the choice before asking for permission so the
                                    // grant callback connects to this node, not the old one.
                                    viewModel.requestConnectNode(node) { onRequestConnect() }
                                }
                            )
                            LuxyDestination.TOOLS -> ToolsScreen(
                                viewModel = viewModel,
                                onOpenSpeedTest = { showSpeedTestSheet = true },
                                onOpenImport = { showImportSheet = true },
                                onOpenAppearance = { overlay = Overlay.APPEARANCE },
                                onOpenLogs = { overlay = Overlay.LOGS },
                                onOpenExpertLab = {
                                    if (settings.experimentalModeEnabled) {
                                        overlay = Overlay.EXPERT_LAB
                                    }
                                }
                            )
                            LuxyDestination.SETTINGS -> StabilityRulesScreen(
                                viewModel = viewModel,
                                onOpenExpertLab = { overlay = Overlay.EXPERT_LAB },
                                onOpenAppearance = { overlay = Overlay.APPEARANCE }
                            )
                        }
                    }
                }

                if (theme.orbDock == OrbDockPosition.FLOATING && overlay == null && currentTab != LuxyDestination.HOME) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PowerOrb(
                            state = telemetry.connectionState,
                            shape = theme.orbShape,
                            label = "",
                            onClick = onRequestConnect,
                            size = 64.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = connectionLabel(telemetry.connectionState),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (telemetry.connectionState == ConnectionState.CONNECTED) {
                                colors.statusConnected
                            } else {
                                colors.onSurface
                            },
                            fontSize = 13.sp
                        )
                    }
                }

                if (overlay != null || currentTab != LuxyDestination.HOME) LuxyDock(modifier = Modifier.navigationBarsPadding()) {
                    LuxyDestination.entries.forEach { destination ->
                        DockItem(
                            label = stringResource(destination.labelRes),
                            icon = destination.icon,
                            selected = currentTab == destination && overlay == null,
                            onClick = {
                                overlay = null
                                currentTab = destination
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ImportSheet(viewModel = viewModel, onDismiss = { showImportSheet = false })
    }

    if (showSpeedTestSheet) {
        SpeedTestSheet(
            connectionState = telemetry.connectionState,
            activeNode = activeNode,
            isRunning = speedTestState.isRunning,
            progress = speedTestState.progress,
            results = speedTestState.results,
            errorMessage = speedTestState.errorMessage,
            onConnectVpn = onRequestConnect,
            onRunSpeedTest = { mode, action, threads, allowHandshake ->
                viewModel.runSpeedTest(mode, action, threads, allowHandshake)
            },
            onCancelSpeedTest = { viewModel.cancelSpeedTest() },
            onDismiss = { showSpeedTestSheet = false }
        )
    }
}

@Composable
private fun TopBarAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = LocalLuxyColors.current.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.DISCONNECTED -> stringResource(R.string.orb_connect)
    ConnectionState.CONNECTING -> stringResource(R.string.orb_connecting)
    ConnectionState.CONNECTED -> stringResource(R.string.orb_connected)
    ConnectionState.RECONNECTING -> stringResource(R.string.orb_reconnecting)
    ConnectionState.FAILOVER -> stringResource(R.string.orb_failover)
    ConnectionState.ERROR -> stringResource(R.string.orb_error)
}
