package com.example.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.components.Formatters
import com.example.ui.theme.VazirmatnFamily
import kotlinx.coroutines.delay
import java.util.Locale

private val HomeBg = Color(0xFF07060A)
private val HomeCard = Color(0xFF141218)
private val HomeLine = Color(0xFF3C342C)
private val HomeAmber = Color(0xFFE8A04A)
private val HomeAmberSoft = Color(0xFF8A6230)
private val HomeText = Color(0xFFF6F1E8)
private val HomeMuted = Color(0xFF9A9388)

/**
 * Home surface. Layout, color and hierarchy follow the LuxyNet reference: one egress card,
 * globe, power control, server row, three shortcuts, and a three-item dock.
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
    val connected = telemetry.connectionState == ConnectionState.CONNECTED
    val busy = telemetry.connectionState == ConnectionState.CONNECTING ||
        telemetry.connectionState == ConnectionState.RECONNECTING ||
        telemetry.connectionState == ConnectionState.FAILOVER
    var egress by remember { mutableStateOf<PublicEgress?>(null) }
    var failure by remember { mutableStateOf<EgressFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showUsage by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val transport = remember(telemetry.networkTransport, telemetry.connectionState) {
        deviceTransport(context).ifBlank { telemetry.networkTransport }
    }

    LaunchedEffect(telemetry.connectionState) {
        egress = null
        failure = null
        loading = true
        if (connected) delay(800)
        val result = PublicEgressProbe().probe(throughTunnel = connected)
        egress = result.getOrNull()
        failure = result.exceptionOrNull()?.let { (it as? EgressException)?.failure ?: EgressFailure.UNAVAILABLE }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                loading = loading,
                egress = egress,
                failure = failure,
                transport = transport,
                language = language,
                lastError = telemetry.lastError
            )
            GlobeControl(
                connected = connected,
                busy = busy,
                onToggle = onToggleConnect
            )
            ServerCard(
                node = activeNode,
                count = serverCount,
                language = language,
                onClick = onOpenNodes
            )
            QuickRow(
                onSpeed = onOpenSpeedTest,
                onUsage = { showUsage = true },
                onTools = onOpenTools
            )
            HomeDock(
                onSettings = onOpenSettings,
                onServers = onOpenNodes
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showUsage) {
        UsageDialog(
            subscription = subscription,
            language = language,
            onRefresh = { sub -> onRefreshSubscription(sub) },
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
    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                tint = HomeAmber,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "LuxyNet",
                color = HomeText,
                fontFamily = VazirmatnFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
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
            .size(44.dp)
            .clip(CircleShape)
            .border(1.dp, HomeLine, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .padding(1.dp)
        )
        Icon(icon, contentDescription = description, tint = HomeText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EgressCard(
    connected: Boolean,
    busy: Boolean,
    loading: Boolean,
    egress: PublicEgress?,
    failure: EgressFailure?,
    transport: String,
    language: String?,
    lastError: String?
) {
    val title = when {
        busy -> tr("Connecting…")
        connected -> tr("Connected")
        telemetryError(lastError) -> tr("Error")
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
            .clip(RoundedCornerShape(22.dp))
            .background(HomeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(HomeAmber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = HomeAmber, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, color = HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EgressCell(Modifier.weight(1f), Icons.Rounded.Public, tr("IP"), value = when {
                loading -> "…"
                egress != null -> Digits.localize(egress.ip, language)
                else -> "—"
            }, ltr = true)
            Hairline()
            EgressCell(Modifier.weight(1f), Icons.Rounded.LocationOn, tr("Country"), value = countryLabel(egress, language), flag = egress?.countryCode)
            Hairline()
            EgressCell(Modifier.weight(1f), Icons.Rounded.Dns, tr("Provider"), value = egress?.provider?.ifBlank { null } ?: if (loading) "…" else "—")
            Hairline()
            EgressCell(Modifier.weight(1f), Icons.Rounded.Wifi, tr("Network"), value = tr(transportLabel(transport)))
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(36.dp)
            .background(HomeLine)
    )
}

@Composable
private fun EgressCell(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    ltr: Boolean = false,
    flag: String? = null
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = HomeMuted, modifier = Modifier.size(14.dp))
        Text(label, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 10.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!flag.isNullOrBlank()) CountryFlag(flag)
            Text(
                text = value,
                color = HomeText,
                fontFamily = if (ltr) androidx.compose.ui.text.font.FontFamily.Monospace else VazirmatnFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content
                )
            )
        }
    }
}

@Composable
private fun CountryFlag(code: String) {
    val normalized = code.uppercase()
    if (normalized == "IR") {
        Column(
            modifier = Modifier
                .size(width = 16.dp, height = 11.dp)
                .clip(RoundedCornerShape(2.dp))
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF239F40)))
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.White))
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFDA0000)))
        }
    } else {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 11.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(HomeAmberSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(normalized.take(2), color = HomeText, fontSize = 6.sp)
        }
    }
}

@Composable
private fun GlobeControl(connected: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val label = when {
        busy -> tr("Connecting…")
        connected -> tr("Disconnect")
        else -> tr("Connect")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.home_globe),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .border(3.dp, if (connected || busy) HomeAmber else HomeAmberSoft, CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF3A2412), Color(0xFF120E0C))
                    )
                )
                .clickable(onClick = onToggle)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                Text(label, color = Color.White, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
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
    val title = node?.name?.takeIf { it.isNotBlank() } ?: tr("Select server")
    val subtitle = if (node == null) {
        "${Digits.localize(count.toString(), language)} ${tr("servers")}"
    } else {
        node.groupName.takeIf { it.isNotBlank() && !it.equals("Local", true) } ?: tr("Selected")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HomeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HomeAmber.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = HomeAmber, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = HomeMuted, fontFamily = VazirmatnFamily, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = HomeMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun QuickRow(onSpeed: () -> Unit, onUsage: () -> Unit, onTools: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(HomeCard)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = HomeAmber, modifier = Modifier.size(22.dp))
        Text(label, color = HomeText, fontFamily = VazirmatnFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeDock(onSettings: () -> Unit, onServers: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(HomeCard)
            .padding(vertical = 8.dp),
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
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontFamily = VazirmatnFamily, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HomeCard,
        title = { Text(tr("Usage"), color = HomeText, fontFamily = VazirmatnFamily) },
        text = {
            if (subscription == null || info == null) {
                Text(tr("No subscription on this profile"), color = HomeMuted, fontFamily = VazirmatnFamily)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(subscription.profileTitle ?: subscription.name, color = HomeText, fontFamily = VazirmatnFamily, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${tr("Used")} ${Digits.localize(Formatters.formatBytes(info.usedBytes), language)}",
                        color = HomeMuted,
                        fontFamily = VazirmatnFamily
                    )
                    if (info.hasQuota) {
                        Text(
                            "${tr("Remaining")} ${Digits.localize(Formatters.formatBytes(info.remainingBytes), language)}",
                            color = HomeMuted,
                            fontFamily = VazirmatnFamily
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (subscription != null) {
                TextButton(onClick = { onRefresh(subscription) }) { Text(tr("Refresh"), color = HomeAmber) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close"), color = HomeMuted) } }
    )
}

private fun telemetryError(lastError: String?): Boolean = !lastError.isNullOrBlank()

private fun countryLabel(egress: PublicEgress?, language: String?): String {
    if (egress == null) return "—"
    if (egress.countryCode.length == 2) {
        val locale = Locale.forLanguageTag(language ?: "en")
        val name = Locale("", egress.countryCode).getDisplayCountry(locale)
        if (name.isNotBlank()) return name
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
