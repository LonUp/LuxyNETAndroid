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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import com.example.ui.theme.VazirmatnFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private val HomeBg = Color(0xFF07060A)
private val HomeCard = Color(0xFF121014)
private val HomeLine = Color(0xFF2E2924)
private val HomeAmber = Color(0xFFE0A15A)
private val HomeAmberSoft = Color(0xFF6E5330)
private val HomeText = Color(0xFFF4EFE6)
private val HomeMuted = Color(0xFF9A9186)
private val HomeDanger = Color(0xFFC46A55)
private val CardShape = RoundedCornerShape(16.dp)

/**
 * Home surface. One egress card, the globe, the live session, subscription quota, and the
 * selected server. Session counters are the tunnel's, never the subscription's.
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
    onRefreshSubscription: (SubscriptionEntity) -> Unit,
    serverCount: Int = 0,
    onOpenTools: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenImport: () -> Unit = {}
) {
    val language = LocalConfiguration.current.locales.let { if (it.size() > 0) it.get(0)?.language else null }
    val context = LocalContext.current
    val state = telemetry.connectionState
    val connected = state == ConnectionState.CONNECTED
    val busy = state == ConnectionState.CONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.FAILOVER
    val failed = state == ConnectionState.ERROR
    var egress by remember { mutableStateOf<PublicEgress?>(null) }
    var failure by remember { mutableStateOf<EgressFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showUsage by remember { mutableStateOf(false) }
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
        val result = PublicEgressProbe().probe(throughTunnel = connected)
        if (!isActive) return@LaunchedEffect
        egress = result.getOrNull()
        failure = result.exceptionOrNull()?.let { (it as? EgressException)?.failure ?: EgressFailure.UNAVAILABLE }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
            busy = busy,
            failed = failed,
            connectedAtElapsedMs = telemetry.connectedAtElapsedMs,
            frozenDurationMs = if (connected || busy) 0L else telemetry.sessionDurationMs,
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
        QuickRow(onSpeed = onOpenSpeedTest, onUsage = { showUsage = true }, onTools = onOpenTools)
        HomeDock(onSettings = onOpenSettings, onServers = onOpenNodes)
        Spacer(Modifier.height(4.dp))
    }

    if (showUsage) {
        UsageDialog(
            subscription = subscription,
            language = language,
            onRefresh = onRefreshSubscription,
            onDismiss = { showUsage = false }
        )
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
    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "LuxyNet",
            color = HomeText,
            fontFamily = VazirmatnFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
            HeaderButton(Icons.Rounded.Tune, tr("Settings"), onTune)
        }
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, HomeLine, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = HomeText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EgressCard(
    connected: Boolean,
    busy: Boolean,
    failed: Boolean,
    loading: Boolean,
    egress: PublicEgress?,
    failure: EgressFailure?,
    transport: String,
    language: String?
) {
    val title = when {
        busy -> tr("Connecting")
        connected -> tr("Connected")
        failed -> tr("Error")
        else -> tr("Disconnected")
    }
    val subtitle = when {
        loading -> tr("Checking…")
        failure == EgressFailure.TIMEOUT -> tr("Timed out")
        failure == EgressFailure.UNAVAILABLE -> tr("Unavailable")
        connected -> tr("Through the tunnel")
        else -> tr("Your current connection")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HomeCard)
            .border(1.dp, HomeLine, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = if (failed) HomeDanger else HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EgressCell(
                Modifier.weight(1.3f),
                tr("IP"),
                value = when {
                    loading -> "…"
                    egress != null -> SessionStatsText.address(egress.ip, language)
                    else -> "—"
                },
                ltr = true
            )
            EgressCell(
                Modifier.weight(1f),
                tr("Country"),
                value = countryLabel(egress, language),
                flag = egress?.countryCode
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EgressCell(
                Modifier.weight(1.3f),
                tr("Provider"),
                value = egress?.provider?.ifBlank { null } ?: if (loading) "…" else "—",
                maxLines = 2
            )
            EgressCell(Modifier.weight(1f), tr("Network"), value = tr(transportLabel(transport)))
        }
    }
}

@Composable
private fun EgressCell(
    modifier: Modifier,
    label: String,
    value: String,
    ltr: Boolean = false,
    flag: String? = null,
    maxLines: Int = 1
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 10.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!flag.isNullOrBlank()) HomeCountryFlag(flag)
            if (ltr) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = value,
                        color = HomeText,
                        fontFamily = VazirmatnFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textDirection = TextDirection.Ltr)
                    )
                }
            } else {
                Text(
                    text = value,
                    color = HomeText,
                    fontFamily = VazirmatnFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GlobeControl(
    connected: Boolean,
    busy: Boolean,
    failed: Boolean,
    connectedAtElapsedMs: Long,
    frozenDurationMs: Long,
    language: String?,
    onToggle: () -> Unit
) {
    val label = when {
        busy -> tr("Connecting")
        connected -> tr("Disconnect")
        else -> tr("Connect")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val globeHeight = maxWidth * (500f / 780f)
            val button = (maxWidth * 0.32f).coerceIn(96.dp, 124.dp)
            Box(
                modifier = Modifier.fillMaxWidth().height(globeHeight),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.home_globe),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                PowerButton(
                    size = button,
                    label = label,
                    connected = connected,
                    busy = busy,
                    failed = failed,
                    onToggle = onToggle
                )
            }
        }
        SessionClock(
            running = connected && connectedAtElapsedMs > 0L,
            connectedAtElapsedMs = connectedAtElapsedMs,
            frozenMs = frozenDurationMs,
            language = language
        )
    }
}

@Composable
private fun PowerButton(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    connected: Boolean,
    busy: Boolean,
    failed: Boolean,
    onToggle: () -> Unit
) {
    val ring = when {
        failed -> HomeDanger
        connected || busy -> HomeAmber
        else -> HomeAmberSoft
    }
    if (busy) {
        PulsingPowerButton(size, label, ring, onToggle)
    } else {
        PowerFace(size, label, ring, 1f, onToggle)
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
    PowerFace(size, label, ring, alpha, onToggle)
}

@Composable
private fun PowerFace(
    size: androidx.compose.ui.unit.Dp,
    label: String,
    ring: Color,
    alpha: Float,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, ring.copy(alpha = alpha), CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF2A1C12), Color(0xFF100E0C))))
            .clickable(onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color.White, fontFamily = VazirmatnFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SessionClock(
    running: Boolean,
    connectedAtElapsedMs: Long,
    frozenMs: Long,
    language: String?
) {
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
        color = if (running) HomeAmber else HomeMuted,
        fontFamily = VazirmatnFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    )
}

@Composable
private fun SessionStats(
    live: Boolean,
    rxBytes: Long,
    txBytes: Long,
    rxPerSec: Long,
    txPerSec: Long,
    language: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, HomeLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(tr("This connection"), color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 10.sp)
        Row(modifier = Modifier.fillMaxWidth()) {
            StatPair(Modifier.weight(1f), Icons.Rounded.ArrowDownward, tr("Download"), SessionStatsText.rate(rxPerSec, language), live)
            StatPair(Modifier.weight(1f), Icons.Rounded.ArrowUpward, tr("Upload"), SessionStatsText.rate(txPerSec, language), live)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatPair(Modifier.weight(1f), Icons.Rounded.ArrowDownward, tr("Download"), SessionStatsText.volume(rxBytes, language), emphasized = true)
            StatPair(Modifier.weight(1f), Icons.Rounded.ArrowUpward, tr("Upload"), SessionStatsText.volume(txBytes, language), emphasized = true)
        }
    }
}

@Composable
private fun StatPair(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    live: Boolean = true,
    emphasized: Boolean = false
) {
    Row(
        modifier = modifier.semantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (live) HomeAmber else HomeMuted, modifier = Modifier.size(14.dp))
        Text(
            text = value,
            color = if (emphasized || live) HomeText else HomeMuted,
            fontFamily = VazirmatnFamily,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            fontSize = if (emphasized) 13.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubscriptionStrip(subscription: SubscriptionEntity?, language: String?) {
    val info = subscription?.let {
        SubscriptionUserInfo(it.uploadBytes, it.downloadBytes, it.totalBytes, it.expireAtEpochSec)
    } ?: return
    if (!info.hasQuota && !info.hasExpiry && info.usedBytes <= 0L) return
    val expiry = when {
        !info.hasExpiry -> ""
        info.isExpired() -> tr("Expired")
        else -> "${Digits.localize(info.daysLeft().toString(), language)} ${tr("days left")}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HomeCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("Subscription"), color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 10.sp)
            if (expiry.isNotBlank()) {
                Text(expiry, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 10.sp)
            }
        }
        if (info.hasQuota) {
            Text(
                "${tr("Used")} ${SessionStatsText.volume(info.usedBytes, language)}  ·  ${tr("Remaining")} ${SessionStatsText.volume(info.remainingBytes, language)}",
                color = HomeText,
                fontFamily = VazirmatnFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { info.usageFraction },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = HomeAmber,
                trackColor = HomeLine
            )
        } else if (info.usedBytes > 0L) {
            Text(
                "${tr("Used")} ${SessionStatsText.volume(info.usedBytes, language)}",
                color = HomeText,
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
    val identity = node?.let { ServerIdentity.resolve(it.name, it.groupName, language) }
    val title = identity?.title ?: tr("Select server")
    val country = identity?.countryName?.takeIf { it.isNotBlank() && !it.equals(title, true) }.orEmpty()
    val subtitle = when {
        node == null -> "${Digits.localize(count.toString(), language)} ${tr("servers")}"
        country.isNotBlank() -> country
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HomeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (identity?.countryCode != null && FlagArt.canDraw(identity.countryCode)) {
            HomeCountryFlag(identity.countryCode, Modifier.size(width = 22.dp, height = 15.dp))
        } else {
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(HomeAmber.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = HomeAmber, modifier = Modifier.size(16.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = HomeMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun QuickRow(onSpeed: () -> Unit, onUsage: () -> Unit, onTools: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickCard(Modifier.weight(1f), Icons.Rounded.Speed, tr("Speed Test"), onSpeed)
        QuickCard(Modifier.weight(1f), Icons.Rounded.BarChart, tr("Usage"), onUsage)
        QuickCard(Modifier.weight(1f), Icons.Rounded.Tune, tr("Tools"), onTools)
    }
}

@Composable
private fun QuickCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HomeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = HomeAmber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = HomeText, fontFamily = VazirmatnFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeDock(onSettings: () -> Unit, onServers: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HomeCard)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockTab(Modifier.weight(1f), Icons.Rounded.Home, tr("Home"), selected = true, onClick = {})
        DockTab(Modifier.weight(1f), Icons.Rounded.Dns, tr("Servers"), selected = false, onClick = onServers)
        DockTab(Modifier.weight(1f), Icons.Rounded.Settings, tr("Settings"), selected = false, onClick = onSettings)
    }
}

@Composable
private fun DockTab(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) HomeAmber else HomeMuted
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontFamily = VazirmatnFamily, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun UsageDialog(
    subscription: SubscriptionEntity?,
    language: String?,
    onRefresh: (SubscriptionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val info = subscription?.let {
        SubscriptionUserInfo(it.uploadBytes, it.downloadBytes, it.totalBytes, it.expireAtEpochSec)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HomeCard,
        title = { Text(tr("Usage"), color = HomeText, fontFamily = VazirmatnFamily) },
        text = {
            if (subscription == null || info == null) {
                Text(tr("No subscription on this profile"), color = HomeMuted, fontFamily = VazirmatnFamily)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(subscription.profileTitle ?: subscription.name, color = HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold)
                    Text("${tr("Used")} ${SessionStatsText.volume(info.usedBytes, language)}", color = HomeMuted, fontFamily = VazirmatnFamily)
                    if (info.hasQuota) {
                        Text("${tr("Remaining")} ${SessionStatsText.volume(info.remainingBytes, language)}", color = HomeMuted, fontFamily = VazirmatnFamily)
                    }
                }
            }
        },
        confirmButton = {
            if (subscription != null) {
                androidx.compose.material3.TextButton(onClick = { onRefresh(subscription) }) {
                    Text(tr("Refresh"), color = HomeAmber)
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Close"), color = HomeMuted) }
        }
    )
}

private fun countryLabel(egress: PublicEgress?, language: String?): String {
    if (egress == null) return "—"
    if (egress.countryCode.length == 2) {
        val locale = Locale.forLanguageTag(language ?: "en")
        val name = Locale("", egress.countryCode).getDisplayCountry(locale)
        if (name.isNotBlank() && !name.equals(egress.countryCode, true)) return name
    }
    return egress.countryName.ifBlank { "—" }
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
