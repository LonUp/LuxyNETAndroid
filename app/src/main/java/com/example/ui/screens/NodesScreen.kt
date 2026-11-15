package com.example.ui.screens

import com.example.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.core.ProtocolGuardrails
import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeSortOrder
import com.example.data.model.ProxyProtocol
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

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
    var searchOpen by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

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

    Scaffold(containerColor = colors.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact toolbar: title + count on the left, icon-only actions on the right.
            val activeName = remember(profiles, settings.activeProfileId) {
                profiles.firstOrNull { it.id == settings.activeProfileId }?.name
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(tr("Servers"), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    Text(
                        text = tr("${filtered.size}/${profiles.size} • ${activeName ?: "none selected"}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CompactIconButton(
                    icon = Icons.Rounded.Search,
                    description = tr("Search"),
                    highlighted = searchOpen || searchQuery.isNotBlank()
                ) {
                    searchOpen = !searchOpen
                    if (!searchOpen) viewModel.setSearchQuery("")
                }
                CompactIconButton(
                    icon = Icons.Rounded.NetworkPing,
                    description = tr("Ping all"),
                    highlighted = false
                ) { viewModel.pingAllNodes() }
                Box {
                    CompactIconButton(
                        icon = Icons.Rounded.MoreVert,
                        description = tr("More"),
                        highlighted = false
                    ) { showActions = true }
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("Paste URI / JSON"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showActions = false
                                showBatchPaste = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Subscriptions (${subscriptions.size})"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.RssFeed, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showActions = false
                                showSubscriptions = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Vault .zsx"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showActions = false
                                showVaultDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Add server manually"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showActions = false
                                showAddManual = true
                            }
                        )
                    }
                }
                IconButton(onClick = { showAddManual = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = tr("Add Node"),
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (searchOpen || searchQuery.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("Search name, group or host"), fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // Protocol & sort filters: one horizontally scrollable row instead of a FlowRow.
            // Only protocols that actually exist in the list are offered, so the strip stays short
            // and the node list keeps the rest of the screen.
            val protocolOptions = remember(profiles, settings.experimentalModeEnabled) {
                profiles
                    .map { ProxyProtocol.parse(it.protocol) }
                    .distinct()
                    .filter { settings.experimentalModeEnabled || !ProtocolGuardrails.isExperimentalProtocol(it) }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = protocolFilter == null,
                        onClick = { viewModel.setProtocolFilter(null) },
                        label = { Text(tr("All"), fontSize = 11.sp) }
                    )
                }
                items(protocolOptions, key = { it.name }) { proto ->
                    FilterChip(
                        selected = protocolFilter == proto.name,
                        onClick = {
                            viewModel.setProtocolFilter(if (protocolFilter == proto.name) null else proto.name)
                        },
                        label = { Text(proto.shortTag, fontSize = 11.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = sortOrder == NodeSortOrder.LATENCY,
                        onClick = {
                            viewModel.setSortOrder(
                                if (sortOrder == NodeSortOrder.LATENCY) NodeSortOrder.DEFAULT else NodeSortOrder.LATENCY
                            )
                        },
                        label = { Text(tr("Fastest"), fontSize = 11.sp) }
                    )
                }
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
                        Text(tr("No Nodes Yet"), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                        Text(tr("Tap + to add a profile manually, paste URIs/JSON, or import a subscription."),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // weight, not fillMaxSize: the list must take the space left by the header,
                    // otherwise it is measured full-height and its viewport slides off-screen.
                    modifier = Modifier.weight(1f)
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
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) colors.primaryContainer.copy(alpha = 0.22f) else colors.surface,
        border = BorderStroke(if (isSelected) 1.2.dp else 1.dp, if (isSelected) colors.primary else colors.outline)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) colors.primary else colors.surfaceVariant,
                modifier = Modifier.size(width = 3.dp, height = 30.dp)
            ) {}

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (node.isLocked) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = tr("Locked vault"),
                            tint = colors.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (node.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = tr("Pinned"),
                            tint = colors.primary,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        text = node.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (isExpired) {
                        "Expired vault profile"
                    } else {
                        buildString {
                            append(ProxyProtocol.parse(node.protocol).shortTag)
                            append(" · ")
                            append(EncryptedConfigVault.redactEndpointLabel(node))
                            if (node.groupName.isNotBlank()) {
                                append(" · ")
                                append(node.groupName)
                            }
                        }
                    },
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    color = if (isExpired) colors.statusError else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val latencyText = when {
                node.latencyMs == null -> "—"
                node.latencyMs < 0 -> "✕"
                else -> "${node.latencyMs}"
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
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = latencyColor
            )

            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = tr("Server actions"),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Ping"), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onPing() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (node.isPinned) "Unpin" else "Pin", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onTogglePin() }
                    )
                    if (!node.isLocked) {
                        DropdownMenuItem(
                            text = { Text(tr("Share QR"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = { menuOpen = false; onShareQr() }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Edit"), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(tr("Delete"), fontSize = 13.sp, color = colors.statusError) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(16.dp))
                        },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }

            Surface(
                modifier = Modifier.clickable(enabled = !isExpired, onClick = onConnect),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) colors.primary else colors.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = if (isSelected) "Active" else "Use",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) colors.onPrimary else colors.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (highlighted) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}
