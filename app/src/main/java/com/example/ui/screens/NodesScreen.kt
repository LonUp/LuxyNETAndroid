package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.EncryptedConfigVault
import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeSortOrder
import com.example.data.model.ProxyProtocol
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodesScreen(
    viewModel: LuxyVpnViewModel,
    onConnectNode: (ServerProfileEntity) -> Unit
) {
    val colors = LocalLuxyColors.current
    val scope = rememberCoroutineScope()
    val profiles by viewModel.profiles.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val protocolFilter by viewModel.protocolFilter.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    var editingNode by remember { mutableStateOf<ServerProfileEntity?>(null) }
    var showAddManual by remember { mutableStateOf(false) }
    var showBatchPaste by remember { mutableStateOf(false) }
    var showSubscriptions by remember { mutableStateOf(false) }
    var showVaultDialog by remember { mutableStateOf(false) }
    var qrShareNode by remember { mutableStateOf<ServerProfileEntity?>(null) }

    val filtered = remember(profiles, searchQuery, protocolFilter, sortOrder) {
        profiles
            .filter { node ->
                val matchesSearch = searchQuery.isBlank() ||
                    node.name.contains(searchQuery, ignoreCase = true) ||
                    node.groupName.contains(searchQuery, ignoreCase = true) ||
                    (!node.isLocked && node.address.contains(searchQuery, ignoreCase = true))
                val matchesProto = protocolFilter == null || node.protocol.equals(protocolFilter, ignoreCase = true)
                matchesSearch && matchesProto
            }
            .let { list ->
                when (sortOrder) {
                    NodeSortOrder.DEFAULT -> list.sortedWith(compareByDescending<ServerProfileEntity> { it.isPinned }.thenByDescending { it.createdAt })
                    NodeSortOrder.LATENCY -> list.sortedWith(compareByDescending<ServerProfileEntity> { it.isPinned }.thenBy { it.latencyMs ?: Int.MAX_VALUE })
                    NodeSortOrder.NAME -> list.sortedWith(compareByDescending<ServerProfileEntity> { it.isPinned }.thenBy { it.name.lowercase() })
                    NodeSortOrder.PROTOCOL -> list.sortedWith(compareByDescending<ServerProfileEntity> { it.isPinned }.thenBy { it.protocol })
                }
            }
    }

    Scaffold(
        containerColor = colors.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddManual = true },
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Node", modifier = Modifier.size(22.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Server Nodes", style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                    Text(
                        "${profiles.size} configured • Tap to select",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.pingAllNodes() },
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.NetworkPing, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ping All", fontSize = 12.sp, color = colors.onSurface)
                }
            }

            // Quick Import & Vault Actions
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showBatchPaste = true },
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste URI / JSON", fontSize = 12.sp, color = colors.onSurface)
                }
                OutlinedButton(
                    onClick = { showSubscriptions = true },
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.RssFeed, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Subscriptions (${subscriptions.size})", fontSize = 12.sp, color = colors.onSurface)
                }
                OutlinedButton(
                    onClick = { showVaultDialog = true },
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vault .zsx", fontSize = 12.sp, color = colors.onSurface)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search nodes by name, group, or host") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Protocol & Sort Filter Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = protocolFilter == null,
                    onClick = { viewModel.setProtocolFilter(null) },
                    label = { Text("All", fontSize = 11.sp) }
                )
                com.example.core.ProtocolGuardrails.let { guardrails ->
                    ProxyProtocol.entries
                        .filter { settings.experimentalModeEnabled || !guardrails.isExperimentalProtocol(it) }
                        .forEach { proto ->
                            FilterChip(
                                selected = protocolFilter == proto.name,
                                onClick = { viewModel.setProtocolFilter(if (protocolFilter == proto.name) null else proto.name) },
                                label = { Text(proto.shortTag, fontSize = 11.sp) }
                            )
                        }
                }
                FilterChip(
                    selected = sortOrder == NodeSortOrder.LATENCY,
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == NodeSortOrder.LATENCY) NodeSortOrder.DEFAULT else NodeSortOrder.LATENCY
                        )
                    },
                    label = { Text("Fastest", fontSize = 11.sp) }
                )
            }

            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                        Text("No Nodes Yet", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                        Text(
                            "Tap + to add a profile manually, paste URIs/JSON, or import a subscription.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { node ->
                        NodeItemCard(
                            node = node,
                            isSelected = node.id == settings.activeProfileId,
                            onSelect = { viewModel.selectActiveNode(node.id) },
                            onConnect = { onConnectNode(node) },
                            onPing = { viewModel.pingNode(node) },
                            onTogglePin = { viewModel.togglePin(node) },
                            onShareQr = { qrShareNode = node },
                            onEdit = { editingNode = node },
                            onDelete = { viewModel.deleteNode(node) }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (showAddManual || editingNode != null) {
        AddEditNodeDialog(
            initialNode = editingNode,
            experimentalModeEnabled = settings.experimentalModeEnabled,
            onDismiss = {
                showAddManual = false
                editingNode = null
            },
            onSave = { saved ->
                viewModel.saveNode(saved)
                showAddManual = false
                editingNode = null
            }
        )
    }

    if (showBatchPaste) {
        BatchImportDialog(
            onDismiss = { showBatchPaste = false },
            onImport = { raw, group ->
                scope.launch {
                    val count = viewModel.importBatchTextAsync(raw, group)
                    if (count > 0) showBatchPaste = false
                }
            }
        )
    }

    if (showSubscriptions) {
        SubscriptionsDialog(
            subscriptions = subscriptions,
            settings = settings,
            onSaveSubscription = { viewModel.addOrUpdateSubscription(it, fetchNow = true) },
            onRefreshSubscription = { viewModel.refreshSubscription(it) },
            onDeleteSubscription = { viewModel.deleteSubscription(it) },
            onDismiss = { showSubscriptions = false }
        )
    }

    if (showVaultDialog) {
        EncryptedVaultDialog(
            profiles = profiles,
            onImportVault = { payload, pass, privKey ->
                viewModel.importEncryptedVaultAsync(payload, pass, privKey)
            },
            onExportVault = { exportNodes, useX25519, pass, pubKey, expMs ->
                viewModel.exportEncryptedVaultAsync(exportNodes, useX25519, pass, pubKey, expMs)
            },
            onDismiss = { showVaultDialog = false }
        )
    }

    qrShareNode?.let { node ->
        QrShareDialog(
            node = node,
            onDismiss = { qrShareNode = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeItemCard(
    node: ServerProfileEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onPing: () -> Unit,
    onTogglePin: () -> Unit,
    onShareQr: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val isExpired = EncryptedConfigVault.isVaultExpired(node)
    val borderColor = if (isSelected) colors.primary else colors.outline

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) colors.primaryContainer.copy(alpha = 0.25f) else colors.surface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (node.isLocked) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Locked Vault",
                            tint = colors.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val latencyText = when {
                    node.latencyMs == null -> "—"
                    node.latencyMs < 0 -> "Timeout"
                    else -> "${node.latencyMs} ms"
                }
                val latencyColor = when {
                    node.latencyMs == null -> colors.onSurfaceVariant
                    node.latencyMs < 0 -> colors.statusError
                    node.latencyMs < 180 -> colors.statusConnected
                    else -> colors.statusConnecting
                }
                Text(
                    text = latencyText,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = latencyColor
                )
            }

            Text(
                text = if (isExpired) {
                    "Expired Locked Vault Profile"
                } else {
                    EncryptedConfigVault.redactEndpointLabel(node)
                },
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = if (isExpired) colors.statusError else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant) {
                    Text(
                        text = node.protocol,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                if (node.groupName.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant) {
                        Text(
                            text = node.groupName,
                            fontSize = 10.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPing, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.NetworkPing, contentDescription = "Ping", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onTogglePin, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = "Pin",
                        tint = if (node.isPinned) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (!node.isLocked) {
                    IconButton(onClick = onShareQr, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = "Share QR", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = colors.statusError, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = !isExpired, onClick = onConnect),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) colors.primary else colors.primaryContainer
                ) {
                    Text(
                        text = "Use",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) colors.onPrimary else colors.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
