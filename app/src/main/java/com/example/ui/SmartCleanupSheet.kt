package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import com.example.R
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SourceType
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.TrashEntry
import com.example.i18n.Digits
import com.example.ui.theme.LocalLuxyColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val TAB_SUGGESTIONS = 0
private const val TAB_TRASH = 1

/**
 * Smart cleanup review sheet. Suggestions are transactional up to the moment they move into
 * Trash; permanent deletion always requires an explicit confirmation dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCleanupSheet(
    plan: SmartCleanupPlan,
    trashEntries: List<TrashEntry>,
    activeNodeId: String?,
    language: String,
    locale: Locale,
    onMoveToTrash: (List<SmartCleanupItem>) -> Unit,
    onRestoreTrash: (List<String>) -> Unit,
    onPermanentlyDeleteTrash: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onDismissSuggestions: (List<SmartCleanupItem>) -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val maxContentHeight = (configuration.screenHeightDp * 0.62f).dp.coerceIn(260.dp, 460.dp)
    var selectedTab by remember { mutableIntStateOf(TAB_SUGGESTIONS) }
    var selectedItemIds by remember { mutableStateOf(defaultSelection(plan)) }
    var selectedTrashIds by remember { mutableStateOf(emptySet<String>()) }
    var expandedCategories by remember { mutableStateOf(emptySet<String>()) }
    var selectedTrashDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermanentDeleteIds by remember { mutableStateOf<List<String>?>(null) }

    androidx.compose.runtime.LaunchedEffect(plan) {
        val validIds = plan.groups.asSequence().flatMap { it.items.asSequence() }.map { it.profile.id }.toSet()
        selectedItemIds = selectedItemIds.filterTo(HashSet()) { it in validIds }
    }
    androidx.compose.runtime.LaunchedEffect(trashEntries) {
        val validTrashIds = trashEntries.mapTo(HashSet()) { it.id }
        selectedTrashIds = selectedTrashIds.filterTo(HashSet()) { it in validTrashIds }
    }

    val selectedItems = remember(plan, selectedItemIds) {
        plan.groups.asSequence()
            .flatMap { it.items.asSequence() }
            .distinctBy { it.profile.id }
            .filter { it.profile.id in selectedItemIds }
            .toList()
    }
    val selectedTrash = remember(trashEntries, selectedTrashIds) {
        trashEntries.filter { it.id in selectedTrashIds }
    }

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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .testTag("smart_cleanup_sheet_content"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.cleanup_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanupTabPill(
                    label = stringResource(R.string.cleanup_tab_suggestions),
                    selected = selectedTab == TAB_SUGGESTIONS,
                    countText = if (plan.totalSuggested > 0) {
                        Digits.localize(plan.totalSuggested.toString(), language)
                    } else {
                        null
                    },
                    onClick = { selectedTab = TAB_SUGGESTIONS }
                )
                CleanupTabPill(
                    label = stringResource(
                        R.string.cleanup_tab_trash,
                        Digits.localize(trashEntries.size.toString(), language)
                    ),
                    selected = selectedTab == TAB_TRASH,
                    onClick = { selectedTab = TAB_TRASH }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
            ) {
                if (selectedTab == TAB_SUGGESTIONS) {
                    SuggestionsTabContent(
                        plan = plan,
                        activeNodeId = activeNodeId,
                        language = language,
                        locale = locale,
                        selectedItemIds = selectedItemIds,
                        expandedCategories = expandedCategories,
                        onToggleGroup = { group ->
                            val ids = group.items.map { it.profile.id }.toSet()
                            selectedItemIds = if (ids.isNotEmpty() && ids.all { it in selectedItemIds }) {
                                selectedItemIds - ids
                            } else {
                                selectedItemIds + ids
                            }
                        },
                        onToggleItem = { item ->
                            val id = item.profile.id
                            selectedItemIds = if (id in selectedItemIds) {
                                selectedItemIds - id
                            } else {
                                selectedItemIds + id
                            }
                        },
                        onTogglePreview = { category ->
                            val key = category.name
                            expandedCategories = if (key in expandedCategories) {
                                expandedCategories - key
                            } else {
                                expandedCategories + key
                            }
                        }
                    )
                } else {
                    TrashTabContent(
                        entries = trashEntries,
                        locale = locale,
                        language = language,
                        selectedTrashIds = selectedTrashIds,
                        onToggleEntry = { entry ->
                            selectedTrashIds = if (entry.id in selectedTrashIds) {
                                selectedTrashIds - entry.id
                            } else {
                                selectedTrashIds + entry.id
                            }
                        },
                        onSelectAll = {
                            selectedTrashIds = trashEntries.mapTo(HashSet(trashEntries.size)) { it.id }
                        },
                        onClearSelection = { selectedTrashIds = emptySet() },
                        onOpenDetails = { entry -> selectedTrashDetailId = entry.id }
                    )
                }
            }

            if (selectedTab == TAB_SUGGESTIONS && plan.groups.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onDismissSuggestions(selectedItems)
                            selectedItemIds = emptySet()
                        },
                        enabled = selectedItems.isNotEmpty()
                    ) {
                        Text(
                            text = stringResource(R.string.cleanup_keep),
                            color = if (selectedItems.isNotEmpty()) {
                                colors.onSurface
                            } else {
                                colors.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            onMoveToTrash(selectedItems)
                            selectedItemIds = emptySet()
                        },
                        enabled = selectedItems.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary,
                            disabledContainerColor = colors.surfaceContainer,
                            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cleanup_move_to_trash,
                                Digits.localize(selectedItems.size.toString(), language)
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else if (selectedTab == TAB_TRASH && trashEntries.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onRestoreTrash(selectedTrash.map { it.id })
                            selectedTrashIds = emptySet()
                        },
                        enabled = selectedTrash.isNotEmpty()
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cleanup_restore_selected,
                                Digits.localize(selectedTrash.size.toString(), language)
                            ),
                            color = if (selectedTrash.isNotEmpty()) colors.primary else colors.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { pendingPermanentDeleteIds = selectedTrash.map { it.id } },
                        enabled = selectedTrash.isNotEmpty()
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cleanup_delete_permanently,
                                Digits.localize(selectedTrash.size.toString(), language)
                            ),
                            color = if (selectedTrash.isNotEmpty()) colors.error else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    pendingPermanentDeleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingPermanentDeleteIds = null },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.cleanup_perm_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cleanup_perm_delete_message,
                        Digits.localize(ids.size.toString(), language)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPermanentDeleteIds = null
                    selectedTrashIds = selectedTrashIds - ids.toSet()
                    onPermanentlyDeleteTrash(ids)
                }) {
                    Text(stringResource(R.string.cleanup_perm_delete_confirm), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermanentDeleteIds = null }) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
            }
        )
    }

    trashEntries.firstOrNull { it.id == selectedTrashDetailId }?.let { entry ->
        val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
        val fullName = entry.profileName.ifBlank { entry.label.ifBlank { entry.profileId } }
        val category = trashCategoryTitle(entry.category)
        val source = trashSourceTitle(entry.sourceType)
        AlertDialog(
            onDismissRequest = { selectedTrashDetailId = null },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.cleanup_trash_detail_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(fullName, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Text("Source: $source")
                    Text("Category: $category")
                    Text("Reason: ${entry.reason.ifBlank { "—" }}")
                    Text("Deleted: ${dates.format(Date(entry.deletedAt))}")
                    Text("${stringResource(R.string.sub_expires_label)}: ${dates.format(Date(entry.expiresAt))}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedTrashDetailId = null
                    selectedTrashIds = selectedTrashIds - entry.id
                    onRestoreTrash(listOf(entry.id))
                }) {
                    Text(
                        stringResource(R.string.cleanup_restore_selected, Digits.localize("1", language)),
                        color = colors.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedTrashDetailId = null
                    pendingPermanentDeleteIds = listOf(entry.id)
                }) {
                    Text(
                        stringResource(R.string.cleanup_delete_permanently, Digits.localize("1", language)),
                        color = colors.error
                    )
                }
            }
        )
    }
}

private fun defaultSelection(plan: SmartCleanupPlan): Set<String> =
    plan.groups
        .filter { it.defaultSelected }
        .flatMap { group -> group.items.map { it.profile.id } }
        .toSet()

@Composable
private fun CleanupTabPill(
    label: String,
    selected: Boolean,
    countText: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colors.accentSoft else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary.copy(alpha = 0.55f) else colors.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurfaceVariant
            )
            if (countText != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) colors.primary.copy(alpha = 0.16f) else colors.accentSoft
                ) {
                    Text(
                        text = countText,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsTabContent(
    plan: SmartCleanupPlan,
    activeNodeId: String?,
    language: String,
    locale: Locale,
    selectedItemIds: Set<String>,
    expandedCategories: Set<String>,
    onToggleGroup: (SmartCleanupGroup) -> Unit,
    onToggleItem: (SmartCleanupItem) -> Unit,
    onTogglePreview: (SmartCleanupCategory) -> Unit
) {
    val colors = LocalLuxyColors.current
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_cleanup_suggestions_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item(key = "suggestions_subtitle", contentType = "suggestions_header") {
            Text(
                text = stringResource(R.string.cleanup_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (plan.groups.isEmpty()) {
            item(key = "suggestions_empty", contentType = "suggestions_empty") {
                Text(
                    text = stringResource(R.string.cleanup_no_suggestions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            items(
                items = plan.groups,
                key = { "suggestion_group_${it.category.name}" },
                contentType = { "suggestion_group" }
            ) { group ->
                val ids = group.items.map { it.profile.id }.toSet()
                val selectedCount = ids.count { it in selectedItemIds }
                val toggleState = when {
                    ids.isEmpty() || selectedCount == 0 -> ToggleableState.Off
                    selectedCount == ids.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val previewExpanded = group.category.name in expandedCategories
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.background,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TriStateCheckbox(
                                state = toggleState,
                                onClick = { onToggleGroup(group) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.primary,
                                    uncheckedColor = colors.outline,
                                    checkmarkColor = colors.onPrimary
                                ),
                                modifier = Modifier.size(28.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = cleanupCategoryTitle(group.category),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = colors.accentSoft
                                    ) {
                                        Text(
                                            text = Digits.localize(group.items.size.toString(), language),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.primary
                                        )
                                    }
                                }
                                Text(
                                    text = cleanupCategoryDescription(group.category),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .clickable(role = Role.Button) { onTogglePreview(group.category) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (previewExpanded) {
                                                R.string.cleanup_hide_preview
                                            } else {
                                                R.string.cleanup_preview
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = if (previewExpanded) {
                                            Icons.Rounded.KeyboardArrowDown
                                        } else {
                                            Icons.AutoMirrored.Rounded.KeyboardArrowRight
                                        },
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (previewExpanded) {
                            HorizontalDivider(color = colors.outlineVariant)
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    items = group.items,
                                    key = { it.profile.id },
                                    contentType = { "suggestion_preview" }
                                ) { item ->
                                    CleanupItemRow(
                                        item = item,
                                        checked = item.profile.id in selectedItemIds,
                                        isActiveNode = item.profile.id == activeNodeId,
                                        locale = locale,
                                        onToggle = { onToggleItem(item) }
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

@Composable
private fun CleanupItemRow(
    item: SmartCleanupItem,
    checked: Boolean,
    isActiveNode: Boolean,
    locale: Locale,
    onToggle: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val evidence = cleanupEvidenceText(item, dateFormat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.primary,
                uncheckedColor = colors.outline,
                checkmarkColor = colors.onPrimary
            ),
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.profile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isActiveNode) {
                    Text(
                        text = stringResource(R.string.sources_connected),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.statusConnected
                    )
                }
            }
            if (item.reason.isNotBlank()) {
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (evidence != null) {
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun cleanupEvidenceText(item: SmartCleanupItem, dateFormat: DateFormat): String? {
    val profile = item.profile
    val endpoint = if (profile.address.isNotBlank()) {
        val portPart = if (profile.port > 0) ":${profile.port}" else ""
        "${profile.protocol.uppercase(Locale.ROOT)} · ${profile.address}$portPart"
    } else {
        null
    }
    val timestampPart = when (item.category) {
        SmartCleanupCategory.REPEATED_FAILURES -> profile.lastAttemptAt?.let {
            stringResource(R.string.cleanup_evidence_last_attempt, dateFormat.format(Date(it)))
        }
        SmartCleanupCategory.EXTENDED_DISABLED -> profile.disabledAt?.let {
            stringResource(R.string.cleanup_evidence_disabled, dateFormat.format(Date(it)))
        }
        SmartCleanupCategory.STALE_HEALTH -> (profile.lastTestedAt ?: profile.lastSuccessAt)?.let {
            stringResource(R.string.cleanup_evidence_last_tested, dateFormat.format(Date(it)))
        }
        SmartCleanupCategory.DUPLICATES,
        SmartCleanupCategory.INVALID,
        SmartCleanupCategory.ORPHANED -> null
    }
    val errorPart = if (item.category == SmartCleanupCategory.REPEATED_FAILURES) {
        profile.lastErrorMessage?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    return listOfNotNull(timestampPart, errorPart ?: endpoint)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

@Composable
private fun TrashTabContent(
    entries: List<TrashEntry>,
    locale: Locale,
    language: String,
    selectedTrashIds: Set<String>,
    onToggleEntry: (TrashEntry) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenDetails: (TrashEntry) -> Unit
) {
    val colors = LocalLuxyColors.current
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.cleanup_trash_retention),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSelectAll,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cleanup_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClearSelection,
                    enabled = selectedTrashIds.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.cleanup_clear_selection,
                            Digits.localize(selectedTrashIds.size.toString(), language)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedTrashIds.isNotEmpty()) colors.onSurfaceVariant else colors.faint
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.cleanup_trash_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .testTag("smart_cleanup_trash_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                items(
                    items = entries,
                    key = { it.id },
                    contentType = { "trash_entry" }
                ) { entry ->
                    TrashEntryRow(
                        entry = entry,
                        checked = entry.id in selectedTrashIds,
                        dateFormat = dateFormat,
                        onToggle = { onToggleEntry(entry) },
                        onOpenDetails = { onOpenDetails(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashEntryRow(
    entry: TrashEntry,
    checked: Boolean,
    dateFormat: DateFormat,
    onToggle: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val categoryText = trashCategoryTitle(entry.category)
    val fullName = entry.profileName.ifBlank { entry.label.ifBlank { entry.profileId } }
    val deleted = dateFormat.format(Date(entry.deletedAt))
    val expires = dateFormat.format(Date(entry.expiresAt))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.background,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.primary,
                    uncheckedColor = colors.outline,
                    checkmarkColor = colors.onPrimary
                ),
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = fullName }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenDetails),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = fullName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDetails)
                )
                if (categoryText.isNotBlank() || entry.reason.isNotBlank()) {
                    Text(
                        text = listOf(categoryText, entry.reason)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "$deleted · ${stringResource(R.string.sub_expires_label)} $expires",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onOpenDetails) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cleanup_trash_detail_title),
                    tint = colors.primary
                )
            }
        }
    }
}

@Composable
private fun trashCategoryTitle(rawCategory: String): String {
    val category = runCatching {
        SmartCleanupCategory.valueOf(rawCategory.trim().uppercase(Locale.ROOT))
    }.getOrNull()
    return if (category != null) cleanupCategoryTitle(category) else rawCategory.ifBlank { "—" }
}

@Composable
private fun trashSourceTitle(sourceType: String): String = when (SourceType.parse(sourceType)) {
    SourceType.MANUAL -> stringResource(R.string.cleanup_trash_source_manual)
    SourceType.SUBSCRIPTION -> stringResource(R.string.cleanup_trash_source_subscription)
    SourceType.LEGACY_UNCLASSIFIED -> stringResource(R.string.cleanup_trash_source_legacy)
}

@Composable
private fun cleanupCategoryTitle(category: SmartCleanupCategory): String = stringResource(
    when (category) {
        SmartCleanupCategory.DUPLICATES -> R.string.cleanup_cat_duplicates
        SmartCleanupCategory.INVALID -> R.string.cleanup_cat_invalid
        SmartCleanupCategory.REPEATED_FAILURES -> R.string.cleanup_cat_repeated_failures
        SmartCleanupCategory.EXTENDED_DISABLED -> R.string.cleanup_cat_extended_disabled
        SmartCleanupCategory.STALE_HEALTH -> R.string.cleanup_cat_stale_health
        SmartCleanupCategory.ORPHANED -> R.string.cleanup_cat_orphaned
    }
)

@Composable
private fun cleanupCategoryDescription(category: SmartCleanupCategory): String = stringResource(
    when (category) {
        SmartCleanupCategory.DUPLICATES -> R.string.cleanup_desc_duplicates
        SmartCleanupCategory.INVALID -> R.string.cleanup_desc_invalid
        SmartCleanupCategory.REPEATED_FAILURES -> R.string.cleanup_desc_repeated_failures
        SmartCleanupCategory.EXTENDED_DISABLED -> R.string.cleanup_desc_extended_disabled
        SmartCleanupCategory.STALE_HEALTH -> R.string.cleanup_desc_stale_health
        SmartCleanupCategory.ORPHANED -> R.string.cleanup_desc_orphaned
    }
)
