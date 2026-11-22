package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.ConfigIdentityLabel
import com.example.core.CountryOption
import com.example.core.NetworkFingerprintProvider
import com.example.core.ServerCountryResolver
import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.CountryVerificationFilter
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.EffectiveHealthState
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.ManualConfigCounts
import com.example.data.model.ManualConfigFilter
import com.example.data.model.MeasurementType
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.data.model.TrashEntry
import com.example.i18n.Digits
import com.example.i18n.UiText
import com.example.ui.theme.LocalLuxyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
private data class ManualConfigRowModel(
    val profile: ServerProfileEntity,
    val health: EffectiveHealthState,
    val countryCode: String?,
    val countryOption: CountryOption?,
    val confidence: CountryConfidence,
    val countrySource: CountrySource,
    val isUnclassified: Boolean,
    val measurementType: MeasurementType,
    val isStale: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualConfigurationsOverlay(
    profiles: List<ServerProfileEntity>,
    counts: ManualConfigCounts,
    deletedBackups: List<DeletedBackupBatch>,
    lastDeletedBatch: DeletedBackupBatch?,
    isRetesting: Boolean,
    retestProgress: Pair<Int, Int>?,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onToggleProfileEnabled: (String, Boolean) -> Unit,
    onBulkSetEnabled: (List<String>, Boolean) -> Unit,
    onBulkDelete: (List<String>) -> Unit,
    onBulkRetest: (List<String>) -> Unit,
    onCancelRetest: () -> Unit,
    onRemoveDuplicates: () -> Unit,
    onRestoreBackup: (String) -> Unit,
    onClearLastDeletedBatch: () -> Unit,
    onReclassifyToManual: (List<String>) -> Unit,
    onSetManualCountry: (String, String?) -> Unit,
    onDetectCountries: (List<String>) -> Unit,
    smartCleanupPlan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
    trashEntries: List<TrashEntry> = emptyList(),
    activeNodeId: String? = null,
    onSetProfilePreferred: (String, Boolean) -> Unit = { _, _ -> },
    onMoveCleanupToTrash: (List<SmartCleanupItem>) -> Unit = {},
    onRestoreTrash: (List<String>) -> Unit = {},
    onPermanentlyDeleteTrash: (List<String>) -> Unit = {},
    onDismissCleanupSuggestions: (List<SmartCleanupItem>) -> Unit = {},
    subscriptionNames: Map<String, String> = emptyMap()
) {
    BackHandler(onBack = onClose)

    val colors = LocalLuxyColors.current
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val language = locale.language
    var healthRefreshAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isNotEmpty()) {
            while (true) {
                delay(60_000L)
                healthRefreshAt = System.currentTimeMillis()
            }
        }
    }
    val netFp = remember(context) { NetworkFingerprintProvider.current(context) }
    val unknownProfileIds = remember(profiles) {
        profiles.filter {
            ServerCountryResolver.effectiveConfidence(it) == CountryConfidence.UNKNOWN
        }.map { it.id }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ManualConfigFilter.ALL) }
    var preferredOnly by rememberSaveable { mutableStateOf(false) }
    var countryFilter by rememberSaveable { mutableStateOf(CountryVerificationFilter.ALL) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var detailProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showDedupConfirm by rememberSaveable { mutableStateOf(false) }
    var showCleanupSheet by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var countryPickerProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    val undoLabel = stringResource(R.string.manual_undo)
    LaunchedEffect(lastDeletedBatch, language) {
        val batch = lastDeletedBatch ?: return@LaunchedEffect
        val res = snackbar.showSnackbar(
            message = UiText.translate(language, batch.noticeMessage ?: "Deleted ${batch.count} configuration(s)"),
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long
        )
        if (res == SnackbarResult.ActionPerformed) {
            onRestoreBackup(batch.batchId)
        } else {
            onClearLastDeletedBatch()
        }
    }

    // Prune stale selections when profiles change
    LaunchedEffect(profiles) {
        val validIds = profiles.mapTo(HashSet(profiles.size)) { it.id }
        if (selectedIds.any { it !in validIds }) {
            selectedIds = selectedIds.filterTo(HashSet()) { it in validIds }
        }
    }

    var hasComputedRows by remember { mutableStateOf(false) }
    val filteredRows by produceState(
        initialValue = emptyList<ManualConfigRowModel>(),
        profiles,
        searchQuery,
        activeFilter,
        preferredOnly,
        countryFilter,
        locale,
        netFp,
        healthRefreshAt
    ) {
        if (profiles.size > 100 && searchQuery.isNotEmpty()) {
            delay(100)
        }
        val nowMs = healthRefreshAt
        value = withContext(Dispatchers.Default) {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            val optionCache = HashMap<String, CountryOption?>()
            profiles.mapNotNull { p ->
                val health = HealthPolicy.effectiveHealthState(p, nowMs, netFp)
                val isUnclassified = SourceType.parse(p.sourceType) == SourceType.LEGACY_UNCLASSIFIED
                val matchesFilter = when (activeFilter) {
                    ManualConfigFilter.ALL -> true
                    ManualConfigFilter.WORKING -> health == EffectiveHealthState.WORKING
                    ManualConfigFilter.FAILED -> health == EffectiveHealthState.FAILED
                    ManualConfigFilter.UNTESTED -> health == EffectiveHealthState.UNTESTED
                    ManualConfigFilter.DISABLED -> health == EffectiveHealthState.DISABLED
                    ManualConfigFilter.INVALID -> health == EffectiveHealthState.INVALID
                    ManualConfigFilter.PREFERRED -> p.isPinned
                    ManualConfigFilter.UNCLASSIFIED -> isUnclassified
                }
                if (!matchesFilter || (preferredOnly && !p.isPinned)) return@mapNotNull null
                val confidence = ServerCountryResolver.effectiveConfidence(p)
                if (countryFilter == CountryVerificationFilter.VERIFIED &&
                    confidence != CountryConfidence.VERIFIED
                ) return@mapNotNull null
                if (countryFilter == CountryVerificationFilter.UNVERIFIED &&
                    confidence == CountryConfidence.VERIFIED
                ) return@mapNotNull null

                val code = ServerCountryResolver.effectiveCountryCode(p)
                val option = code?.let { c ->
                    optionCache.getOrPut(c) { ServerCountryResolver.optionForCode(c, locale) }
                }
                if (q.isNotEmpty()) {
                    val hit = p.name.lowercase(Locale.ROOT).contains(q) ||
                        p.address.lowercase(Locale.ROOT).contains(q) ||
                        p.protocol.lowercase(Locale.ROOT).contains(q) ||
                        (code?.lowercase(Locale.ROOT)?.contains(q) == true) ||
                        (option?.name?.lowercase(Locale.ROOT)?.contains(q) == true)
                    if (!hit) return@mapNotNull null
                }
                ManualConfigRowModel(
                    profile = p,
                    health = health,
                    countryCode = code,
                    countryOption = option,
                    confidence = confidence,
                    countrySource = ServerCountryResolver.effectiveSource(p),
                    isUnclassified = isUnclassified,
                    measurementType = MeasurementType.parse(p.lastMeasurementType),
                    isStale = HealthPolicy.isHealthStale(p, nowMs, netFp)
                )
            }
        }
        hasComputedRows = true
    }

    val selectedProfiles = remember(profiles, selectedIds) {
        profiles.filter { it.id in selectedIds }
    }
    val allSelectedDisabled = selectedProfiles.isNotEmpty() && selectedProfiles.all { !it.isEnabled }
    val anySelectedUnclassified = selectedProfiles.any {
        SourceType.parse(it.sourceType) == SourceType.LEGACY_UNCLASSIFIED
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                Surface(
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BulkActionButton(
                                icon = Icons.Rounded.Refresh,
                                label = stringResource(R.string.manual_retest),
                                tint = colors.primary,
                                enabled = !isRetesting,
                                onClick = { onBulkRetest(selectedIds.toList()) },
                                modifier = Modifier.weight(1f)
                            )
                            BulkActionButton(
                                icon = if (allSelectedDisabled) Icons.Rounded.Check else Icons.Rounded.Block,
                                label = stringResource(
                                    if (allSelectedDisabled) R.string.manual_enable_selected
                                    else R.string.manual_disable_selected
                                ),
                                tint = colors.onSurface,
                                enabled = true,
                                onClick = {
                                    onBulkSetEnabled(selectedIds.toList(), allSelectedDisabled)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BulkActionButton(
                                icon = Icons.Outlined.Delete,
                                label = stringResource(R.string.manual_delete_selected),
                                tint = colors.error,
                                enabled = true,
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (anySelectedUnclassified) {
                            OutlinedButton(
                                onClick = {
                                    val unclassifiedIds = selectedProfiles
                                        .filter { SourceType.parse(it.sourceType) == SourceType.LEGACY_UNCLASSIFIED }
                                        .map { it.id }
                                    onReclassifyToManual(unclassifiedIds)
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.manual_move_to_manual),
                                    color = colors.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onClose,
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
                            text = stringResource(R.string.manual_back_subscriptions),
                            color = colors.primary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val cleanupTitle = stringResource(R.string.cleanup_title)
                        val cleanupBadgeCount =
                            if (smartCleanupPlan.totalSuggested > 0) smartCleanupPlan.totalSuggested else trashEntries.size
                        Surface(
                            onClick = { showCleanupSheet = true },
                            shape = RoundedCornerShape(999.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.semantics { contentDescription = cleanupTitle }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                                if (cleanupBadgeCount > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = colors.primary.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = Digits.localize(cleanupBadgeCount.toString(), language),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { overflowExpanded = true },
                                modifier = Modifier.semantics {
                                    contentDescription = context.getString(R.string.manual_more_actions_cd)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant
                                )
                            }
                            val selectionActive = selectionMode || selectedIds.isNotEmpty()
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (selectionActive) R.string.manual_done_selecting
                                                else R.string.manual_select_mode
                                            )
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        selectionMode = !selectionActive
                                        if (selectionActive) selectedIds = emptySet()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manual_detect_countries)) },
                                    enabled = unknownProfileIds.isNotEmpty(),
                                    onClick = {
                                        overflowExpanded = false
                                        onDetectCountries(unknownProfileIds)
                                    }
                                )
                                if (counts.duplicateCount > 0) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.manual_remove_duplicates,
                                                    Digits.localize(counts.duplicateCount.toString(), language)
                                                )
                                            )
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            showDedupConfirm = true
                                        }
                                    )
                                }
                                if (counts.unclassified > 0) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.manual_move_to_manual)) },
                                        onClick = {
                                            overflowExpanded = false
                                            onReclassifyToManual(
                                                profiles.filter {
                                                    SourceType.parse(it.sourceType) == SourceType.LEGACY_UNCLASSIFIED
                                                }.map { it.id }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.manual_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground
                )
                Text(
                    text = stringResource(R.string.manual_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // Unified health filters.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SummaryCountCard(
                        label = stringResource(R.string.manual_filter_all),
                        count = Digits.localize(counts.total.toString(), language),
                        accent = colors.onSurfaceVariant,
                        borderColor = colors.outlineVariant,
                        selected = activeFilter == ManualConfigFilter.ALL,
                        onClick = { activeFilter = ManualConfigFilter.ALL }
                    )
                    SummaryCountCard(
                        label = stringResource(R.string.manual_filter_working),
                        count = Digits.localize(counts.working.toString(), language),
                        accent = colors.statusConnected,
                        borderColor = colors.statusConnected.copy(alpha = 0.30f),
                        selected = activeFilter == ManualConfigFilter.WORKING,
                        onClick = {
                            activeFilter =
                                if (activeFilter == ManualConfigFilter.WORKING) ManualConfigFilter.ALL
                                else ManualConfigFilter.WORKING
                        }
                    )
                    SummaryCountCard(
                        label = stringResource(R.string.manual_filter_failed),
                        count = Digits.localize(counts.failed.toString(), language),
                        accent = colors.error,
                        borderColor = colors.error.copy(alpha = 0.30f),
                        selected = activeFilter == ManualConfigFilter.FAILED,
                        onClick = {
                            activeFilter =
                                if (activeFilter == ManualConfigFilter.FAILED) ManualConfigFilter.ALL
                                else ManualConfigFilter.FAILED
                        }
                    )
                    SummaryCountCard(
                        label = stringResource(R.string.manual_filter_untested),
                        count = Digits.localize(counts.untested.toString(), language),
                        accent = colors.onSurface,
                        borderColor = colors.outlineVariant,
                        selected = activeFilter == ManualConfigFilter.UNTESTED,
                        onClick = {
                            activeFilter =
                                if (activeFilter == ManualConfigFilter.UNTESTED) ManualConfigFilter.ALL
                                else ManualConfigFilter.UNTESTED
                        }
                    )
                    SummaryCountCard(
                        label = stringResource(R.string.manual_filter_disabled),
                        count = Digits.localize(counts.disabled.toString(), language),
                        accent = colors.onSurfaceVariant,
                        borderColor = colors.outlineVariant,
                        selected = activeFilter == ManualConfigFilter.DISABLED,
                        onClick = {
                            activeFilter =
                                if (activeFilter == ManualConfigFilter.DISABLED) ManualConfigFilter.ALL
                                else ManualConfigFilter.DISABLED
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))


                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.manual_search_hint),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        cursorColor = colors.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // Orthogonal preferred, country verification, and exceptional-state filters.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { preferredOnly = !preferredOnly },
                        shape = RoundedCornerShape(999.dp),
                        color = if (preferredOnly) colors.primary else colors.surface,
                        border = if (preferredOnly) null else BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(
                            text = "★ ${stringResource(R.string.manual_filter_preferred)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (preferredOnly) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (preferredOnly) colors.onPrimary else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            countryFilter =
                                if (countryFilter == CountryVerificationFilter.VERIFIED) CountryVerificationFilter.ALL
                                else CountryVerificationFilter.VERIFIED
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = if (countryFilter == CountryVerificationFilter.VERIFIED) colors.primary else colors.surface,
                        border = if (countryFilter == CountryVerificationFilter.VERIFIED) {
                            null
                        } else BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(
                            text = "✓ ${stringResource(R.string.manual_filter_verified_country)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (countryFilter == CountryVerificationFilter.VERIFIED) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (countryFilter == CountryVerificationFilter.VERIFIED) colors.onPrimary else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            countryFilter =
                                if (countryFilter == CountryVerificationFilter.UNVERIFIED) CountryVerificationFilter.ALL
                                else CountryVerificationFilter.UNVERIFIED
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = if (countryFilter == CountryVerificationFilter.UNVERIFIED) colors.primary else colors.surface,
                        border = if (countryFilter == CountryVerificationFilter.UNVERIFIED) {
                            null
                        } else BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(
                            text = "? ${stringResource(R.string.manual_filter_unverified_country)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (countryFilter == CountryVerificationFilter.UNVERIFIED) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (countryFilter == CountryVerificationFilter.UNVERIFIED) colors.onPrimary else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    if (counts.invalid > 0 || activeFilter == ManualConfigFilter.INVALID) {
                        Surface(
                            onClick = {
                                activeFilter =
                                    if (activeFilter == ManualConfigFilter.INVALID) ManualConfigFilter.ALL
                                    else ManualConfigFilter.INVALID
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = if (activeFilter == ManualConfigFilter.INVALID) colors.primary else colors.surface,
                            border = if (activeFilter == ManualConfigFilter.INVALID) null else BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(R.string.manual_filter_invalid),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activeFilter == ManualConfigFilter.INVALID) colors.onPrimary else colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                    if (counts.unclassified > 0 || activeFilter == ManualConfigFilter.UNCLASSIFIED) {
                        Surface(
                            onClick = {
                                activeFilter =
                                    if (activeFilter == ManualConfigFilter.UNCLASSIFIED) ManualConfigFilter.ALL
                                    else ManualConfigFilter.UNCLASSIFIED
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = if (activeFilter == ManualConfigFilter.UNCLASSIFIED) colors.primary else colors.surface,
                            border = if (activeFilter == ManualConfigFilter.UNCLASSIFIED) null else BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(R.string.manual_filter_unclassified),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activeFilter == ManualConfigFilter.UNCLASSIFIED) colors.onPrimary else colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                if (isRetesting && retestProgress != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.manual_retesting,
                                        Digits.localize(retestProgress.first.toString(), language),
                                        Digits.localize(retestProgress.second.toString(), language)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary
                                )
                                TextButton(
                                    onClick = onCancelRetest,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.minimal_cancel), color = colors.error)
                                }
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (retestProgress.second > 0) {
                                        retestProgress.first.toFloat() / retestProgress.second.toFloat()
                                    } else 0f
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = colors.primary,
                                trackColor = colors.background
                            )
                        }
                    }
                }

                // Selection controls are only shown after explicitly entering selection mode.
                val selectionActive = selectionMode || selectedIds.isNotEmpty()
                if (selectionActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    selectedIds = filteredRows.mapTo(HashSet(filteredRows.size)) { it.profile.id }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        if (
                                            activeFilter == ManualConfigFilter.ALL &&
                                            !preferredOnly &&
                                            countryFilter == CountryVerificationFilter.ALL &&
                                            searchQuery.isBlank()
                                        ) {
                                            R.string.manual_select_all
                                        } else R.string.manual_select_matching
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary
                                )
                            }
                            if (selectedIds.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.manual_selected_count,
                                        Digits.localize(selectedIds.size.toString(), language)
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { selectedIds = emptySet() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.manual_clear_selection,
                                            Digits.localize(selectedIds.size.toString(), language)
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                selectedIds = emptySet()
                                selectionMode = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.manual_done_selecting),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary
                            )
                        }
                    }
                }
                // List
                if (filteredRows.isEmpty() && hasComputedRows) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.manual_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredRows,
                            key = { it.profile.id },
                            contentType = { "manual_config_row" }
                        ) { row ->
                            val isSelected = row.profile.id in selectedIds
                            ManualConfigRow(
                                row = row,
                                selected = isSelected,
                                selectionMode = selectionMode || selectedIds.isNotEmpty(),
                                language = language,
                                onToggleSelect = {
                                    selectedIds = if (isSelected) selectedIds - row.profile.id else selectedIds + row.profile.id
                                },
                                onToggleEnabled = { enabled ->
                                    onToggleProfileEnabled(row.profile.id, enabled)
                                },
                                onTogglePreferred = {
                                    onSetProfilePreferred(row.profile.id, !row.profile.isPinned)
                                },
                                onClick = {
                                    if (selectionMode || selectedIds.isNotEmpty()) {
                                        selectedIds = if (isSelected) selectedIds - row.profile.id else selectedIds + row.profile.id
                                    } else {
                                        detailProfileId = row.profile.id
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selectedIds = selectedIds + row.profile.id
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Bulk delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.manual_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.manual_delete_confirm_message,
                        Digits.localize(selectedIds.size.toString(), language)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = selectedIds.toList()
                    showDeleteConfirm = false
                    selectedIds = emptySet()
                    selectionMode = false
                    onBulkDelete(targets)
                }) {
                    Text(stringResource(R.string.sub_delete), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
            }
        )
    }

    // Duplicate cleanup confirmation
    if (showDedupConfirm) {
        AlertDialog(
            onDismissRequest = { showDedupConfirm = false },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.manual_dedup_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.manual_dedup_confirm_message,
                        Digits.localize(counts.duplicateCount.toString(), language)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDedupConfirm = false
                    onRemoveDuplicates()
                }) {
                    Text(stringResource(R.string.sub_delete), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDedupConfirm = false }) {
                    Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
                }
            }
        )
    }


    // Detail sheet for a single configuration
    profiles.firstOrNull { it.id == detailProfileId }?.let { detailProfile ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val nowMs = System.currentTimeMillis()
        val health = HealthPolicy.effectiveHealthState(detailProfile, nowMs, netFp)
        val countryCode = ServerCountryResolver.effectiveCountryCode(detailProfile)
        val countryOption = countryCode?.let { ServerCountryResolver.optionForCode(it, locale) }
        val confidence = ServerCountryResolver.effectiveConfidence(detailProfile)
        val countrySource = ServerCountryResolver.effectiveSource(detailProfile)
        val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }

        ModalBottomSheet(
            onDismissRequest = { detailProfileId = null },
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
                    text = detailProfile.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground
                )
                Text(
                    text = "${detailProfile.protocol} • ${detailProfile.address}:${detailProfile.port} • ${detailProfile.transport}/${detailProfile.security}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HealthBadge(
                        health = health,
                        latencyMs = detailProfile.latencyMs,
                        measurementType = MeasurementType.parse(detailProfile.lastMeasurementType),
                        stale = HealthPolicy.isHealthStale(detailProfile, nowMs, netFp),
                        language = language
                    )
                    ConfidencePill(confidence = confidence, source = countrySource)
                }

                HorizontalDivider(color = colors.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.manual_detail_enable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                    Switch(
                        checked = detailProfile.isEnabled,
                        onCheckedChange = { onToggleProfileEnabled(detailProfile.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.onSurfaceVariant,
                            uncheckedTrackColor = colors.surfaceContainer
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.manual_detail_preferred),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                    Switch(
                        checked = detailProfile.isPinned,
                        onCheckedChange = { onSetProfilePreferred(detailProfile.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.onSurfaceVariant,
                            uncheckedTrackColor = colors.surfaceContainer
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.manual_detail_country),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            text = countryOption?.let {
                                if (it.flag.isNotEmpty()) "${it.flag} ${it.name}" else it.name
                            } ?: stringResource(R.string.manual_country_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface
                        )
                    }
                    TextButton(onClick = { countryPickerProfileId = detailProfile.id }) {
                        Text(stringResource(R.string.manual_detail_assign_country), color = colors.primary)
                    }
                }

                detailProfile.lastSuccessAt?.let { ts ->
                    Text(
                        text = stringResource(R.string.manual_detail_last_success, dates.format(Date(ts))),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                detailProfile.lastAttemptAt?.let { ts ->
                    Text(
                        text = stringResource(R.string.manual_detail_last_attempt, dates.format(Date(ts))),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                detailProfile.exitIp?.let { ip ->
                    Text(
                        text = stringResource(R.string.manual_detail_exit_ip, ip),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (!detailProfile.lastErrorMessage.isNullOrBlank() &&
                    detailProfile.lastErrorCategory != ErrorCategory.NONE.name
                ) {
                    Text(
                        text = stringResource(R.string.manual_detail_error, detailProfile.lastErrorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onBulkRetest(listOf(detailProfile.id))
                            detailProfileId = null
                        },
                        enabled = !isRetesting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.manual_detail_retest_now))
                    }
                    OutlinedButton(
                        onClick = {
                            val id = detailProfile.id
                            detailProfileId = null
                            onBulkDelete(listOf(id))
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.sub_delete), color = colors.error)
                    }
                }

                if (SourceType.parse(detailProfile.sourceType) == SourceType.LEGACY_UNCLASSIFIED) {
                    OutlinedButton(
                        onClick = {
                            onReclassifyToManual(listOf(detailProfile.id))
                            detailProfileId = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.manual_move_to_manual), color = colors.primary)
                    }
                }
            }
        }
    }

    // Manual country picker dialog
    profiles.firstOrNull { it.id == countryPickerProfileId }?.let { targetProfile ->
        ManualCountryPickerDialog(
            locale = locale,
            hasManualOverride = targetProfile.manualCountryCode != null,
            onSelect = { code ->
                onSetManualCountry(targetProfile.id, code)
                countryPickerProfileId = null
            },
            onDismiss = { countryPickerProfileId = null }
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
            onMoveToTrash = onMoveCleanupToTrash,
            onRestoreTrash = onRestoreTrash,
            onPermanentlyDeleteTrash = onPermanentlyDeleteTrash,
            onDismiss = { showCleanupSheet = false },
            onDismissSuggestions = { dismissedItems ->
                onDismissCleanupSuggestions(dismissedItems)
                if (dismissedItems.size >= smartCleanupPlan.totalSuggested) {
                    showCleanupSheet = false
                }
            }
        )
    }
}

@Composable
private fun SummaryCountCard(
    label: String,
    count: String,
    accent: Color,
    borderColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.primary else colors.surface,
        border = if (selected) null else BorderStroke(1.dp, borderColor),
        modifier = modifier.widthIn(min = 48.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onPrimary else accent,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center
            )
            Text(
                text = count,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) colors.onPrimary else accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ManualConfigRow(
    row: ManualConfigRowModel,
    selected: Boolean,
    selectionMode: Boolean,
    language: String,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePreferred: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val profile = row.profile
    val countryOption = row.countryOption
    val countryLabel = if (countryOption == null) {
        stringResource(R.string.manual_country_unknown)
    } else {
        when {
            row.confidence == CountryConfidence.VERIFIED ->
                stringResource(R.string.manual_exit_label, countryOption.name)
            row.countrySource == CountrySource.MANUAL ->
                stringResource(R.string.manual_manual_country_label, countryOption.name)
            row.confidence == CountryConfidence.ESTIMATED ->
                stringResource(R.string.manual_estimated_label, countryOption.name)
            else -> stringResource(R.string.manual_country_unknown)
        }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.primary,
                        uncheckedColor = colors.outline,
                        checkmarkColor = colors.onPrimary
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (countryOption?.flag?.isNotEmpty() == true) {
                        Text(
                            text = countryOption.flag,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (profile.isEnabled) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onTogglePreferred,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (profile.isPinned) Icons.Rounded.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.manual_preferred_toggle_cd, profile.name),
                            tint = if (profile.isPinned) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = countryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("·", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    ConfidencePill(confidence = row.confidence, source = row.countrySource)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = remember(profile) { ConfigIdentityLabel.shortLabel(profile) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text("·", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        HealthBadge(
                            health = row.health,
                            latencyMs = profile.latencyMs,
                            measurementType = row.measurementType,
                            stale = row.isStale,
                            language = language,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    val toggleCd = "${profile.name} ${stringResource(R.string.manual_detail_enable)}"
                    Switch(
                        checked = profile.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier
                            .scale(0.72f)
                            .semantics {
                                contentDescription = toggleCd
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
        }
    }
}

@Composable
private fun ConfidencePill(
    confidence: CountryConfidence,
    source: CountrySource
) {
    val colors = LocalLuxyColors.current
    val (label, tint) = when {
        confidence == CountryConfidence.VERIFIED ->
            "✓ " + stringResource(R.string.manual_confidence_verified) to colors.statusConnected
        source == CountrySource.MANUAL ->
            stringResource(R.string.manual_confidence_manual) to colors.primary
        confidence == CountryConfidence.ESTIMATED ->
            "≈ " + stringResource(R.string.manual_confidence_estimated) to colors.primary
        else ->
            "? " + stringResource(R.string.manual_confidence_unknown) to colors.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.32f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun HealthBadge(
    health: EffectiveHealthState,
    latencyMs: Int?,
    measurementType: MeasurementType = MeasurementType.NONE,
    stale: Boolean = false,
    language: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    val (label, tint) = when (health) {
        EffectiveHealthState.WORKING -> {
            val base = stringResource(R.string.manual_filter_working)
            val measurement = measurementLabel(measurementType, latencyMs, language)
            (if (measurement != null) "$base · $measurement" else base) to colors.statusConnected
        }
        EffectiveHealthState.FAILED -> stringResource(R.string.manual_filter_failed) to colors.error
        EffectiveHealthState.UNTESTED -> stringResource(R.string.manual_filter_untested) to colors.primary
        EffectiveHealthState.DISABLED -> stringResource(R.string.manual_filter_disabled) to colors.onSurfaceVariant
        EffectiveHealthState.INVALID -> stringResource(R.string.manual_filter_invalid) to colors.error
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(tint, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (stale && health != EffectiveHealthState.UNTESTED) {
            Text(
                text = stringResource(R.string.manual_health_stale),
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** Names the measurement that produced the latency; never presents it as an ICMP ping. */
@Composable
private fun measurementLabel(
    type: MeasurementType,
    latencyMs: Int?,
    language: String
): String? {
    if (latencyMs == null || latencyMs < 0) return null
    val value = Digits.localize(latencyMs.toString(), language)
    return when (type) {
        MeasurementType.TCP_CONNECT -> stringResource(R.string.manual_measure_tcp, value)
        MeasurementType.PROXY_HTTP_204 -> stringResource(R.string.manual_measure_proxy, value)
        MeasurementType.TUNNEL_CONNECT -> stringResource(R.string.manual_measure_connect, value)
        MeasurementType.NONE -> null
    }
}

@Composable
private fun BulkActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    val stacked = LocalDensity.current.fontScale > 1.15f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        if (stacked) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) tint else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) tint else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) tint else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) tint else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ManualCountryPickerDialog(
    locale: Locale,
    hasManualOverride: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    var query by remember { mutableStateOf("") }
    val allCountries = remember(locale) {
        Locale.getISOCountries()
            .mapNotNull { ServerCountryResolver.optionForCode(it, locale) }
            .sortedBy { it.name }
    }
    val filtered = remember(allCountries, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) allCountries
        else allCountries.filter {
            it.name.lowercase(Locale.ROOT).contains(q) || it.code.lowercase(Locale.ROOT).contains(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onBackground,
        textContentColor = colors.onSurface,
        title = { Text(stringResource(R.string.manual_detail_assign_country)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.manual_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasManualOverride) {
                    TextButton(onClick = { onSelect(null) }) {
                        Text(stringResource(R.string.manual_detail_clear_country), color = colors.error)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.code }) { option ->
                        Surface(
                            onClick = { onSelect(option.code) },
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(text = option.flag, fontSize = 18.sp)
                                Text(
                                    text = "${option.name} (${option.code})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sub_cancel), color = colors.onSurfaceVariant)
            }
        }
    )
}
