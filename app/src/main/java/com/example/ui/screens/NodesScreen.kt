package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.EncryptedConfigVault
import com.example.data.local.ServerProfileEntity
import com.example.data.model.NodeSortOrder
import com.example.data.model.ProxyProtocol
import com.example.ui.LuxyVpnUiState
import com.example.ui.components.Formatters

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodesScreen(
    uiState: LuxyVpnUiState,
    onSearchChange: (String) -> Unit,
    onGroupSelect: (String) -> Unit,
    onSortChange: (NodeSortOrder) -> Unit,
    onSelectNode: (ServerProfileEntity) -> Unit,
    onProbeNode: (ServerProfileEntity) -> Unit,
    onProbeAll: () -> Unit,
    onSelectFastest: () -> Unit,
    onTogglePin: (ServerProfileEntity) -> Unit,
    onEditNode: (ServerProfileEntity) -> Unit,
    onShareNode: (ServerProfileEntity) -> Unit,
    onDeleteNode: (ServerProfileEntity) -> Unit,
    onImportClipboard: () -> Unit,
    onOpenPasteUri: () -> Unit,
    onOpenManualAdd: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenVaultImport: () -> Unit = {},
    onOpenVaultExport: (ServerProfileEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    val groups = remember(uiState.servers) {
        listOf("All") + uiState.servers.map { it.groupName.ifBlank { "Local" } }.distinct().sorted()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
        ) {
            // Header & Quick Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Proxy Nodes",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${uiState.servers.size} configured • ${uiState.subscriptions.size} subscription(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenSubscriptions,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("nodes_subscriptions_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Manage subscriptions",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("nodes_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sort,
                                contentDescription = "Sort nodes",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            NodeSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (order == uiState.sortOrder) "✓ ${order.label}" else order.label
                                        )
                                    },
                                    onClick = {
                                        onSortChange(order)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Search Field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search node name, host, or protocol…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("nodes_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Wrapping Group Filter Chips
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    FilterChip(
                        selected = uiState.selectedGroup == group,
                        onClick = { onGroupSelect(group) },
                        label = { Text(group, maxLines = 1) }
                    )
                }
            }

            // Test All & Fastest Strip
            if (uiState.servers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onProbeAll,
                        enabled = !uiState.batchProbe.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_all_nodes_button")
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.batchProbe.isRunning) "Testing…" else "Test All",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onSelectFastest,
                        enabled = !uiState.batchProbe.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("select_fastest_node_button")
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select Fastest", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (uiState.batchProbe.isRunning && uiState.batchProbe.total > 0) {
                    LinearProgressIndicator(
                        progress = { uiState.batchProbe.completed.toFloat() / uiState.batchProbe.total.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // Node List
            if (uiState.filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (uiState.servers.isEmpty()) "No Proxy Nodes Yet" else "No Matching Nodes",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (uiState.servers.isEmpty())
                                "Tap + to import from clipboard, paste a config URI, add a subscription URL, or configure a server manually."
                            else "Try clearing your search or switching group filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.filteredServers, key = { it.id }) { node ->
                        val isSelected = uiState.selectedNode?.id == node.id
                        ServerNodeCard(
                            node = node,
                            isSelected = isSelected,
                            onSelect = { onSelectNode(node) },
                            onProbe = { onProbeNode(node) },
                            onTogglePin = { onTogglePin(node) },
                            onEdit = { onEditNode(node) },
                            onShare = { onShareNode(node) },
                            onExportVault = { onOpenVaultExport(node) },
                            onDelete = { onDeleteNode(node) }
                        )
                    }
                }
            }
        }

        // Add Node FAB + Menu
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            FloatingActionButton(
                onClick = { showAddMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_node_fab")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add proxy node or subscription",
                    modifier = Modifier.size(24.dp)
                )
            }

            DropdownMenu(
                expanded = showAddMenu,
                onDismissRequest = { showAddMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Import from Clipboard") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showAddMenu = false
                        onImportClipboard()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Paste URI / Config") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showAddMenu = false
                        onOpenPasteUri()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add Server Manually") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showAddMenu = false
                        onOpenManualAdd()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Manage Subscriptions") },
                    leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showAddMenu = false
                        onOpenSubscriptions()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Import Encrypted Vault (.zsx)") },
                    leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showAddMenu = false
                        onOpenVaultImport()
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerNodeCard(
    node: ServerProfileEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onProbe: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onExportVault: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val protocol = ProxyProtocol.fromString(node.protocol)
    val protoColor = Formatters.protocolColor(protocol)
    val rttColor = Formatters.latencyColor(node.latencyMs)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .testTag("node_card_${node.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else protoColor.copy(alpha = 0.45f))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = protoColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = protocol.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = protoColor,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (node.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (node.isLocked) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Locked Encrypted Vault Profile",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = EncryptedConfigVault.redactEndpointLabel(node),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = rttColor.copy(alpha = 0.14f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onProbe)
            ) {
                Text(
                    text = Formatters.latencyLabel(node.latencyMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = rttColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Node actions",
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Test Latency") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onProbe() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (node.isPinned) "Unpin Node" else "Pin to Top") },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (node.isLocked) "Vault Lock Info" else "Share URI / QR Code") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (node.isLocked) Icons.Rounded.Lock else Icons.Rounded.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = { showMenu = false; onShare() }
                    )
                    if (!node.isLocked) {
                        DropdownMenuItem(
                            text = { Text("Export Encrypted Vault (.zsx)") },
                            leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onExportVault() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Configuration") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onEdit() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}
