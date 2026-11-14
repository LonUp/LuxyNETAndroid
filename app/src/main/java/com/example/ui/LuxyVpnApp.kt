package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.components.SpeedTestSheet
import com.example.ui.screens.ExpertLabScreen
import com.example.ui.screens.LogsSettingsScreen
import com.example.ui.screens.NodesScreen
import com.example.ui.screens.StabilityRulesScreen
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.LocalLuxyColors

private enum class LuxyDestination(val label: String, val icon: ImageVector) {
    TUNNEL("Tunnel", Icons.Rounded.Shield),
    NODES("Nodes", Icons.Rounded.Dns),
    SETTINGS("Settings", Icons.Rounded.Settings),
    LOGS("Logs", Icons.AutoMirrored.Rounded.ListAlt)
}

@Composable
fun LuxyVpnApp(
    viewModel: LuxyVpnViewModel,
    onRequestConnect: () -> Unit
) {
    val colors = LocalLuxyColors.current
    var currentTab by remember { mutableStateOf(LuxyDestination.TUNNEL) }
    var showExpertLab by remember { mutableStateOf(false) }
    var showSpeedTestSheet by remember { mutableStateOf(false) }

    val settings by viewModel.settings.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val activeNode by viewModel.activeNode.collectAsState()
    val uiNotice by viewModel.uiNotice.collectAsState()
    val speedTestState by viewModel.speedTestState.collectAsState()

    LaunchedEffect(settings.experimentalModeEnabled) {
        if (!settings.experimentalModeEnabled) {
            showExpertLab = false
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiNotice) {
        val msg = uiNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearUiNotice()
    }

    val isExpertLabVisible = showExpertLab && settings.experimentalModeEnabled

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!isExpertLabVisible) {
                NavigationBar(
                    containerColor = colors.surface,
                    contentColor = colors.onSurface
                ) {
                    LuxyDestination.entries.forEach { dest ->
                        val selected = currentTab == dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = dest },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.primary,
                                selectedTextColor = colors.primary,
                                indicatorColor = colors.primaryContainer,
                                unselectedIconColor = colors.onSurfaceVariant,
                                unselectedTextColor = colors.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isExpertLabVisible) {
                ExpertLabScreen(
                    viewModel = viewModel,
                    onBack = { showExpertLab = false }
                )
            } else {
                when (currentTab) {
                    LuxyDestination.TUNNEL -> TunnelScreen(
                        telemetry = telemetry,
                        activeNode = activeNode,
                        settings = settings,
                        onToggleConnect = onRequestConnect,
                        onOpenNodes = { currentTab = LuxyDestination.NODES },
                        onOpenSpeedTest = { showSpeedTestSheet = true },
                        onOpenExpertLab = {
                            if (settings.experimentalModeEnabled) {
                                showExpertLab = true
                            }
                        }
                    )
                    LuxyDestination.NODES -> NodesScreen(
                        viewModel = viewModel,
                        onConnectNode = { node ->
                            val prep = viewModel.prepareVpnIntent()
                            if (prep != null) {
                                onRequestConnect()
                            } else {
                                viewModel.connectNode(node)
                            }
                        }
                    )
                    LuxyDestination.SETTINGS -> StabilityRulesScreen(
                        viewModel = viewModel,
                        onOpenExpertLab = { showExpertLab = true }
                    )
                    LuxyDestination.LOGS -> LogsSettingsScreen(
                        viewModel = viewModel
                    )
                }
            }
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
                onRunSpeedTest = { mode, allowHandshake ->
                    viewModel.runSpeedTest(mode, allowHandshake)
                },
                onCancelSpeedTest = { viewModel.cancelSpeedTest() },
                onDismiss = { showSpeedTestSheet = false }
            )
        }
    }
}
