package com.example.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ServerCountryResolver
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.TunnelTelemetry
import com.example.data.model.VerificationState
import com.example.i18n.Digits
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Collapsible connection telemetry card for Home.
 *
 * The expanded body is composed only while [expanded]; the uptime ticker therefore never runs
 * (and never recomposes anything) while the card is collapsed. Every value is either real
 * telemetry or an explicit "unknown" label — never a fabricated placeholder IP.
 */
@Composable
fun ConnectionDetailsCard(
    telemetry: TunnelTelemetry,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit,
    language: String,
    locale: Locale,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.details_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant)
                ConnectionDetailsBody(
                    telemetry = telemetry,
                    expanded = expanded,
                    language = language,
                    locale = locale
                )
            }
        }
    }
}

@Composable
private fun ConnectionDetailsBody(
    telemetry: TunnelTelemetry,
    expanded: Boolean,
    language: String,
    locale: Locale
) {
    val colors = LocalLuxyColors.current
    val connected = telemetry.connectionState == ConnectionState.CONNECTED
    val checking = telemetry.verificationState == VerificationState.CHECKING

    // Live session timer: one tick per second, and only while the card is open on a live tunnel.
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(expanded, telemetry.connectionState, telemetry.connectedAtElapsedMs) {
        if (expanded && telemetry.connectionState == ConnectionState.CONNECTED && telemetry.connectedAtElapsedMs > 0L) {
            while (true) {
                nowElapsedMs = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
    }

    val uptimeText = when {
        !connected -> "—"
        telemetry.connectedAtElapsedMs <= 0L -> "00:00:00"
        else -> Digits.localize(
            formatUptime(nowElapsedMs - telemetry.connectedAtElapsedMs),
            language
        )
    }
    val downloadText = if (connected) {
        Digits.localize(formatRate(telemetry.rxBytesPerSec), language)
    } else {
        "—"
    }
    val uploadText = if (connected) {
        Digits.localize(formatRate(telemetry.txBytesPerSec), language)
    } else {
        "—"
    }
    val totalText = Digits.localize(
        "↓ ${formatByteSize(telemetry.rxBytes)}  ↑ ${formatByteSize(telemetry.txBytes)}",
        language
    )

    val exitIpText = when {
        telemetry.exitIp?.isNotBlank() == true -> telemetry.exitIp.orEmpty()
        checking -> stringResource(R.string.details_checking)
        else -> stringResource(R.string.details_unknown)
    }
    val exitCountryText = exitCountryLabel(telemetry, locale, checking)

    val statusLabel: String
    val statusTint: Color
    when {
        telemetry.verificationState == VerificationState.VERIFIED && telemetry.lockdownEnabled -> {
            statusLabel = stringResource(R.string.details_status_verified_lockdown)
            statusTint = colors.statusConnected
        }
        telemetry.verificationState == VerificationState.VERIFIED &&
            telemetry.dnsLeakProtected && telemetry.ipv6LeakProtected -> {
            statusLabel = stringResource(R.string.details_status_verified_protected)
            statusTint = colors.statusConnected
        }
        telemetry.verificationState == VerificationState.CHECKING -> {
            statusLabel = stringResource(R.string.details_checking)
            statusTint = colors.primary
        }
        telemetry.verificationState == VerificationState.PARTIAL -> {
            statusLabel = stringResource(R.string.details_status_partial)
            statusTint = colors.statusConnecting
        }
        telemetry.verificationState == VerificationState.FAILED -> {
            statusLabel = stringResource(R.string.details_status_failed)
            statusTint = colors.error
        }
        else -> {
            statusLabel = stringResource(R.string.details_status_tunnel_only)
            statusTint = colors.onSurfaceVariant
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailValueRow(
            label = stringResource(R.string.details_exit_ip),
            value = exitIpText
        )
        DetailValueRow(
            label = stringResource(R.string.details_exit_country),
            value = exitCountryText
        )
        DetailValueRow(
            label = stringResource(R.string.details_uptime),
            value = uptimeText
        )
        DetailValueRow(
            label = stringResource(R.string.details_download),
            value = downloadText
        )
        DetailValueRow(
            label = stringResource(R.string.details_upload),
            value = uploadText
        )
        DetailValueRow(
            label = stringResource(R.string.details_total),
            value = totalText
        )
        DetailValueRow(
            label = stringResource(R.string.details_status),
            value = statusLabel,
            valueColor = statusTint
        )
    }
}

@Composable
private fun DetailValueRow(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: colors.onSurface,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun exitCountryLabel(
    telemetry: TunnelTelemetry,
    locale: Locale,
    checking: Boolean
): String {
    val code = telemetry.exitCountryCode?.trim().orEmpty()
    if (code.isEmpty()) {
        return stringResource(
            if (checking) R.string.details_checking else R.string.details_unknown
        )
    }
    val option = ServerCountryResolver.optionForCode(code, locale)
    val base = if (option != null && option.name.isNotBlank()) {
        if (option.flag.isNotEmpty()) "${option.flag} ${option.name}" else option.name
    } else {
        code
    }
    return when (telemetry.exitCountryConfidence) {
        CountryConfidence.VERIFIED -> stringResource(R.string.details_country_verified, base)
        CountryConfidence.ESTIMATED -> stringResource(R.string.details_country_estimated, base)
        CountryConfidence.UNKNOWN -> base
    }
}

/**
 * Compact, honest failure card: the user-facing message is always shown, while the raw technical
 * detail stays behind a disclosure so stack traces and hostnames do not leak into the main UI.
 */
@Composable
fun ConnectionErrorBanner(
    errorMessage: String,
    technicalDetail: String?,
    modifier: Modifier = Modifier
) {
    if (errorMessage.isBlank() && technicalDetail.isNullOrBlank()) return
    val colors = LocalLuxyColors.current
    var showDetails by rememberSaveable(errorMessage, technicalDetail) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (errorMessage.isNotBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onErrorContainer
                )
            }
            if (!technicalDetail.isNullOrBlank()) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (showDetails) R.string.details_hide_details else R.string.details_error_details
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.error
                    )
                }
                if (showDetails) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(
                            text = technicalDetail,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFontFamily,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatUptime(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatRate(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L)
    return when {
        value < 1_024L -> "$value B/s"
        value < 1_024L * 1_024L -> String.format(Locale.US, "%.1f KB/s", value / 1_024.0)
        else -> String.format(Locale.US, "%.1f MB/s", value / (1_024.0 * 1_024.0))
    }
}

private fun formatByteSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value < 1_024L -> "$value B"
        value < 1_024L * 1_024L -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
        value < 1_024L * 1_024L * 1_024L -> String.format(Locale.US, "%.1f MB", value / (1_024.0 * 1_024.0))
        else -> String.format(Locale.US, "%.2f GB", value / (1_024.0 * 1_024.0 * 1_024.0))
    }
}
