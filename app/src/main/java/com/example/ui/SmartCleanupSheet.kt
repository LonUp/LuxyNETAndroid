package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
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
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var selectedTab by remember { mutableIntStateOf(TAB_SUGGESTIONS) }
    var selectedItemIds by remember { mutableStateOf(defaultSelection(plan)) }
    var selectedTrashIds by remember { mutableStateOf(emptySet<String>()) }
    var expandedCategories by remember { mutableStateOf(emptySet<String>()) }
    var confirmPermanentDelete by remember { mutableStateOf(false) }

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
    val selectedTrash = trashEntries.filter { it.id in selectedTrashIds }

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
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.cleanup_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanupTabPill(
                    label = stringResource(R.string.cleanup_tab_suggestions),
                    selected = selectedTab == TAB_SUGGESTIONS,
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectedTab == TAB_SUGGESTIONS) {
                    SuggestionsTabContent(
                        plan = plan,
                        activeNodeId = activeNodeId,
                        language = language,
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
                        selectedTrashIds = selectedTrashIds,
                        onToggleEntry = { entry ->
                            selectedTrashIds = if (entry.id in selectedTrashIds) {
                                selectedTrashIds - entry.id
                            } else {
                                selectedTrashIds + entry.id
                            }
                        }
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            if (selectedTab == TAB_SUGGESTIONS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.cleanup_keep),
                            color = colors.onSurfaceVariant,
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
            } else {
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
                        onClick = { confirmPermanentDelete = true },
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

    if (confirmPermanentDelete) {
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.cleanup_perm_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cleanup_perm_delete_message,
                        Digits.localize(selectedTrash.size.toString(), language)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedTrash.map { it.id }
                    confirmPermanentDelete = false
                    selectedTrashIds = emptySet()
                    onPermanentlyDeleteTrash(ids)
                }) {
                    Text(stringResource(R.string.cleanup_perm_delete_confirm), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
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
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.primary else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun SuggestionsTabContent(
    plan: SmartCleanupPlan,
    activeNodeId: String?,
    language: String,
    selectedItemIds: Set<String>,
    expandedCategories: Set<String>,
    onToggleGroup: (SmartCleanupGroup) -> Unit,
    onToggleItem: (SmartCleanupItem) -> Unit,
    onTogglePreview: (SmartCleanupCategory) -> Unit
) {
    val colors = LocalLuxyColors.current
    Text(
        text = stringResource(R.string.cleanup_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )
    if (plan.groups.isEmpty()) {
        Text(
            text = stringResource(R.string.cleanup_no_suggestions),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        return
    }
    plan.groups.forEach { group ->
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                            color = colors.primary
                        )
                    }
                    TextButton(
                        onClick = { onTogglePreview(group.category) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (previewExpanded) R.string.cleanup_hide_preview else R.string.cleanup_preview
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (previewExpanded) {
                    HorizontalDivider(color = colors.outlineVariant)
                    group.items.forEach { item ->
                        CleanupItemRow(
                            item = item,
                            checked = item.profile.id in selectedItemIds,
                            isActiveNode = item.profile.id == activeNodeId,
                            onToggle = { onToggleItem(item) }
                        )
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
    onToggle: () -> Unit
) {
    val colors = LocalLuxyColors.current
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
        }
    }
}

@Composable
private fun TrashTabContent(
    entries: List<TrashEntry>,
    locale: Locale,
    selectedTrashIds: Set<String>,
    onToggleEntry: (TrashEntry) -> Unit
) {
    val colors = LocalLuxyColors.current
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    Text(
        text = stringResource(R.string.cleanup_trash_retention),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.cleanup_trash_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        return
    }
    entries.forEach { entry ->
        val checked = entry.id in selectedTrashIds
        val parsedCategory = runCatching {
            SmartCleanupCategory.valueOf(entry.category.trim().uppercase(Locale.ROOT))
        }.getOrNull()
        val categoryText = when {
            parsedCategory != null -> cleanupCategoryTitle(parsedCategory)
            entry.category.isNotBlank() -> entry.category
            else -> ""
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.background,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = { onToggleEntry(entry) }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    Text(
                        text = entry.profileName.ifBlank { entry.label },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    val deleted = dateFormat.format(Date(entry.deletedAt))
                    val expires = dateFormat.format(Date(entry.expiresAt))
                    Text(
                        text = "$deleted · ${stringResource(R.string.sub_expires_label)} $expires",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
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
