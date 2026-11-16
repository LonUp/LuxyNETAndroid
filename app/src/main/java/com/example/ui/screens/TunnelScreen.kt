package com.example.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.EgressException
import com.example.core.EgressFailure
import com.example.core.PublicEgress
import com.example.core.PublicEgressProbe
import com.example.core.SubscriptionUserInfo
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ConnectionState
import com.example.data.model.StabilitySettings
import com.example.i18n.Digits
import com.example.i18n.tr
import com.example.ui.components.SessionStatsText
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalLuxyMetrics
import com.example.ui.theme.VazirmatnFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Home surface. One egress card, the globe with the connection control, the live session,
 * subscription quota, and the selected server. Session counters are the tunnel's, never the
 * subscription's. Every colour comes from the active palette so Home cannot drift from the
 * rest of the app.
 */
@Composable
fun TunnelScreen(
    telemetry: com.example.data.model.TunnelTelemetry,
    activeNode: ServerProfileEntity?,
    settings: StabilitySettings,
    onToggleConnect: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenExpertLab: () -> Unit,
    subscription: SubscriptionEntity?,
    serverCount: Int = 0,
    onSelectFastestServer: () -> Unit = {},
    isSelectingFastestServer: Boolean = false,
    onOpenTools: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenImport: () -> Unit = {}
) {
    val language = LocalConfiguration.current.locales.let { if (it.size() > 0) it.get(0)?.language else null }
    val context = LocalContext.current
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val state = telemetry.connectionState
    val connected = state == ConnectionState.CONNECTED
    val disconnecting = state == ConnectionState.DISCONNECTING
    val busy = state == ConnectionState.CONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.FAILOVER
    val failed = state == ConnectionState.ERROR
    var egress by remember { mutableStateOf<PublicEgress?>(null) }
    var failure by remember { mutableStateOf<EgressFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    val transport = remember(telemetry.networkTransport, state) {
        deviceTransport(context).ifBlank { telemetry.networkTransport }
    }

    LaunchedEffect(state) {
        egress = null
        failure = null
        loading = true
        if (connected) delay(800)
        if (!isActive) return@LaunchedEffect
        val result = PublicEgressProbe().probe(throughTunnel = connected || disconnecting)
        if (!isActive) return@LaunchedEffect
        egress = result.getOrNull()
        failure = result.exceptionOrNull()?.let { (it as? EgressException)?.failure ?: EgressFailure.UNAVAILABLE }
        loading = false
    }

    // Text inherits the Material body line height (24 sp) even when only fontSize is
    // overridden, which inflated every 10 sp label into a 26 dp line box. Home sets its own
    // sizes, so it uses the font's natural metrics instead.
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keeps the palette's own aurora as a whisper so Home reads charcoal, not teal.
                .background(colors.background.copy(alpha = 0.62f))
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.gutter),
            verticalArrangement = Arrangement.spacedBy(metrics.cardGap)
        ) {
            Spacer(Modifier.height(2.dp))
            HomeHeader(
                experimental = settings.experimentalModeEnabled,
                menuOpen = showMenu,
                onMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onTune = onOpenSettings,
                onImport = onOpenImport,
                onAppearance = onOpenAppearance,
                onLogs = onOpenLogs,
                onExpert = onOpenExpertLab
            )
            EgressCard(
                connected = connected,
                disconnecting = disconnecting,
                busy = busy,
                failed = failed,
                loading = loading,
                egress = egress,
                failure = failure,
                transport = transport,
                language = language
            )
            GlobeControl(
                connected = connected,
                disconnecting = disconnecting,
                busy = busy,
                failed = failed,
                connectedAtElapsedMs = telemetry.connectedAtElapsedMs,
                frozenDurationMs = if (connected || busy || disconnecting) 0L else telemetry.sessionDurationMs,
                language = language,
                onToggle = onToggleConnect
            )
            SessionStats(
                live = connected,
                rxBytes = telemetry.rxBytes,
                txBytes = telemetry.txBytes,
                rxPerSec = if (connected) telemetry.rxBytesPerSec else 0L,
                txPerSec = if (connected) telemetry.txBytesPerSec else 0L,
                language = language
            )
            SubscriptionStrip(subscription = subscription, language = language)
            ServerCard(node = activeNode, count = serverCount, language = language, onClick = onOpenNodes)
            QuickRow(
                onSpeed = onOpenSpeedTest,
                onFastest = onSelectFastestServer,
                fastestRunning = isSelectingFastestServer,
                fastestEnabled = !busy && !disconnecting,
                onTools = onOpenTools
            )
            Spacer(Modifier.height(6.dp))
        }

    }
}

@Composable
private fun HomeHeader(
    experimental: Boolean,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onTune: () -> Unit,
    onImport: () -> Unit,
    onAppearance: () -> Unit,
    onLogs: () -> Unit,
    onExpert: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            HeaderButton(Icons.Rounded.MoreVert, tr("More"), onMenu)
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text(tr("Import")) }, onClick = { onDismissMenu(); onImport() })
                DropdownMenuItem(text = { Text(tr("Appearance")) }, onClick = { onDismissMenu(); onAppearance() })
                DropdownMenuItem(text = { Text(tr("Event log")) }, onClick = { onDismissMenu(); onLogs() })
                if (experimental) {
                    DropdownMenuItem(text = { Text(tr("Expert Lab")) }, onClick = { onDismissMenu(); onExpert() })
                }
            }
        }
        Text(
            text = "LuxyNet",
            modifier = Modifier.weight(1f),
            color = colors.onBackground,
            fontFamily = VazirmatnFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            letterSpacing = 0.2.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        HeaderButton(Icons.Rounded.Tune, tr("Settings"), onTune)
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = LocalLuxyColors.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(1.dp, colors.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(18.dp))
    }
}

/**
 * The one observed-egress card: the public address this device is actually using, whichever
 * side of the tunnel it is on. A failed or loading probe never keeps a stale address.
 */
@Composable
private fun EgressCard(
    connected: Boolean,
    disconnecting: Boolean,
    busy: Boolean,
    failed: Boolean,
    loading: Boolean,
    egress: PublicEgress?,
    failure: EgressFailure?,
    transport: String,
    language: String?
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val shape = RoundedCornerShape(metrics.radiusMd)
    val status = when {
        disconnecting -> tr("Disconnecting")
        busy -> tr("Connecting")
        connected -> tr("Connected")
        failed -> tr("Error")
        else -> tr("Disconnected")
    }
    val dot = when {
        failed -> colors.statusError
        disconnecting -> colors.primary
        connected -> colors.statusConnected
        busy -> colors.primary
        else -> colors.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val country = if (egress == null) "" else countryLabel(egress, language)
    val provider = egress?.provider?.trim().orEmpty()
    val providerText = when {
        provider.isNotEmpty() -> provider
        loading -> "…"
        failure == EgressFailure.TIMEOUT -> tr("Timed out")
        failure == EgressFailure.UNAVAILABLE -> tr("Unavailable")
        else -> "—"
    }
    val ip = when {
        loading -> "…"
        egress != null -> SessionStatsText.address(egress.ip, language)
        else -> "—"
    }
    val network = tr(transportLabel(transport))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
            Text(
                status,
                color = if (failed) colors.statusError else colors.onBackground,
                fontFamily = VazirmatnFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                network,
                color = colors.onSurfaceVariant,
                fontFamily = VazirmatnFamily,
                fontSize = 10.5.sp,
                maxLines = 1
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = ip,
                color = colors.onBackground,
                fontFamily = VazirmatnFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(textDirection = TextDirection.Ltr)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = providerText,
                modifier = Modifier.weight(1f),
                color = colors.onSurfaceVariant,
                fontFamily = VazirmatnFamily,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (country.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                HomeCountryFlag(egress?.countryCode.orEmpty(), width = 20.dp, height = 13.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = country,
                    color = colors.onSurfaceVariant,
                    fontFamily = VazirmatnFamily,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Globe plus the single connection control and its session clock. The raster globe is gone:
 * the vector has no plate, no baked button, and no limb ring, so the only circle on screen is
 * the button the user actually presses.
 */
@Composable
private fun GlobeControl(
    connected: Boolean,
    disconnecting: Boolean,
    busy: Boolean,
    failed: Boolean,
    connectedAtElapsedMs: Long,
    frozenDurationMs: Long,
    language: String?,
    onToggle: () -> Unit
) {
    val label = when {
        busy -> tr("Connecting")
        disconnecting -> tr("Disconnecting")
        connected -> tr("Disconnect")
        else -> tr("Connect")
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val globe = (maxWidth * 0.72f).coerceIn(196.dp, 268.dp)
        val button = (globe * 0.42f).coerceIn(96.dp, 112.dp)
        Box(
            modifier = Modifier.fillMaxWidth().height(globe),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.home_globe),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(globe)
            )
            PowerButton(
                size = button,
                label = label,
                connected = connected,
                disconnecting = disconnecting,
                busy = busy,
                failed = failed,
                onToggle = onToggle
            )
            SessionClock(
                modifier = Modifier.align(Alignment.Center).offset(y = button / 2 + 9.dp),
                running = (connected || disconnecting) && connectedAtElapsedMs > 0L,
                connectedAtElapsedMs = connectedAtElapsedMs,
                frozenMs = frozenDurationMs,
                language = language
            )
        }
    }
}

@Composable
private fun PowerButton(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    connected: Boolean,
    disconnecting: Boolean,
    busy: Boolean,
    failed: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val ring = when {
        failed -> colors.statusError
        connected || disconnecting || busy -> colors.primary
        else -> colors.primary.copy(alpha = 0.38f)
    }
    if (busy) {
        PulsingPowerButton(size, label, ring, onToggle)
    } else {
        PowerFace(size, label, ring, 1f, enabled = !disconnecting, onToggle = onToggle)
    }
}

@Composable
private fun PulsingPowerButton(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    ring: Color,
    onToggle: () -> Unit
) {
    val alpha by rememberInfiniteTransition(label = "connect").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring"
    )
    PowerFace(size, label, ring, alpha, enabled = true, onToggle = onToggle)
}

@Composable
private fun PowerFace(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    ring: Color,
    alpha: Float,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, ring.copy(alpha = ring.alpha * alpha), CircleShape)
            .background(Brush.radialGradient(listOf(colors.orbInner, colors.orbOuter)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = colors.onBackground,
                fontFamily = VazirmatnFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SessionClock(
    modifier: Modifier,
    running: Boolean,
    connectedAtElapsedMs: Long,
    frozenMs: Long,
    language: String?
) {
    val colors = LocalLuxyColors.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running, connectedAtElapsedMs) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val elapsed = if (running) (now - connectedAtElapsedMs).coerceAtLeast(0L) else frozenMs.coerceAtLeast(0L)
    Text(
        text = SessionStatsText.duration(elapsed, language),
        modifier = modifier,
        color = if (running) colors.primary else colors.onSurfaceVariant,
        fontFamily = VazirmatnFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp
    )
}

/**
 * Four tunnel measurements in two rows: instantaneous speed above, session totals below.
 * Labels name each metric, so no reader has to infer a number from an arrow alone.
 */
@Composable
private fun SessionStats(
    live: Boolean,
    rxBytes: Long,
    txBytes: Long,
    rxPerSec: Long,
    txPerSec: Long,
    language: String?
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val shape = RoundedCornerShape(metrics.radiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            tr("This connection"),
            color = colors.onSurfaceVariant,
            fontFamily = VazirmatnFamily,
            fontSize = 10.sp
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                Modifier.weight(1f),
                Icons.Rounded.ArrowDownward,
                tr("Download speed"),
                SessionStatsText.rateParts(rxPerSec, language),
                live = live,
                strong = false
            )
            StatCell(
                Modifier.weight(1f),
                Icons.Rounded.ArrowUpward,
                tr("Upload speed"),
                SessionStatsText.rateParts(txPerSec, language),
                live = live,
                strong = false
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                Modifier.weight(1f),
                Icons.Rounded.ArrowDownward,
                tr("Total download"),
                SessionStatsText.volumeParts(rxBytes, language),
                live = live,
                strong = true
            )
            StatCell(
                Modifier.weight(1f),
                Icons.Rounded.ArrowUpward,
                tr("Total upload"),
                SessionStatsText.volumeParts(txBytes, language),
                live = live,
                strong = true
            )
        }
    }
}

@Composable
private fun StatCell(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    amount: SessionStatsText.Amount,
    live: Boolean,
    strong: Boolean
) {
    val colors = LocalLuxyColors.current
    val valueColor = when {
        strong || live -> colors.onBackground
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = modifier.semantics { contentDescription = "$label ${amount.number} ${amount.unit}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (live) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp)
        )
        Column {
            Text(
                text = label,
                color = colors.onSurfaceVariant,
                fontFamily = VazirmatnFamily,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(
                    text = amount.number,
                    modifier = Modifier.alignByBaseline(),
                    color = valueColor,
                    fontFamily = VazirmatnFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = amount.unit,
                    modifier = Modifier.alignByBaseline(),
                    color = colors.onSurfaceVariant,
                    fontFamily = VazirmatnFamily,
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SubscriptionStrip(subscription: SubscriptionEntity?, language: String?) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val info = subscription?.let {
        SubscriptionUserInfo(it.uploadBytes, it.downloadBytes, it.totalBytes, it.expireAtEpochSec)
    } ?: return
    if (!info.hasQuota && !info.hasExpiry && info.usedBytes <= 0L) return
    var nowEpochSec by remember(info.expireAtEpochSec) {
        mutableLongStateOf(System.currentTimeMillis() / 1000L)
    }
    LaunchedEffect(info.expireAtEpochSec) {
        if (!info.hasExpiry) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis() / 1000L
            delay((60L - now % 60L) * 1000L)
            nowEpochSec = System.currentTimeMillis() / 1000L
        }
    }
    val timeLeft = info.remainingTime(nowEpochSec)
    val expiry = when {
        !info.hasExpiry -> ""
        info.isExpired(nowEpochSec) -> tr("Expired")
        timeLeft.days > 0L && timeLeft.hours > 0 ->
            "${Digits.localize(timeLeft.days.toString(), language)} ${tr(if (timeLeft.days == 1L) "day" else "days")} ${Digits.localize(timeLeft.hours.toString(), language)} ${tr(if (timeLeft.hours == 1) "hour" else "hours")} ${tr("left")}"
        timeLeft.days > 0L ->
            "${Digits.localize(timeLeft.days.toString(), language)} ${tr(if (timeLeft.days == 1L) "day" else "days")} ${tr("left")}"
        timeLeft.hours > 0 && timeLeft.minutes > 0 ->
            "${Digits.localize(timeLeft.hours.toString(), language)} ${tr(if (timeLeft.hours == 1) "hour" else "hours")} ${Digits.localize(timeLeft.minutes.toString(), language)} ${tr(if (timeLeft.minutes == 1) "minute" else "minutes")} ${tr("left")}"
        timeLeft.hours > 0 ->
            "${Digits.localize(timeLeft.hours.toString(), language)} ${tr(if (timeLeft.hours == 1) "hour" else "hours")} ${tr("left")}"
        else ->
            "${Digits.localize(timeLeft.minutes.coerceAtLeast(1).toString(), language)} ${tr(if (timeLeft.minutes <= 1) "minute" else "minutes")} ${tr("left")}"
    }
    val shape = RoundedCornerShape(metrics.radiusMd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Subscription"),
                modifier = Modifier.weight(1f),
                color = colors.onSurfaceVariant,
                fontFamily = VazirmatnFamily,
                fontSize = 10.sp
            )
            if (expiry.isNotBlank()) {
                Text(expiry, color = colors.onSurfaceVariant, fontFamily = VazirmatnFamily, fontSize = 10.sp)
            }
        }
        if (info.hasQuota) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${tr("Used")} ${SessionStatsText.volume(info.usedBytes, language)} / ${SessionStatsText.volume(info.totalBytes, language)}",
                    modifier = Modifier.weight(1f),
                    color = colors.onBackground,
                    fontFamily = VazirmatnFamily,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${tr("Remaining")} ${SessionStatsText.volume(info.remainingBytes, language)}",
                    color = colors.onSurfaceVariant,
                    fontFamily = VazirmatnFamily,
                    fontSize = 10.5.sp,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.line2)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(info.usageFraction)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                )
            }
        } else if (info.usedBytes > 0L) {
            Text(
                "${tr("Used")} ${SessionStatsText.volume(info.usedBytes, language)}",
                color = colors.onBackground,
                fontFamily = VazirmatnFamily,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ServerCard(
    node: ServerProfileEntity?,
    count: Int,
    language: String?,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val shape = RoundedCornerShape(metrics.radiusMd)
    val identity = node?.let { ServerIdentity.resolve(it.name, it.groupName, language) }
    val title = identity?.title?.takeIf { it.isNotBlank() } ?: node?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: tr("Select server")
    val country = identity?.countryName.orEmpty()
    val subtitle = when {
        node == null -> "${Digits.localize(count.toString(), language)} ${tr("servers")}"
        country.isNotBlank() && !title.contains(country, ignoreCase = true) -> country
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val code = identity?.countryCode
        if (code != null && FlagArt.canDraw(code)) {
            HomeCountryFlag(code, width = 22.dp, height = 15.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.onBackground,
                fontFamily = VazirmatnFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = colors.onSurfaceVariant,
                    fontFamily = VazirmatnFamily,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun QuickRow(
    onSpeed: () -> Unit,
    onFastest: () -> Unit,
    fastestRunning: Boolean,
    fastestEnabled: Boolean,
    onTools: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickCard(Modifier.weight(1f), Icons.Rounded.Speed, tr("Speed Test"), onSpeed)
        QuickCard(
            Modifier.weight(1f),
            Icons.Rounded.NetworkPing,
            tr(if (fastestRunning) "Finding fastest server…" else "Fastest server"),
            onFastest,
            enabled = fastestEnabled && !fastestRunning
        )
        QuickCard(Modifier.weight(1f), Icons.Rounded.Tune, tr("Tools"), onTools)
    }
}

@Composable
private fun QuickCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val colors = LocalLuxyColors.current
    val shape = RoundedCornerShape(LocalLuxyMetrics.current.radiusSm)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surface)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = colors.onBackground,
            fontFamily = VazirmatnFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


private fun countryLabel(egress: PublicEgress, language: String?): String {
    if (egress.countryCode.length == 2) {
        val locale = Locale.forLanguageTag(language ?: "en")
        val name = Locale("", egress.countryCode).getDisplayCountry(locale)
        if (name.isNotBlank() && !name.equals(egress.countryCode, true)) return name
    }
    return egress.countryName
}

private fun transportLabel(raw: String): String = when {
    raw.contains("wi", ignoreCase = true) -> "Wi-Fi"
    raw.contains("cell", ignoreCase = true) || raw.contains("lte", ignoreCase = true) || raw.contains("5g", ignoreCase = true) -> "Mobile"
    raw.contains("eth", ignoreCase = true) -> "Ethernet"
    raw.isBlank() -> "Network"
    else -> raw
}

private fun deviceTransport(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ""
    val networks = cm.allNetworks
    var wifi = false
    var cell = false
    var ethernet = false
    for (network in networks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true
    }
    return when {
        wifi -> "Wi-Fi"
        cell -> "Mobile"
        ethernet -> "Ethernet"
        else -> ""
    }
}
