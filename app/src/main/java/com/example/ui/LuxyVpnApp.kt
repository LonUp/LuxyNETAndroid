package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.CountryOption
import com.example.core.ServerCountryResolver
import com.example.data.model.ConnectionState
import com.example.i18n.Digits
import com.example.i18n.UiText
import com.example.ui.theme.LocalLuxyColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun LuxyVpnApp(
    viewModel: LuxyVpnViewModel,
    onRequestConnect: () -> Unit,
    onImportFile: () -> Unit,
    permissionPending: Boolean = false
) {
    // Traffic counters update in the service; this screen does not need to redraw for them.
    val stateFlow = remember(viewModel) {
        viewModel.telemetry.map { it.connectionState }.distinctUntilChanged()
    }
    val errorFlow = remember(viewModel) {
        viewModel.telemetry.map { it.lastError }.distinctUntilChanged()
    }
    val state by stateFlow.collectAsStateWithLifecycle(viewModel.telemetry.value.connectionState)
    val error by errorFlow.collectAsStateWithLifecycle(viewModel.telemetry.value.lastError)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedCountryCode by viewModel.selectedCountryCode.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val selecting by viewModel.isSelectingFastestServer.collectAsStateWithLifecycle()
    val notice by viewModel.uiNotice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val locale = LocalConfiguration.current.locales[0]
    val language = locale.language
    val emptyClipboard = stringResource(R.string.minimal_empty_clipboard)
    var showSubscriptions by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSubscriptions) { showSubscriptions = false }
    val availableCountries = remember(profiles, locale) {
        ServerCountryResolver.availableCountries(profiles, locale)
    }
    val selectedCountry = remember(selectedCountryCode, availableCountries, locale) {
        selectedCountryCode?.let { code ->
            availableCountries.firstOrNull { it.code == code }
                ?: ServerCountryResolver.optionForCode(code, locale)
        }
    }
    val selectedCountryAvailable = remember(selectedCountryCode, profiles) {
        selectedCountryCode == null || profiles.any { ServerCountryResolver.matches(it, selectedCountryCode) }
    }
    val onImportClipboard: () -> Unit = {
        // Read only in response to a tap, never when opening or resuming the app.
        val text = clipboard.getText()?.text.orEmpty()
        if (text.isBlank()) viewModel.showNotice(emptyClipboard)
        else viewModel.importText(text)
    }
    LaunchedEffect(notice, language) {
        val message = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(UiText.translate(language, message), duration = SnackbarDuration.Long)
        if (viewModel.uiNotice.value == message) viewModel.clearUiNotice()
    }
    LaunchedEffect(error, language) {
        val message = error ?: return@LaunchedEffect
        snackbar.showSnackbar(UiText.translate(language, message), duration = SnackbarDuration.Long)
    }

    if (showSubscriptions) {
        val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
        val standaloneServerCount = remember(profiles) { profiles.count { it.subscriptionId == null } }
        SubscriptionsPage(
            subscriptions = subscriptions,
            managementEnabled = state in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR) &&
                !selecting && !permissionPending,
            busy = importing,
            snackbar = snackbar,
            onHome = { showSubscriptions = false },
            onRefresh = viewModel::refreshSubscription,
            onSave = viewModel::saveSubscription,
            onDelete = viewModel::deleteSubscription,
            standaloneServerCount = standaloneServerCount,
            onImportClipboard = onImportClipboard,
            onImportFile = onImportFile,
            onAddSubscriptionUrl = viewModel::importSubscriptionUrl
        )
        return
    }

    MinimalHome(
        state = state,
        serverCount = profiles.size,
        importing = importing,
        selecting = selecting,
        permissionPending = permissionPending,
        snackbar = snackbar,
        onConnect = onRequestConnect,
        onSubscriptions = { showSubscriptions = true },
        selectedCountry = selectedCountry,
        availableCountries = availableCountries,
        selectedCountryAvailable = selectedCountryAvailable,
        onSelectCountry = viewModel::selectCountry,
        onConfirmCountrySwitch = viewModel::switchCountryAndReconnect
    )
}

/** One task per action: connect or manage subscriptions. No technical clutter. */
@Composable
internal fun MinimalHome(
    state: ConnectionState,
    serverCount: Int,
    importing: Boolean,
    selecting: Boolean,
    permissionPending: Boolean,
    snackbar: SnackbarHostState,
    onConnect: () -> Unit,
    onSubscriptions: (() -> Unit)? = null,
    selectedCountry: CountryOption? = null,
    availableCountries: List<CountryOption> = emptyList(),
    selectedCountryAvailable: Boolean = true,
    onSelectCountry: (String?) -> Unit = {},
    onConfirmCountrySwitch: (String?) -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    val idle = state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
    val isActiveOrConnecting = selecting || !idle
    val locationEnabled = !importing && !permissionPending && state != ConnectionState.DISCONNECTING
    val connectEnabled = !importing && !permissionPending &&
        state != ConnectionState.DISCONNECTING && (serverCount > 0 || !idle || selecting)
    var showLocationSheet by rememberSaveable { mutableStateOf(false) }
    var hasPendingSwitch by rememberSaveable { mutableStateOf(false) }
    var pendingSwitchCode by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(locationEnabled) {
        if (!locationEnabled) showLocationSheet = false
    }
    LaunchedEffect(isActiveOrConnecting) {
        if (!isActiveOrConnecting) {
            hasPendingSwitch = false
            pendingSwitchCode = null
        }
    }
    val action = stringResource(
        when {
            selecting -> R.string.minimal_cancel
            state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTING ->
                R.string.minimal_disconnect
            !idle && state != ConnectionState.DISCONNECTING -> R.string.minimal_cancel
            else -> R.string.minimal_connect
        }
    )
    val status = stringResource(
        when {
            importing -> R.string.minimal_importing
            permissionPending -> R.string.minimal_permission
            selecting -> R.string.minimal_selecting
            state == ConnectionState.CONNECTED -> R.string.minimal_connected
            state == ConnectionState.DISCONNECTING -> R.string.minimal_disconnecting
            !idle -> R.string.minimal_connecting
            state == ConnectionState.ERROR -> R.string.minimal_connection_failed
            else -> R.string.minimal_disconnected
        }
    )
    val statusColor = when {
        state == ConnectionState.CONNECTED || selecting || !idle -> colors.primary
        state == ConnectionState.ERROR -> colors.error
        else -> colors.onSurfaceVariant
    }
    val locale = LocalConfiguration.current.locales[0]
    val language = locale.language
    val ready = if (serverCount == 0) stringResource(R.string.minimal_import_hint) else {
        stringResource(R.string.minimal_ready, Digits.localize(serverCount.toString(), language))
    }
    val selectorLabel = selectedCountry?.let {
        if (it.flag.isNotEmpty()) "${it.flag} ${it.name}" else it.name
    } ?: stringResource(R.string.location_automatic)
    val selectorCd = stringResource(R.string.location_selector_cd, selectorLabel)
    val orbBrush = remember(colors.primary) {
        Brush.verticalGradient(
            colors = listOf(
                lerp(colors.primary, Color.White, 0.14f),
                colors.primary,
                lerp(colors.primary, Color.Black, 0.08f)
            )
        )
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            DottedGlobeBackdrop(
                dotColor = colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(220.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    if (onSubscriptions != null) {
                        TextButton(
                            onClick = onSubscriptions,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.sub_title),
                                color = colors.primary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 24.dp)
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = onConnect,
                        enabled = connectEnabled,
                        modifier = Modifier.size(148.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (connectEnabled) {
                                lerp(colors.primary, Color.White, 0.25f).copy(alpha = 0.35f)
                            } else {
                                colors.outlineVariant
                            }
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.onPrimary,
                            disabledContainerColor = colors.surfaceContainer,
                            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (connectEnabled) Modifier.background(orbBrush, CircleShape)
                                    else Modifier.background(colors.surfaceContainer, CircleShape)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = action,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = action,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground
                    )
                    Spacer(Modifier.height(18.dp))
                    if (serverCount > 0 || selectedCountry != null) {
                        Surface(
                            onClick = { if (locationEnabled) showLocationSheet = true },
                            enabled = locationEnabled,
                            shape = RoundedCornerShape(999.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outline),
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .heightIn(min = 44.dp)
                                .semantics { contentDescription = selectorCd }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (selectedCountry == null) {
                                    Icon(
                                        imageVector = Icons.Rounded.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (locationEnabled) colors.primary else colors.onSurfaceVariant
                                    )
                                } else if (selectedCountry.flag.isNotEmpty()) {
                                    Text(
                                        text = selectedCountry.flag,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = selectedCountry?.name ?: stringResource(R.string.location_automatic),
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (locationEnabled) colors.onSurface else colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.onSurfaceVariant
                                )
                            }
                        }
                        if (selectedCountry != null && !selectedCountryAvailable) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.location_no_servers),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.minimal_start),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (onSubscriptions != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = onSubscriptions,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.minimal_open_subscriptions),
                                    color = colors.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = colors.onSurfaceVariant
                    )
                    Text(
                        text = ready,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showLocationSheet) {
        CountrySelectionSheet(
            selectedCountryCode = selectedCountry?.code,
            availableCountries = availableCountries,
            onSelect = { targetCode ->
                showLocationSheet = false
                if (targetCode == selectedCountry?.code) return@CountrySelectionSheet
                if (isActiveOrConnecting) {
                    pendingSwitchCode = targetCode
                    hasPendingSwitch = true
                } else {
                    onSelectCountry(targetCode)
                }
            },
            onDismiss = { showLocationSheet = false }
        )
    }
    if (hasPendingSwitch) {
        val targetLabel = pendingSwitchCode?.let { code ->
            availableCountries.firstOrNull { it.code == code }?.let {
                if (it.flag.isNotEmpty()) "${it.flag} ${it.name}" else it.name
            } ?: ServerCountryResolver.displayLabel(code, locale)
        } ?: stringResource(R.string.location_automatic)
        AlertDialog(
            onDismissRequest = {
                hasPendingSwitch = false
                pendingSwitchCode = null
            },
            containerColor = colors.surface,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onSurfaceVariant,
            title = { Text(stringResource(R.string.location_switch_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.location_switch_message, targetLabel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingSwitchCode
                    hasPendingSwitch = false
                    pendingSwitchCode = null
                    onConfirmCountrySwitch(target)
                }) {
                    Text(stringResource(R.string.location_switch_confirm), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    hasPendingSwitch = false
                    pendingSwitchCode = null
                }) {
                    Text(stringResource(R.string.minimal_cancel), color = colors.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun DottedGlobeBackdrop(
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val radius = minOf(size.width * 0.62f, size.height * 1.22f)
        val centerX = size.width * 0.5f
        val centerY = size.height + radius * 0.24f
        val dotRadius = 1.35.dp.toPx()
        val points = GlobeDots.points
        var i = 0
        while (i < points.size) {
            val nx = points[i]
            val ny = points[i + 1]
            val alpha = points[i + 2]
            val px = centerX + nx * radius
            val py = centerY + ny * radius
            if (py in 0f..size.height && px in 0f..size.width) {
                val bottomFade = ((size.height - py) / (size.height * 0.32f)).coerceIn(0f, 1f)
                val finalAlpha = alpha * bottomFade
                if (finalAlpha > 0.01f) {
                    drawCircle(
                        color = dotColor.copy(alpha = finalAlpha),
                        radius = dotRadius,
                        center = Offset(px, py)
                    )
                }
            }
            i += 3
        }
    }
}

private object GlobeDots {
    val points: FloatArray = buildPoints()

    private fun buildPoints(): FloatArray {
        val out = ArrayList<Float>(1800)
        val tilt = 18.0 * PI / 180.0
        val cosTilt = cos(tilt)
        val sinTilt = sin(tilt)
        var latDeg = -12
        while (latDeg <= 80) {
            val lat = latDeg * PI / 180.0
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonStep = if (latDeg >= 60) 8 else 5
            var lonDeg = -85
            while (lonDeg <= 85) {
                val lon = lonDeg * PI / 180.0
                val x = cosLat * sin(lon)
                val y0 = sinLat
                val z0 = cosLat * cos(lon)
                val y = y0 * cosTilt - z0 * sinTilt
                val z = y0 * sinTilt + z0 * cosTilt
                if (z > 0.08 && y > -0.15) {
                    val land = isLand(latDeg.toDouble(), lonDeg.toDouble())
                    val limb = sqrt(z).toFloat()
                    val alpha = if (land) 0.20f * limb else 0.045f * limb
                    out.add(x.toFloat())
                    out.add((-y).toFloat())
                    out.add(alpha)
                }
                lonDeg += lonStep
            }
            latDeg += 4
        }
        return out.toFloatArray()
    }

    private fun isLand(lat: Double, lon: Double): Boolean {
        // Simplified continent blobs centered on Europe/Africa/Asia/Americas for subtle dot density.
        val europe = inEllipse(lat, lon, 50.0, 14.0, 14.0, 22.0)
        val africa = inEllipse(lat, lon, 10.0, 18.0, 24.0, 22.0)
        val westAsia = inEllipse(lat, lon, 32.0, 48.0, 16.0, 20.0)
        val eastAsia = inEllipse(lat, lon, 42.0, 78.0, 20.0, 24.0)
        val northAmerica = inEllipse(lat, lon, 44.0, -68.0, 18.0, 22.0)
        val southAmerica = inEllipse(lat, lon, -8.0, -58.0, 18.0, 14.0)
        val greenland = inEllipse(lat, lon, 70.0, -38.0, 8.0, 14.0)
        return europe || africa || westAsia || eastAsia || northAmerica || southAmerica || greenland
    }

    private fun inEllipse(lat: Double, lon: Double, cLat: Double, cLon: Double, rLat: Double, rLon: Double): Boolean {
        val dLat = (lat - cLat) / rLat
        val dLon = (lon - cLon) / rLon
        return dLat * dLat + dLon * dLon <= 1.0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountrySelectionSheet(
    selectedCountryCode: String?,
    availableCountries: List<CountryOption>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
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
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.location_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "automatic") {
                    LocationOptionRow(
                        selected = selectedCountryCode == null,
                        onClick = { onSelect(null) },
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(colors.accentSoft, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = colors.primary
                                )
                            }
                        },
                        title = stringResource(R.string.location_automatic),
                        subtitle = stringResource(R.string.location_recommended)
                    )
                }
                if (availableCountries.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(
                            color = colors.outlineVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    items(availableCountries, key = { it.code }) { country ->
                        LocationOptionRow(
                            selected = selectedCountryCode == country.code,
                            onClick = { onSelect(country.code) },
                            leading = {
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = country.flag,
                                        fontSize = 20.sp
                                    )
                                }
                            },
                            title = country.name,
                            subtitle = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationOptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?
) {
    val colors = LocalLuxyColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.accentSoft else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.location_selected_cd),
                    modifier = Modifier.size(20.dp),
                    tint = colors.primary
                )
            }
        }
    }
}
