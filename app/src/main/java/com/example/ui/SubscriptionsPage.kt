package com.example.ui

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.ManualConfigCounts
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.TrashEntry
import com.example.i18n.Digits
import com.example.ui.theme.LocalLuxyColors
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun SubscriptionsPage(
    subscriptions: List<SubscriptionEntity>,
    managementEnabled: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onHome: () -> Unit,
    onRefresh: (String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    standaloneServerCount: Int = 0,
    onImportClipboard: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onAddSubscriptionUrl: (url: String, name: String) -> Unit = { _, _ -> },
    manualConfigsEnabled: Boolean = true,
    manualProfiles: List<ServerProfileEntity> = emptyList(),
    manualCounts: ManualConfigCounts = ManualConfigCounts(total = standaloneServerCount),
    deletedBackups: List<DeletedBackupBatch> = emptyList(),
    lastDeletedBatch: DeletedBackupBatch? = null,
    isRetesting: Boolean = false,
    retestProgress: Pair<Int, Int>? = null,
    connectedSubscriptionId: String? = null,
    connectedIsManual: Boolean = false,
    sourceChangePendingReconnect: Boolean = false,
    onToggleSubscriptionSource: (String, Boolean) -> Unit = { _, _ -> },
    onToggleManualSource: (Boolean) -> Unit = {},
    onToggleProfileEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onBulkSetEnabled: (List<String>, Boolean) -> Unit = { _, _ -> },
    onBulkDeleteManual: (List<String>) -> Unit = {},
    onBulkRetestManual: (List<String>) -> Unit = {},
    onCancelRetest: () -> Unit = {},
    onRemoveDuplicates: () -> Unit = {},
    onRestoreBackup: (String) -> Unit = {},
    onClearLastDeletedBatch: () -> Unit = {},
    onReclassifyToManual: (List<String>) -> Unit = {},
    onSetManualCountry: (String, String?) -> Unit = { _, _ -> },
    onDetectCountries: (List<String>) -> Unit = {},
    smartCleanupPlan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
    trashEntries: List<TrashEntry> = emptyList(),
    activeNodeId: String? = null,
    eligibleCounts: Map<String, Int> = emptyMap(),
    onSetProfilePreferred: (String, Boolean) -> Unit = { _, _ -> },
    onMoveCleanupToTrash: (List<SmartCleanupItem>) -> Unit = {},
    onRestoreTrash: (List<String>) -> Unit = {},
    onPermanentlyDeleteTrash: (List<String>) -> Unit = {},
    onExportBackup: (passphrase: String) -> Unit = {},
    onRequestRestoreBackup: () -> Unit = {},
    onDismissCleanupSuggestions: (List<SmartCleanupItem>) -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    val locale = LocalConfiguration.current.locales[0]
    val language = locale.language
    val enabled = managementEnabled && !busy
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showAddUrlForm by rememberSaveable { mutableStateOf(false) }
    var showManualOverlay by rememberSaveable { mutableStateOf(false) }
    var showCleanupSheet by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    val subscriptionNames = remember(subscriptions) {
        buildMap(subscriptions.size) { subscriptions.forEach { put(it.id, it.name) } }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hasExpiry = subscriptions.any { it.expireAtEpochSec > 0 }
    LaunchedEffect(enabled) {
        if (!enabled) {
            showAddSheet = false
            showAddUrlForm = false
        }
    }
    LaunchedEffect(lifecycle, hasExpiry) {
        if (hasExpiry) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }

    if (showManualOverlay) {
        ManualConfigurationsOverlay(
            profiles = manualProfiles,
            counts = manualCounts,
            deletedBackups = deletedBackups,
            lastDeletedBatch = lastDeletedBatch,
            isRetesting = isRetesting,
            retestProgress = retestProgress,
            snackbar = snackbar,
            onClose = { showManualOverlay = false },
            onToggleProfileEnabled = onToggleProfileEnabled,
            onBulkSetEnabled = onBulkSetEnabled,
            onBulkDelete = onBulkDeleteManual,
            onBulkRetest = onBulkRetestManual,
            onCancelRetest = onCancelRetest,
            onRemoveDuplicates = onRemoveDuplicates,
            onRestoreBackup = onRestoreBackup,
            onClearLastDeletedBatch = onClearLastDeletedBatch,
            onReclassifyToManual = onReclassifyToManual,
            onSetManualCountry = onSetManualCountry,
            onDetectCountries = onDetectCountries,
            smartCleanupPlan = smartCleanupPlan,
            trashEntries = trashEntries,
            activeNodeId = activeNodeId,
            subscriptionNames = subscriptionNames,
            onSetProfilePreferred = onSetProfilePreferred,
            onMoveCleanupToTrash = onMoveCleanupToTrash,
            onRestoreTrash = onRestoreTrash,
            onPermanentlyDeleteTrash = onPermanentlyDeleteTrash,
            onDismissCleanupSuggestions = onDismissCleanupSuggestions
        )
        return
    }
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "header") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = onHome,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = stringResource(R.string.sub_home),
                                color = colors.primary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // The badge is a hint for pending suggestions only; Trash depth is announced
                        // through the accessibility state instead of being mistaken for a count of
                        // new suggestions. Trash stays reachable because the entry always opens the
                        // sheet, whatever the counts are.
                        val suggestionCount = smartCleanupPlan.totalSuggested.coerceAtLeast(0)
                        val trashCount = trashEntries.size
                        val cleanupTitle = stringResource(R.string.cleanup_title)
                        val localizedSuggestions = Digits.localize(suggestionCount.toString(), language)
                        val localizedTrash = Digits.localize(trashCount.toString(), language)
                        val cleanupState = when {
                            suggestionCount > 0 && trashCount > 0 -> stringResource(
                                R.string.cleanup_entry_state_suggestions_trash,
                                localizedSuggestions,
                                localizedTrash
                            )
                            suggestionCount > 0 -> stringResource(
                                R.string.cleanup_entry_state_suggestions,
                                localizedSuggestions
                            )
                            trashCount > 0 -> stringResource(
                                R.string.cleanup_entry_state_trash,
                                localizedTrash
                            )
                            else -> null
                        }
                        val headerFontScale = LocalDensity.current.fontScale
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.sub_title),
                                modifier = Modifier.weight(1f),
                                // The cleanup and add actions claim fixed width; at large font scales
                                // the title wraps instead of being ellipsized, so the screen name is
                                // never hidden and neither action is pushed offscreen.
                                style = if (headerFontScale > 1.25f) {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.headlineLarge
                                },
                                fontWeight = FontWeight.Bold,
                                color = colors.onBackground
                            )
                            Surface(
                                onClick = { if (!busy) showCleanupSheet = true },
                                enabled = !busy,
                                shape = RoundedCornerShape(999.dp),
                                color = colors.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (suggestionCount > 0) colors.primary.copy(alpha = 0.28f)
                                    else colors.outlineVariant
                                ),
                                modifier = Modifier
                                    .widthIn(min = 48.dp)
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription = cleanupTitle
                                        if (cleanupState != null) stateDescription = cleanupState
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    if (suggestionCount > 0) {
                                        Surface(shape = CircleShape, color = colors.accentSoft) {
                                            Text(
                                                text = localizedSuggestions,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.primary,
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Surface(
                                onClick = { if (enabled) showAddSheet = true },
                                enabled = enabled,
                                shape = RoundedCornerShape(13.dp),
                                color = if (enabled) colors.primary else colors.surfaceContainer,
                                border = if (enabled) null else BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = stringResource(R.string.sub_add_cd),
                                        tint = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.sub_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                if (sourceChangePendingReconnect) {
                    item(key = "source_reconnect_notice") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.sources_changes_next_connection),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (!managementEnabled) {
                    item(key = "locked") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.sub_locked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (busy) {
                    item(key = "busy") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colors.accentSoft,
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.primary
                                )
                                Text(
                                    text = stringResource(R.string.sub_busy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                if (showAddUrlForm) {
                    item(key = "add_url_form") {
                        AddSubscriptionUrlForm(
                            enabled = enabled,
                            onDismiss = { showAddUrlForm = false },
                            onAdd = { url, name ->
                                if (enabled) {
                                    onAddSubscriptionUrl(url, name)
                                    showAddUrlForm = false
                                }
                            }
                        )
                    }
                }


                if (subscriptions.isEmpty()) {
                    item(key = "empty") {
                        EmptySubscriptionsCard(
                            enabled = enabled,
                            standaloneServerCount = standaloneServerCount,
                            language = language,
                            onAddClick = { if (enabled) showAddSheet = true }
                        )
                    }
                }

                items(subscriptions, key = { it.id }) { subscription ->
                    if (editingId == subscription.id) {
                        EditSubscriptionForm(
                            subscription = subscription,
                            enabled = enabled,
                            onDismiss = { editingId = null },
                            onSave = { name, url ->
                                if (enabled) {
                                    onSave(subscription.id, name, url)
                                    editingId = null
                                }
                            }
                        )
                    } else {
                        SubscriptionCard(
                            subscription = subscription,
                            now = now,
                            enabled = enabled,
                            switchEnabled = !busy,
                            isConnected = connectedSubscriptionId == subscription.id,
                            eligibleCount = eligibleCounts[subscription.id],
                            onToggleConnection = { checked ->
                                onToggleSubscriptionSource(subscription.id, checked)
                            },
                            onRefresh = { if (enabled) onRefresh(subscription.id) },
                            onEdit = { if (enabled) editingId = subscription.id },
                            onDelete = { if (enabled) deletingId = subscription.id }
                        )
                    }
                }

                item(key = "manual_configurations_card") {
                    val manualCount = if (manualProfiles.isNotEmpty()) manualCounts.total else standaloneServerCount
                    ManualConfigurationsCard(
                        configCount = manualCount,
                        unclassifiedCount = manualCounts.unclassified,
                        included = manualConfigsEnabled,
                        isConnected = connectedIsManual,
                        switchEnabled = !busy,
                        addEnabled = enabled,
                        language = language,
                        onOpen = { showManualOverlay = true },
                        onAdd = { if (enabled) showAddSheet = true },
                        onToggleConnection = onToggleManualSource
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddConfigurationSheet(
            enabled = enabled,
            onDismiss = { showAddSheet = false },
            onImportClipboard = {
                showAddSheet = false
                if (enabled) onImportClipboard()
            },
            onImportFile = {
                showAddSheet = false
                if (enabled) onImportFile()
            },
            onAddSubscriptionUrl = {
                showAddSheet = false
                if (enabled) showAddUrlForm = true
            },
            onExportBackup = {
                showAddSheet = false
                if (enabled) showExportDialog = true
            },
            onRestoreBackup = {
                showAddSheet = false
                if (enabled) onRequestRestoreBackup()
            }
        )
    }

    if (showExportDialog) {
        ExportBackupDialog(
            onConfirm = { passphrase ->
                showExportDialog = false
                onExportBackup(passphrase)
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showCleanupSheet) {
        SmartCleanupSheet(
            plan = smartCleanupPlan,
            trashEntries = trashEntries,
            activeNodeId = activeNodeId,
            subscriptionNames = subscriptionNames,
            language = language,
            locale = locale,
            onMoveToTrash = { items ->
                onMoveCleanupToTrash(items)
            },
            onRestoreTrash = onRestoreTrash,
            onPermanentlyDeleteTrash = onPermanentlyDeleteTrash,
            onDismiss = { showCleanupSheet = false },
            onDismissSuggestions = { items ->
                onDismissCleanupSuggestions(items)
                if (items.size >= smartCleanupPlan.totalSuggested) {
                    showCleanupSheet = false
                }
            }
        )
    }

    subscriptions.firstOrNull { it.id == deletingId }?.let { subscription ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.sub_delete_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.sub_delete_message, subscription.name))
                    if (!managementEnabled) {
                        Text(stringResource(R.string.sub_locked), color = colors.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        if (enabled) {
                            onDelete(subscription.id)
                            deletingId = null
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.sub_delete),
                        color = if (enabled) colors.error else colors.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun EmptySubscriptionsCard(
    enabled: Boolean,
    standaloneServerCount: Int,
    language: String,
    onAddClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(colors.accentSoft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = stringResource(R.string.sub_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = stringResource(R.string.sub_empty),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (standaloneServerCount > 0) {
                Text(
                    text = stringResource(
                        R.string.sub_empty_standalone,
                        Digits.localize(standaloneServerCount.toString(), language)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary
                )
            }
            Button(
                onClick = onAddClick,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.surfaceContainer,
                    disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.sub_add_sheet_title),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private enum class SubscriptionStatus {
    ACTIVE,
    EXPIRED,
    EXHAUSTED,
    FAILED,
    NO_SERVERS,
    NOT_UPDATED
}

private fun resolveSubscriptionStatus(
    subscription: SubscriptionEntity,
    nowEpochSec: Long
): SubscriptionStatus {
    val used = subscription.uploadBytes.coerceAtLeast(0L) + subscription.downloadBytes.coerceAtLeast(0L)
    val expired = subscription.expireAtEpochSec > 0L && subscription.expireAtEpochSec <= nowEpochSec
    val exhausted = subscription.totalBytes > 0L && used >= subscription.totalBytes
    val failed = !subscription.lastError.isNullOrBlank() ||
        subscription.lastStatus.equals("Failed", ignoreCase = true)
    val hasSuccessfulSnapshot = subscription.lastUpdatedAt != null ||
        subscription.lastStatus.equals("Updated", ignoreCase = true)
    return when {
        expired -> SubscriptionStatus.EXPIRED
        exhausted -> SubscriptionStatus.EXHAUSTED
        failed -> SubscriptionStatus.FAILED
        subscription.nodeCount <= 0 -> SubscriptionStatus.NO_SERVERS
        !hasSuccessfulSnapshot -> SubscriptionStatus.NOT_UPDATED
        else -> SubscriptionStatus.ACTIVE
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    now: Long,
    enabled: Boolean,
    switchEnabled: Boolean,
    isConnected: Boolean,
    eligibleCount: Int? = null,
    onToggleConnection: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val colors = LocalLuxyColors.current
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    fun isolated(value: String) = BidiFormatter.getInstance(locale).unicodeWrap(value, TextDirectionHeuristics.LTR)
    // Zero is the database sentinel for missing metadata, not an unlimited or zero-use plan.
    val used = subscription.uploadBytes.coerceAtLeast(0).toDouble() +
        subscription.downloadBytes.coerceAtLeast(0).toDouble()
    val total = subscription.totalBytes.takeIf { it > 0 }?.toDouble()
    val usageKnown = used > 0
    val remaining = if (usageKnown && total != null) (total - used).coerceAtLeast(0.0) else null
    val expiry = subscription.expireAtEpochSec.takeIf { it > 0 }
    val nowEpochSec = now / 1000L
    val status = resolveSubscriptionStatus(subscription, nowEpochSec)
    val usableServers = eligibleCount ?: subscription.nodeCount
    val compactVerticalActions = LocalDensity.current.fontScale > 1.25f
    var expanded by rememberSaveable(subscription.id) { mutableStateOf(false) }
    val useForConnectionLabel = stringResource(R.string.sources_use_for_connection)
    val allUsageMetrics = listOf(
        SubscriptionUsageMetric(
            label = stringResource(R.string.sub_used_label),
            value = byteSize(used, numbers, locale),
            reported = usageKnown
        ),
        SubscriptionUsageMetric(
            label = stringResource(R.string.sub_remaining_label),
            value = remaining?.let { byteSize(it, numbers, locale) } ?: "",
            reported = remaining != null
        ),
        SubscriptionUsageMetric(
            label = stringResource(R.string.sub_allowance_label),
            value = total?.let { byteSize(it, numbers, locale) } ?: "",
            reported = total != null
        ),
        SubscriptionUsageMetric(
            label = stringResource(R.string.sub_expires_label),
            value = expiry?.let {
                isolated(dates.format(Date(it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)))
            } ?: "",
            reported = expiry != null,
            secondary = expiry?.takeIf { it > nowEpochSec }?.let {
                timeLeft(it, nowEpochSec, numbers, locale)
            }
        )
    )
    val reportedMetrics = allUsageMetrics.filter { it.reported }
    val missingMetrics = allUsageMetrics.filterNot { it.reported }

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = colors.accentSoft,
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (eligibleCount != null && eligibleCount != subscription.nodeCount) {
                            stringResource(
                                R.string.sub_servers_eligible,
                                isolated(numbers.format(subscription.nodeCount)),
                                isolated(numbers.format(usableServers))
                            )
                        } else {
                            stringResource(
                                R.string.sub_servers,
                                isolated(numbers.format(subscription.nodeCount))
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (status != SubscriptionStatus.ACTIVE) {
                        SubscriptionStatusPill(status = status)
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isConnected) {
                        EligibilityPill(
                            labelRes = R.string.sources_connected,
                            tint = colors.statusConnected
                        )
                    }
                    Switch(
                        checked = subscription.isEnabledForConnection,
                        onCheckedChange = onToggleConnection,
                        enabled = switchEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "${subscription.name}: $useForConnectionLabel"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.onSurfaceVariant,
                            uncheckedTrackColor = colors.surfaceContainer
                        )
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            val detailsContentDescription = stringResource(
                if (expanded) R.string.sub_collapse_details_cd
                else R.string.sub_expand_details_cd
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = detailsContentDescription }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        usageKnown -> {
                            Text(
                                text = stringResource(R.string.sub_used_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = byteSize(used, numbers, locale),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        expiry != null -> {
                            Text(
                                text = stringResource(R.string.sub_expires_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = isolated(
                                    dates.format(Date(expiry.coerceAtMost(Long.MAX_VALUE / 1000) * 1000))
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> Text(
                            text = stringResource(R.string.sub_quota_not_reported),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (expanded) {
                val collapsedMetricLabel = when {
                    usageKnown -> stringResource(R.string.sub_used_label)
                    expiry != null -> stringResource(R.string.sub_expires_label)
                    else -> null
                }
                val expandedMetrics = if (collapsedMetricLabel != null) {
                    reportedMetrics.filterNot { it.label == collapsedMetricLabel }
                } else {
                    reportedMetrics
                }
                if (expandedMetrics.isNotEmpty() || (reportedMetrics.isNotEmpty() && missingMetrics.isNotEmpty()) || (usageKnown && total != null)) {
                    HorizontalDivider(color = colors.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        expandedMetrics.chunked(2).forEach { rowMetrics ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                UsageMetricCell(
                                    label = rowMetrics[0].label,
                                    value = rowMetrics[0].value,
                                    reported = true,
                                    secondary = rowMetrics[0].secondary,
                                    modifier = if (rowMetrics.size > 1) {
                                        Modifier.weight(1f).padding(end = 12.dp)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }
                                )
                                if (rowMetrics.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(colors.outlineVariant)
                                    )
                                    UsageMetricCell(
                                        label = rowMetrics[1].label,
                                        value = rowMetrics[1].value,
                                        reported = true,
                                        secondary = rowMetrics[1].secondary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                        if (reportedMetrics.isNotEmpty() && missingMetrics.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.sub_not_reported_group,
                                    missingMetrics.joinToString(", ") { it.label }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        if (usageKnown && total != null) {
                            LinearProgressIndicator(
                                progress = { (used / total).coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (used >= total) colors.error else colors.primary,
                                trackColor = colors.background
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.sub_updated,
                                subscription.lastUpdatedAt?.let { isolated(dates.format(Date(it))) }
                                    ?: stringResource(R.string.sub_never)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    if (!subscription.lastError.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.sub_failed),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider(color = colors.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardActionButton(
                        icon = Icons.Rounded.Refresh,
                        label = stringResource(R.string.sub_refresh),
                        enabled = enabled,
                        stacked = compactVerticalActions,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(colors.outlineVariant)
                    )
                    CardActionButton(
                        icon = Icons.Outlined.Edit,
                        label = stringResource(R.string.sub_edit),
                        enabled = enabled,
                        stacked = compactVerticalActions,
                        onClick = onEdit,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(colors.outlineVariant)
                    )
                    CardActionButton(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.sub_delete),
                        enabled = enabled,
                        stacked = compactVerticalActions,
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionStatusPill(status: SubscriptionStatus) {
    val colors = LocalLuxyColors.current
    val (labelRes, tint, bg, border) = when (status) {
        SubscriptionStatus.ACTIVE -> Quad(
            R.string.sub_status_active,
            colors.statusConnected,
            colors.statusConnected.copy(alpha = 0.14f),
            colors.statusConnected.copy(alpha = 0.28f)
        )
        SubscriptionStatus.EXPIRED -> Quad(
            R.string.sub_status_expired,
            colors.error,
            colors.error.copy(alpha = 0.14f),
            colors.error.copy(alpha = 0.28f)
        )
        SubscriptionStatus.EXHAUSTED -> Quad(
            R.string.sub_status_exhausted,
            colors.error,
            colors.error.copy(alpha = 0.14f),
            colors.error.copy(alpha = 0.28f)
        )
        SubscriptionStatus.FAILED -> Quad(
            R.string.sub_status_failed,
            colors.error,
            colors.error.copy(alpha = 0.14f),
            colors.error.copy(alpha = 0.28f)
        )
        SubscriptionStatus.NO_SERVERS -> Quad(
            R.string.sub_status_no_servers,
            colors.onSurfaceVariant,
            colors.surfaceContainer,
            colors.outlineVariant
        )
        SubscriptionStatus.NOT_UPDATED -> Quad(
            R.string.sub_status_not_updated,
            colors.onSurfaceVariant,
            colors.surfaceContainer,
            colors.outlineVariant
        )
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(tint, CircleShape)
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EligibilityPill(labelRes: Int, tint: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.30f))
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}


@Composable
private fun EmptyManualConfigurationsCard(
    enabled: Boolean,
    onAdd: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val addLabel = stringResource(R.string.manual_empty_add_action)
    val actionColor = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = enabled,
                onClickLabel = addLabel,
                role = Role.Button,
                onClick = onAdd
            )
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.manual_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = stringResource(R.string.manual_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.manual_empty_action_short),
                color = actionColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ManualConfigurationsCard(
    configCount: Int,
    unclassifiedCount: Int,
    included: Boolean,
    isConnected: Boolean,
    switchEnabled: Boolean,
    addEnabled: Boolean,
    language: String,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onToggleConnection: (Boolean) -> Unit
) {
    // With no saved configurations there is nothing to include or open: the card only explains
    // what manual configurations are and offers the single action that can create one. No switch
    // is rendered, so the connection-source preference can never be changed from an empty state.
    if (configCount <= 0) {
        EmptyManualConfigurationsCard(enabled = addEnabled, onAdd = onAdd)
        return
    }
    val colors = LocalLuxyColors.current
    val manualTitle = stringResource(R.string.manual_title)
    val useForConnectionLabel = stringResource(R.string.sources_use_for_connection)
    val openDescription = stringResource(R.string.manual_open_cd)
    val localizedCount = Digits.localize(configCount.toString(), language)
    val countText = if (configCount == 1) {
        stringResource(R.string.manual_configs_count_one, localizedCount)
    } else {
        stringResource(R.string.manual_configs_count_many, localizedCount)
    }
    val summaryText = if (unclassifiedCount > 0) {
        "$countText · ${Digits.localize(unclassifiedCount.toString(), language)} ${stringResource(R.string.manual_filter_unclassified)}"
    } else {
        countText
    }
    Surface(
        onClick = onOpen,
        color = colors.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = colors.accentSoft,
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = manualTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isConnected) {
                    EligibilityPill(
                        labelRes = R.string.sources_connected,
                        tint = colors.statusConnected
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Switch(
                    checked = included,
                    onCheckedChange = onToggleConnection,
                    enabled = switchEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = "$manualTitle: $useForConnectionLabel"
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.onSurfaceVariant,
                        uncheckedTrackColor = colors.surfaceContainer
                    )
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = openDescription,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class SubscriptionUsageMetric(
    val label: String,
    val value: String,
    val reported: Boolean,
    val secondary: String? = null,
)
@Composable
private fun UsageMetricCell(
    label: String,
    value: String,
    reported: Boolean,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    val colors = LocalLuxyColors.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (reported) FontWeight.SemiBold else FontWeight.Normal,
            color = if (reported) colors.onSurface else colors.onSurfaceVariant
        )
        if (secondary != null) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CardActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    stacked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    val iconTint = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f)
    val textColor = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConfigurationSheet(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onAddSubscriptionUrl: () -> Unit,
    onExportBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.outline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_add_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            AddConfigurationOptionRow(
                icon = Icons.Outlined.ContentPaste,
                title = stringResource(R.string.sub_add_clipboard_title),
                description = stringResource(R.string.sub_add_clipboard_desc),
                enabled = enabled,
                onClick = onImportClipboard
            )
            AddConfigurationOptionRow(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(R.string.sub_add_file_title),
                description = stringResource(R.string.sub_add_file_desc),
                enabled = enabled,
                onClick = onImportFile
            )
            AddConfigurationOptionRow(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.sub_add_url_title),
                description = stringResource(R.string.sub_add_url_desc),
                enabled = enabled,
                onClick = onAddSubscriptionUrl
            )
            HorizontalDivider(color = colors.outlineVariant)
            AddConfigurationOptionRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.backup_export_title),
                description = stringResource(R.string.backup_export_desc),
                enabled = enabled,
                onClick = onExportBackup
            )
            AddConfigurationOptionRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.backup_restore_title),
                description = stringResource(R.string.backup_restore_desc),
                enabled = enabled,
                onClick = onRestoreBackup
            )
        }
    }
}

@Composable
private fun AddConfigurationOptionRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.accentSoft,
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.20f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AddSubscriptionUrlForm(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String) -> Unit
) {
    val colors = LocalLuxyColors.current
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val valid = validSubscriptionUrl(url.trim())
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        cursorColor = colors.primary
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_add_url_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
            val urlLabel = stringResource(R.string.sub_url)
            val urlHint = stringResource(R.string.sub_add_url_hint)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = enabled,
                    label = { Text(urlLabel) },
                    placeholder = { Text(urlHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    isError = attempted && !valid
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = enabled,
                label = { Text(stringResource(R.string.sub_name_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
            if (attempted && !valid) {
                Text(
                    text = stringResource(R.string.sub_url_invalid),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.sub_locked),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
                TextButton(
                    enabled = enabled,
                    onClick = {
                        attempted = true
                        if (valid && enabled) onAdd(url.trim(), name.trim())
                    }
                ) {
                    Text(
                        text = stringResource(R.string.sub_add_action),
                        color = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun byteSize(bytes: Double, numbers: NumberFormat, locale: Locale): String {
    var amount = bytes
    var unit = 0
    while (amount >= 1024 && unit < 4) {
        amount /= 1024
        unit++
    }
    val resource = when (unit) {
        0 -> R.string.sub_bytes
        1 -> R.string.sub_kib
        2 -> R.string.sub_mib
        3 -> R.string.sub_gib
        else -> R.string.sub_tib
    }
    return stringResource(
        resource,
        BidiFormatter.getInstance(locale).unicodeWrap(numbers.format(amount), TextDirectionHeuristics.LTR)
    )
}

@Composable
private fun timeLeft(expiry: Long, now: Long, numbers: NumberFormat, locale: Locale): String {
    val seconds = expiry - now
    val (resource, count) = when {
        seconds <= 0 -> return stringResource(R.string.sub_expired)
        seconds >= 86_400 -> R.string.sub_days to seconds / 86_400
        seconds >= 3_600 -> R.string.sub_hours to seconds / 3_600
        seconds >= 60 -> R.string.sub_minutes to seconds / 60
        else -> return stringResource(R.string.sub_under_minute)
    }
    return stringResource(
        resource,
        BidiFormatter.getInstance(locale).unicodeWrap(numbers.format(count), TextDirectionHeuristics.LTR)
    )
}

@Composable
private fun EditSubscriptionForm(
    subscription: SubscriptionEntity,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    val colors = LocalLuxyColors.current
    var name by rememberSaveable(subscription.id) { mutableStateOf(subscription.name) }
    var url by rememberSaveable(subscription.id) { mutableStateOf(subscription.url) }
    var attempted by rememberSaveable(subscription.id) { mutableStateOf(false) }
    val valid = name.isNotBlank() && validSubscriptionUrl(url.trim())
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        cursorColor = colors.primary
    )
    // Keep the editor bounded and scrollable when the keyboard or large text reduces space.
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = enabled,
                label = { Text(stringResource(R.string.sub_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                isError = attempted && name.isBlank()
            )
            val urlLabel = stringResource(R.string.sub_url)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = enabled,
                    label = { Text(urlLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    isError = attempted && !validSubscriptionUrl(url.trim())
                )
            }
            if (attempted && !valid) {
                Text(
                    text = stringResource(R.string.sub_invalid),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.sub_locked),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
                TextButton(
                    enabled = enabled,
                    onClick = {
                        attempted = true
                        if (valid && enabled) onSave(name.trim(), url.trim())
                    }
                ) {
                    Text(
                        text = stringResource(R.string.sub_save),
                        color = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun validSubscriptionUrl(value: String): Boolean {
    val trimmed = value.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }
    return trimmed.toHttpUrlOrNull() != null
}
