package com.example.ui

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.data.local.SubscriptionEntity
import com.example.ui.theme.LocalLuxyColors
import androidx.compose.ui.res.stringResource
import java.net.URI
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun SubscriptionsPage(
    subscriptions: List<SubscriptionEntity>,
    managementEnabled: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onHome: () -> Unit,
    onRefresh: (String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    val colors = LocalLuxyColors.current
    val enabled = managementEnabled && !busy
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hasExpiry = subscriptions.any { it.expireAtEpochSec > 0 }
    LaunchedEffect(lifecycle, hasExpiry) {
        if (hasExpiry) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).widthIn(max = 560.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    TextButton(onClick = onHome, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sub_home))
                    }
                    Text(stringResource(R.string.sub_title), style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground)
                }
            }
            if (!managementEnabled) item {
                Text(stringResource(R.string.sub_locked), color = colors.onSurfaceVariant)
            }
            if (busy) item { Text(stringResource(R.string.sub_busy), color = colors.primary) }
            if (subscriptions.isEmpty()) item {
                Text(stringResource(R.string.sub_empty), color = colors.onSurfaceVariant)
            }
            items(subscriptions, key = { it.id }) { subscription ->
                if (editingId == subscription.id) {
                    EditSubscriptionForm(subscription, enabled, onDismiss = { editingId = null }) { name, url ->
                        if (enabled) {
                            onSave(subscription.id, name, url)
                            editingId = null
                        }
                    }
                } else {
                    SubscriptionCard(subscription, now, enabled,
                        onRefresh = { if (enabled) onRefresh(subscription.id) },
                        onEdit = { if (enabled) editingId = subscription.id },
                        onDelete = { if (enabled) deletingId = subscription.id })
                }
            }
        }
    }
    subscriptions.firstOrNull { it.id == deletingId }?.let { subscription ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.sub_delete_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sub_delete_message, subscription.name))
                if (!managementEnabled) Text(stringResource(R.string.sub_locked))
            } },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    if (enabled) { onDelete(subscription.id); deletingId = null }
                }) { Text(stringResource(R.string.sub_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.sub_cancel)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    now: Long,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val colors = LocalLuxyColors.current
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    fun isolated(value: String) = BidiFormatter.getInstance(locale).unicodeWrap(value, TextDirectionHeuristics.LTR)
    val unknown = stringResource(R.string.sub_unknown)
    // Zero is the database sentinel for missing metadata, not an unlimited or zero-use plan.
    val used = subscription.uploadBytes.coerceAtLeast(0).toDouble() + subscription.downloadBytes.coerceAtLeast(0).toDouble()
    val total = subscription.totalBytes.takeIf { it > 0 }?.toDouble()
    val usageKnown = used > 0
    val remaining = if (usageKnown && total != null) (total - used).coerceAtLeast(0.0) else null
    val expiry = subscription.expireAtEpochSec.takeIf { it > 0 }
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(subscription.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(stringResource(R.string.sub_servers, isolated(numbers.format(subscription.nodeCount))), color = colors.onSurfaceVariant)
            Text(stringResource(R.string.sub_used, if (usageKnown) byteSize(used, numbers, locale) else unknown))
            Text(stringResource(R.string.sub_remaining, remaining?.let { byteSize(it, numbers, locale) } ?: unknown))
            Text(stringResource(R.string.sub_allowance, total?.let { byteSize(it, numbers, locale) } ?: unknown))
            if (usageKnown && total != null) {
                LinearProgressIndicator(progress = { (used / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(),
                    color = colors.primary, trackColor = colors.background)
            }
            Text(stringResource(R.string.sub_expiry, expiry?.let {
                isolated(dates.format(Date(it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)))
            } ?: unknown))
            Text(stringResource(R.string.sub_time_left, expiry?.let { timeLeft(it, now / 1000, numbers, locale) } ?: unknown))
            Text(stringResource(R.string.sub_updated, subscription.lastUpdatedAt?.let {
                isolated(dates.format(Date(it)))
            } ?: stringResource(R.string.sub_never)), style = MaterialTheme.typography.bodySmall)
            if (!subscription.lastError.isNullOrBlank()) {
                // Fetch errors may contain token-bearing URLs; show only a safe, localized status.
                Text(stringResource(R.string.sub_failed), color = colors.error, style = MaterialTheme.typography.bodySmall)
            }
            if (usageKnown || total != null) Text(stringResource(R.string.sub_snapshot),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            // FlowRow keeps full labels and touch targets at large font sizes and in Persian.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sub_refresh)) }
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sub_edit)) }
                TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sub_delete)) }
            }
        }
    }
}

@Composable
private fun byteSize(bytes: Double, numbers: NumberFormat, locale: Locale): String {
    var amount = bytes
    var unit = 0
    while (amount >= 1024 && unit < 4) { amount /= 1024; unit++ }
    val resource = when (unit) {
        0 -> R.string.sub_bytes
        1 -> R.string.sub_kib
        2 -> R.string.sub_mib
        3 -> R.string.sub_gib
        else -> R.string.sub_tib
    }
    return stringResource(resource, BidiFormatter.getInstance(locale).unicodeWrap(numbers.format(amount), TextDirectionHeuristics.LTR))
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
    return stringResource(resource, BidiFormatter.getInstance(locale).unicodeWrap(numbers.format(count), TextDirectionHeuristics.LTR))
}

@Composable
private fun EditSubscriptionForm(
    subscription: SubscriptionEntity,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by rememberSaveable(subscription.id) { mutableStateOf(subscription.name) }
    var url by rememberSaveable(subscription.id) { mutableStateOf(subscription.url) }
    var attempted by rememberSaveable(subscription.id) { mutableStateOf(false) }
    val valid = name.isNotBlank() && validSubscriptionUrl(url.trim())
    // Keep the editor bounded and scrollable when the keyboard or large text reduces space.
        Surface(shape = MaterialTheme.shapes.large, color = LocalLuxyColors.current.surfaceContainer) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 560.dp)
                .padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.sub_edit_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = enabled,
                    label = { Text(stringResource(R.string.sub_name)) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, isError = attempted && name.isBlank())
                val urlLabel = stringResource(R.string.sub_url)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, enabled = enabled,
                        label = { Text(urlLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        isError = attempted && !validSubscriptionUrl(url.trim()))
                }
                if (attempted && !valid) Text(stringResource(R.string.sub_invalid), color = MaterialTheme.colorScheme.error)
                if (!enabled) Text(stringResource(R.string.sub_locked))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.sub_cancel)) }
                    TextButton(enabled = enabled, onClick = {
                        attempted = true
                        if (valid && enabled) onSave(name.trim(), url.trim())
                    }) { Text(stringResource(R.string.sub_save)) }
                }
            }
        }
}

private fun validSubscriptionUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank()
}.getOrDefault(false)
