package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.R
import com.example.core.ConfigIdentityLabel
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SourceType
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.TrashEntry
import com.example.i18n.AppDateFormat
import com.example.i18n.Digits
import com.example.ui.theme.LocalLuxyColors
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
    onDismissSuggestions: (List<SmartCleanupItem>) -> Unit = {},
    subscriptionNames: Map<String, String> = emptyMap()
) {
    val colors = LocalLuxyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val suggestedCount = plan.totalSuggested
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
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("smart_cleanup_sheet_content"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CleanupTabPill(
                    modifier = Modifier.weight(1f, fill = false),
                    label = stringResource(R.string.cleanup_tab_suggestions),
                    selected = selectedTab == TAB_SUGGESTIONS,
                    countText = if (suggestedCount > 0) {
                        Digits.localize(suggestedCount.toString(), language)
                    } else {
                        null
                    },
                    onClick = { selectedTab = TAB_SUGGESTIONS }
                )
                CleanupTabPill(
                    modifier = Modifier.weight(1f, fill = false),
                    label = stringResource(
                        R.string.cleanup_tab_trash,
                        Digits.localize(trashEntries.size.toString(), language)
                    ),
                    selected = selectedTab == TAB_TRASH,
                    onClick = { selectedTab = TAB_TRASH }
                )
            }

            if (selectedTab == TAB_SUGGESTIONS) {
                SuggestionsTabContent(
                    modifier = Modifier.weight(1f, fill = false),
                    plan = plan,
                    activeNodeId = activeNodeId,
                    language = language,
                    suggestedCount = suggestedCount,
                    subscriptionNames = subscriptionNames,
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
                    modifier = Modifier.weight(1f, fill = false),
                    entries = trashEntries,
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

            if (selectedTab == TAB_SUGGESTIONS && suggestedCount > 0) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismissSuggestions(selectedItems)
                            selectedItemIds = emptySet()
                        },
                        enabled = selectedItems.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cleanup_keep),
                            color = if (selectedItems.isNotEmpty()) {
                                colors.onSurface
                            } else {
                                colors.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onMoveToTrash(selectedItems)
                            selectedItemIds = emptySet()
                        },
                        enabled = selectedItems.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (selectedTab == TAB_TRASH && trashEntries.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onRestoreTrash(selectedTrash.map { it.id })
                            selectedTrashIds = emptySet()
                        },
                        enabled = selectedTrash.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cleanup_restore_selected,
                                Digits.localize(selectedTrash.size.toString(), language)
                            ),
                            color = if (selectedTrash.isNotEmpty()) colors.primary else colors.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { pendingPermanentDeleteIds = selectedTrash.map { it.id } },
                        enabled = selectedTrash.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cleanup_delete_permanently,
                                Digits.localize(selectedTrash.size.toString(), language)
                            ),
                            color = if (selectedTrash.isNotEmpty()) colors.error else colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                    Text(stringResource(R.string.cleanup_source, source))
                    Text(stringResource(R.string.cleanup_trash_category, category))
                    Text(stringResource(R.string.cleanup_trash_reason, entry.reason.ifBlank { "—" }))
                    Text(stringResource(R.string.cleanup_trash_deleted, AppDateFormat.dateTime(entry.deletedAt, language)))
                    Text("${stringResource(R.string.sub_expires_label)}: ${AppDateFormat.dateTime(entry.expiresAt, language)}")
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
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    countText: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = modifier.semantics {
            this.selected = selected
            role = Role.Tab
        },
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colors.accentSoft else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary.copy(alpha = 0.55f) else colors.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
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
    modifier: Modifier,
    plan: SmartCleanupPlan,
    activeNodeId: String?,
    language: String,
    suggestedCount: Int,
    subscriptionNames: Map<String, String>,
    selectedItemIds: Set<String>,
    expandedCategories: Set<String>,
    onToggleGroup: (SmartCleanupGroup) -> Unit,
    onToggleItem: (SmartCleanupItem) -> Unit,
    onTogglePreview: (SmartCleanupCategory) -> Unit
) {
    val colors = LocalLuxyColors.current
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("smart_cleanup_suggestions_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item(key = "suggestions_subtitle", contentType = "suggestions_header") {
            Text(
                text = stringResource(R.string.cleanup_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (suggestedCount == 0) {
            item(key = "suggestions_empty", contentType = "suggestions_empty") {
                Text(
                    text = stringResource(R.string.cleanup_no_suggestions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            plan.groups.forEach { group ->
                item(
                    key = "suggestion_group_${group.category.name}",
                    contentType = "suggestion_group"
                ) {
                    CleanupSuggestionGroupCard(
                        group = group,
                        language = language,
                        selectedItemIds = selectedItemIds,
                        previewExpanded = group.category.name in expandedCategories,
                        onToggleGroup = { onToggleGroup(group) },
                        onTogglePreview = { onTogglePreview(group.category) }
                    )
                }
                if (group.category.name in expandedCategories) {
                    items(
                        items = group.items,
                        key = { "suggestion_preview_${group.category.name}_${it.profile.id}" },
                        contentType = { "suggestion_preview" }
                    ) { item ->
                        Surface(
                            modifier = Modifier.padding(start = 24.dp).fillMaxWidth(),
                            color = colors.background,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            CleanupItemRow(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                item = item,
                                checked = item.profile.id in selectedItemIds,
                                isActiveNode = item.profile.id == activeNodeId,
                                language = language,
                                subscriptionNames = subscriptionNames,
                                onToggle = { onToggleItem(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupSuggestionGroupCard(
    group: SmartCleanupGroup,
    language: String,
    selectedItemIds: Set<String>,
    previewExpanded: Boolean,
    onToggleGroup: () -> Unit,
    onTogglePreview: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val ids = remember(group) { group.items.mapTo(HashSet(group.items.size)) { it.profile.id } }
    val selectedCount = ids.count { it in selectedItemIds }
    val toggleState = when {
        ids.isEmpty() || selectedCount == 0 -> ToggleableState.Off
        selectedCount == ids.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.background,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TriStateCheckbox(
                    state = toggleState,
                    onClick = onToggleGroup,
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
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
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
                            .clickable(role = Role.Button, onClick = onTogglePreview)
                            .heightIn(min = 40.dp)
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
            }
        }
    }
}

@Composable
private fun CleanupItemRow(
    modifier: Modifier = Modifier,
    item: SmartCleanupItem,
    checked: Boolean,
    isActiveNode: Boolean,
    language: String,
    subscriptionNames: Map<String, String>,
    onToggle: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val evidence = cleanupEvidenceText(item, language)
    val sourceName = when (SourceType.parse(item.profile.sourceType)) {
        SourceType.MANUAL -> stringResource(R.string.cleanup_trash_source_manual)
        SourceType.SUBSCRIPTION -> item.profile.subscriptionId
            ?.let(subscriptionNames::get)
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.cleanup_subscription_owner, it) }
            ?: stringResource(R.string.cleanup_trash_source_subscription)
        SourceType.LEGACY_UNCLASSIFIED -> stringResource(R.string.cleanup_trash_source_legacy)
    }
    Row(
        modifier = modifier
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
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
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
            Text(
                text = remember(item.profile) { ConfigIdentityLabel.shortLabel(item.profile) },
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
            Text(
                text = stringResource(R.string.cleanup_source, sourceName),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun cleanupEvidenceText(item: SmartCleanupItem, language: String): String? {
    val profile = item.profile
    val endpoint = if (profile.address.isNotBlank()) {
        val portPart = if (profile.port > 0) ":${profile.port}" else ""
        "${profile.protocol.uppercase(Locale.ROOT)} · ${profile.address}$portPart"
    } else {
        null
    }
    val timestampPart = when (item.category) {
        SmartCleanupCategory.REPEATED_FAILURES -> profile.lastAttemptAt?.let {
            stringResource(R.string.cleanup_evidence_last_attempt, AppDateFormat.date(it, language))
        }
        SmartCleanupCategory.EXTENDED_DISABLED -> profile.disabledAt?.let {
            stringResource(R.string.cleanup_evidence_disabled, AppDateFormat.date(it, language))
        }
        SmartCleanupCategory.STALE_HEALTH -> (profile.lastTestedAt ?: profile.lastSuccessAt)?.let {
            stringResource(R.string.cleanup_evidence_last_tested, AppDateFormat.date(it, language))
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
    modifier: Modifier,
    entries: List<TrashEntry>,
    language: String,
    selectedTrashIds: Set<String>,
    onToggleEntry: (TrashEntry) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenDetails: (TrashEntry) -> Unit
) {
    val colors = LocalLuxyColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.cleanup_trash_retention),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        if (entries.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSelectAll,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cleanup_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = onClearSelection,
                    enabled = selectedTrashIds.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.cleanup_clear_selection,
                            Digits.localize(selectedTrashIds.size.toString(), language)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedTrashIds.isNotEmpty()) colors.onSurfaceVariant else colors.faint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                        language = language,
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
    language: String,
    onToggle: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val categoryText = trashCategoryTitle(entry.category)
    val fullName = entry.profileName.ifBlank { entry.label.ifBlank { entry.profileId } }
    val deleted = AppDateFormat.date(entry.deletedAt, language)
    val expires = AppDateFormat.date(entry.expiresAt, language)
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
