package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.ui.screens.CleanIpScannerDialog
import com.example.ui.screens.EchProbeDialog
import com.example.ui.screens.ExpertLabScreen
import com.example.ui.screens.LogsSettingsScreen
import com.example.ui.screens.NodeEditorDialog
import com.example.ui.screens.NodesScreen
import com.example.ui.screens.PasteUriDialog
import com.example.ui.screens.RealitySniDialog
import com.example.ui.screens.ShareNodeQrDialog
import com.example.ui.screens.StabilityRulesScreen
import com.example.ui.screens.SubscriptionManagerDialog
import com.example.ui.screens.TunnelScreen
import com.example.ui.screens.VaultExportDialog
import com.example.ui.screens.VaultImportDialog
import com.example.ui.screens.WarpEndpointBenchmarkDialog
import com.example.ui.screens.XrayConfigInspectorDialog
import com.example.ui.theme.LuxyVpnTheme
import com.example.vpn.TunnelManager
import kotlinx.coroutines.launch

private const val ROUTE_EXPERT_LAB = "expert_lab"

private enum class LuxyVpnDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    TUNNEL("tunnel", "Tunnel", Icons.Rounded.PowerSettingsNew, Icons.Outlined.PowerSettingsNew, "nav_tunnel"),
    NODES("nodes", "Nodes", Icons.Rounded.Dns, Icons.Outlined.Dns, "nav_nodes"),
    SETTINGS("stability", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_stability"),
    LOGS("logs", "Logs", Icons.Rounded.Terminal, Icons.Outlined.Terminal, "nav_logs")
}

@Composable
fun LuxyVpnApp(viewModel: LuxyVpnViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPasteDialog by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<ServerProfileEntity?>(null) }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var sharingNode by remember { mutableStateOf<ServerProfileEntity?>(null) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showCleanIpDialog by remember { mutableStateOf(false) }
    var showWarpBenchmarkDialog by remember { mutableStateOf(false) }
    var showRealityDialog by remember { mutableStateOf(false) }
    var showEchDialog by remember { mutableStateOf(false) }
    var showVaultImportDialog by remember { mutableStateOf(false) }
    var exportingVaultNode by remember { mutableStateOf<ServerProfileEntity?>(null) }
    var compiledJsonDialog by remember { mutableStateOf<String?>(null) }
    var pendingConnectProfile by remember { mutableStateOf<ServerProfileEntity?>(null) }
    var autoConnectAttempted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Auto-Connect on launch when enabled and VPN permission is already granted
    LaunchedEffect(uiState.settings.autoConnectOnLaunch, uiState.selectedNode?.id) {
        val node = uiState.selectedNode
        if (!autoConnectAttempted &&
            uiState.settings.autoConnectOnLaunch &&
            node != null
        ) {
            autoConnectAttempted = true
            if (uiState.telemetry.connectionState == ConnectionState.DISCONNECTED &&
                TunnelManager.prepareVpnIntent(context) == null
            ) {
                TunnelManager.startTunnel(context, node)
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val profile = pendingConnectProfile ?: uiState.selectedNode
        pendingConnectProfile = null
        if (result.resultCode == Activity.RESULT_OK && profile != null) {
            TunnelManager.startTunnel(context, profile)
        } else if (result.resultCode != Activity.RESULT_OK) {
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission is required to start the tunnel")
            }
        }
    }

    val launchTunnelOrConsent: (ServerProfileEntity) -> Unit = { profile ->
        val prepareIntent = TunnelManager.prepareVpnIntent(context)
        if (prepareIntent != null) {
            pendingConnectProfile = profile
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            TunnelManager.startTunnel(context, profile)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val target = pendingConnectProfile ?: uiState.selectedNode
        if (target != null) {
            launchTunnelOrConsent(target)
        }
    }

    val handleToggleConnection: () -> Unit = {
        val state = uiState.telemetry.connectionState
        if (state == ConnectionState.CONNECTED ||
            state == ConnectionState.CONNECTING ||
            state == ConnectionState.RECONNECTING ||
            state == ConnectionState.FAILOVER
        ) {
            TunnelManager.stopTunnel(context)
        } else {
            val target = uiState.selectedNode
            if (target == null) {
                scope.launch {
                    snackbarHostState.showSnackbar("Import or add a proxy node first")
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingConnectProfile = target
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launchTunnelOrConsent(target)
                }
            }
        }
    }

    val handleImportClipboard: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
        } else {
            viewModel.importFromText(text, groupName = "Clipboard")
        }
    }

    val handleCopyText: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        scope.launch { snackbarHostState.showSnackbar("Copied $label to clipboard") }
    }

    LuxyVpnTheme(
        themeMode = uiState.settings.themeMode,
        colorPalette = uiState.settings.colorPalette
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val isSubScreen = currentRoute == ROUTE_EXPERT_LAB

        val navigateToTab: (String) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useRail && !isSubScreen) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            LuxyVpnDestination.entries.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail && !isSubScreen) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            LuxyVpnDestination.entries.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = LuxyVpnDestination.TUNNEL.route,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(LuxyVpnDestination.TUNNEL.route) {
                            TunnelScreen(
                                uiState = uiState,
                                onToggleConnection = handleToggleConnection,
                                onImportClipboard = handleImportClipboard,
                                onOpenManualAdd = {
                                    editingNode = null
                                    showAddNodeDialog = true
                                },
                                onOpenSubscriptions = { showSubscriptionDialog = true },
                                onNavigateToNodes = { navigateToTab(LuxyVpnDestination.NODES.route) },
                                onProbeSelected = viewModel::probeSingleNode,
                                onSelectFastest = viewModel::selectFastestNode,
                                onSetRoutingMode = viewModel::setRoutingMode
                            )
                        }

                        composable(LuxyVpnDestination.NODES.route) {
                            NodesScreen(
                                uiState = uiState,
                                onSearchChange = viewModel::setSearchQuery,
                                onGroupSelect = viewModel::setSelectedGroup,
                                onSortChange = viewModel::setSortOrder,
                                onSelectNode = viewModel::selectNode,
                                onProbeNode = viewModel::probeSingleNode,
                                onProbeAll = viewModel::probeAllNodes,
                                onSelectFastest = viewModel::selectFastestNode,
                                onTogglePin = viewModel::togglePin,
                                onEditNode = {
                                    editingNode = it
                                    showAddNodeDialog = true
                                },
                                onShareNode = { sharingNode = it },
                                onDeleteNode = viewModel::deleteServer,
                                onImportClipboard = handleImportClipboard,
                                onOpenPasteUri = { showPasteDialog = true },
                                onOpenManualAdd = {
                                    editingNode = null
                                    showAddNodeDialog = true
                                },
                                onOpenSubscriptions = { showSubscriptionDialog = true },
                                onOpenVaultImport = { showVaultImportDialog = true },
                                onOpenVaultExport = { exportingVaultNode = it }
                            )
                        }

                        composable(LuxyVpnDestination.SETTINGS.route) {
                            StabilityRulesScreen(
                                uiState = uiState,
                                onSetThemeMode = viewModel::setThemeMode,
                                onSetColorPalette = viewModel::setColorPalette,
                                onSetAutoConnectOnLaunch = viewModel::setAutoConnectOnLaunch,
                                onSetAutoReconnect = viewModel::setAutoReconnect,
                                onSetSmartFailover = viewModel::setSmartFailover,
                                onSetMtu = viewModel::setMtu,
                                onSetRoutingMode = viewModel::setRoutingMode,
                                onToggleRule = viewModel::setRoutingRuleEnabled,
                                onSaveRule = viewModel::saveRoutingRule,
                                onDeleteRule = viewModel::deleteRoutingRule,
                                onSetSplitTunnelConfig = viewModel::setSplitTunnelConfig,
                                onSetPrivacyOpSecConfig = viewModel::setPrivacyOpSecConfig,
                                onOpenExpertLab = {
                                    navController.navigate(ROUTE_EXPERT_LAB) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(ROUTE_EXPERT_LAB) {
                            ExpertLabScreen(
                                uiState = uiState,
                                onNavigateBack = { navController.popBackStack() },
                                onApplyPreset = viewModel::applyAntiCensorshipPreset,
                                onSetProbeInterval = viewModel::setProbeIntervalSec,
                                onSetMuxEnabled = viewModel::setMuxEnabled,
                                onSetMuxConcurrency = viewModel::setMuxConcurrency,
                                onSetTlsFragment = viewModel::setTlsFragmentEnabled,
                                onSetTcpFastOpen = viewModel::setTcpFastOpen,
                                onSetFragmentParams = { packets, length, interval, host, maxSplit ->
                                    viewModel.setFragmentParams(packets, length, interval, host, maxSplit)
                                },
                                onSetNoiseEnabled = { enabled -> viewModel.setNoiseConfig(enabled) },
                                onSetNoiseParams = { pkt, delay, noiseType, applyTo ->
                                    viewModel.setNoiseConfig(true, pkt, delay, noiseType, applyTo)
                                },
                                onSetUtlsFingerprint = viewModel::setUtlsFingerprint,
                                onSetFakeDns = viewModel::setFakeDnsEnabled,
                                onSetSniffing = viewModel::setSniffingEnabled,
                                onSetBlockQuicUdp = viewModel::setBlockQuicUdp,
                                onSetXhttpConfig = viewModel::setXhttpConfig,
                                onSetCipherSuites = viewModel::setCipherSuites,
                                onSetFinalMaskType = viewModel::setFinalMaskType,
                                onSetEchConfig = viewModel::setEchConfig,
                                onSetHappyEyeballs = { enabled -> viewModel.setHappyEyeballsEnabled(enabled) },
                                onSetAllowUnencryptedVless = viewModel::setAllowUnencryptedVless,
                                onTestFragmentedTls = viewModel::testFragmentedTlsOnActiveNode,
                                onOpenCleanIpScanner = { showCleanIpDialog = true },
                                onOpenWarpBenchmark = { showWarpBenchmarkDialog = true },
                                onOpenRealitySniCheck = { showRealityDialog = true },
                                onOpenEchCheck = { showEchDialog = true },
                                onOpenXrayConfigInspector = {
                                    scope.launch {
                                        compiledJsonDialog = viewModel.compileActiveXrayConfigAsync()
                                    }
                                },
                                onSetChainingConfig = viewModel::setChainingConfig,
                                onSetWarpConfig = viewModel::setWarpConfig,
                                onSetAmneziaWgParams = viewModel::setAmneziaWgParams,
                                onSetWarpNoisePreset = viewModel::setWarpNoisePreset,
                                onSetAdvancedTlsOptions = viewModel::setAdvancedTlsOptions,
                                onSetSocketMaskAndTimeouts = viewModel::setSocketMaskAndTimeouts,
                                onSetAdaptiveRoutingConfig = viewModel::setAdaptiveRoutingConfig,
                                onClearNetworkStrategies = viewModel::clearNetworkStrategies,
                                onReprobeOrResetStage = viewModel::reprobeOrResetAdaptiveStage,
                                onSaveDns = viewModel::setDnsConfig,
                                onTestDns = viewModel::testDnsLatency,
                                onVerifyDoh = viewModel::verifyDohAntiPoisoning,
                                onSetDnsPoolConfig = viewModel::setDnsPoolConfig,
                                onBenchmarkDnsPool = viewModel::benchmarkDnsPool
                            )
                        }

                        composable(LuxyVpnDestination.LOGS.route) {
                            LogsSettingsScreen(
                                uiState = uiState,
                                onCopyLogs = { logsText ->
                                    if (logsText.isNotBlank()) handleCopyText("logs", logsText)
                                },
                                onClearLogs = viewModel::clearEvents
                            )
                        }
                    }
                }
            }

            if (showPasteDialog) {
                PasteUriDialog(
                    onDismiss = { showPasteDialog = false },
                    onImport = { raw -> viewModel.importFromText(raw) }
                )
            }

            if (showAddNodeDialog) {
                NodeEditorDialog(
                    existing = editingNode,
                    onDismiss = {
                        showAddNodeDialog = false
                        editingNode = null
                    },
                    onSave = { profile ->
                        viewModel.saveServer(profile)
                    }
                )
            }

            sharingNode?.let { node ->
                ShareNodeQrDialog(
                    profile = node,
                    onDismiss = { sharingNode = null },
                    onCopyUri = { uri -> handleCopyText("node URI", uri) }
                )
            }

            if (showSubscriptionDialog) {
                SubscriptionManagerDialog(
                    subscriptions = uiState.subscriptions,
                    isSyncing = uiState.isSyncingSubscription,
                    topNLimit = uiState.settings.subscriptionTopNLimit,
                    onSetTopNLimit = viewModel::setSubscriptionTopNLimit,
                    onDismiss = { showSubscriptionDialog = false },
                    onAddSubscription = viewModel::addSubscription,
                    onSyncOne = viewModel::syncSubscription,
                    onSyncAll = viewModel::syncAllSubscriptions,
                    onDelete = viewModel::deleteSubscription
                )
            }

            if (showVaultImportDialog) {
                VaultImportDialog(
                    onDismiss = { showVaultImportDialog = false },
                    onUnlockVault = { payload, pass, priv ->
                        viewModel.importVaultBundle(payload, pass, priv) {
                            showVaultImportDialog = false
                        }
                    },
                    onCopyGeneratedPublicKey = { pubKey ->
                        handleCopyText("X25519 public key", pubKey)
                    }
                )
            }

            exportingVaultNode?.let { node ->
                VaultExportDialog(
                    profile = node,
                    onExport = { pass, pubKey, ttlHours ->
                        viewModel.exportVaultBundle(listOf(node), pass, pubKey, ttlHours)
                    },
                    onCopyVault = { env -> handleCopyText(".zsx vault", env) },
                    onDismiss = { exportingVaultNode = null }
                )
            }

            if (showCleanIpDialog) {
                CleanIpScannerDialog(
                    scanState = uiState.cleanIpScanState,
                    defaultSni = uiState.selectedNode?.sni?.ifBlank { uiState.selectedNode?.hostHeader.orEmpty() }
                        ?: "www.cloudflare.com",
                    hasActiveNode = uiState.selectedNode != null,
                    onStartScan = viewModel::scanCleanIps,
                    onApplyCleanIp = viewModel::applyCleanIpToSelectedNode,
                    onDismiss = { showCleanIpDialog = false }
                )
            }

            if (showWarpBenchmarkDialog) {
                WarpEndpointBenchmarkDialog(
                    scanState = uiState.warpEndpointScanState,
                    currentEndpoint = uiState.settings.warpEndpoint,
                    onStartBenchmark = viewModel::scanWarpEndpoints,
                    onApplyEndpoint = viewModel::applyWarpEndpoint,
                    onDismiss = { showWarpBenchmarkDialog = false }
                )
            }

            if (showRealityDialog) {
                RealitySniDialog(
                    defaultSni = uiState.selectedNode?.sni?.ifBlank { "www.cloudflare.com" } ?: "www.cloudflare.com",
                    isTesting = uiState.isTestingReality,
                    result = uiState.lastRealityProbe,
                    onTestSni = viewModel::testRealitySni,
                    onDismiss = { showRealityDialog = false }
                )
            }

            if (showEchDialog) {
                EchProbeDialog(
                    defaultDomain = uiState.selectedNode?.sni?.ifBlank { "cloudflare-ech.com" } ?: "cloudflare-ech.com",
                    isTesting = uiState.isTestingEch,
                    result = uiState.lastEchProbe,
                    onProbeEch = viewModel::probeEchDns,
                    onDismiss = { showEchDialog = false }
                )
            }

            compiledJsonDialog?.let { json ->
                XrayConfigInspectorDialog(
                    compiledJson = json,
                    onCopyJson = { handleCopyText("Xray JSON", it) },
                    onDismiss = { compiledJsonDialog = null }
                )
            }
        }
    }
}
