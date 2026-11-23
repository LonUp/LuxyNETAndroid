package com.example.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ServerCountryResolver
import com.example.data.model.ConnectionState
import com.example.data.model.TunnelTelemetry
import com.example.data.model.VerificationState
import com.example.i18n.BidiText
import com.example.i18n.DataSizeFormat
import com.example.i18n.UiText
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalThemeConfig
import com.example.ui.theme.MonoFontFamily
import java.util.Locale

/**
 * Collapsible connection telemetry card for Home.
 *
 * The expanded body contains only the exit IP and total traffic. Missing IP is explicit rather
 * than replaced with a fabricated address.
 */
@Composable
fun ConnectionDetailsCard(
    telemetry: TunnelTelemetry,
    onToggleExpanded: () -> Unit,
    language: String,
    locale: Locale,
    modifier: Modifier = Modifier,
    expanded: Boolean = false
) {
    val colors = LocalLuxyColors.current
    val toggleLabel = stringResource(
        if (expanded) R.string.details_collapse_cd else R.string.details_expand_cd
    )
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
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = toggleLabel, onClick = onToggleExpanded)
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
    language: String,
    locale: Locale
) {
    val colors = LocalLuxyColors.current
    val connected = telemetry.connectionState == ConnectionState.CONNECTED
    val checking = telemetry.verificationState == VerificationState.CHECKING
    val exitIpText = when {
        !connected -> "—"
        telemetry.exitIp?.isNotBlank() == true -> {
            val wrappedIp = BidiText.isolateLtr(telemetry.exitIp.orEmpty(), locale)
            val flag = telemetry.exitCountryCode?.let { ServerCountryResolver.flagEmoji(it) }.orEmpty()
            if (flag.isNotEmpty()) "$flag $wrappedIp" else wrappedIp
        }
        checking -> stringResource(R.string.details_checking)
        else -> stringResource(R.string.details_unknown)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailValueRow(
            label = stringResource(R.string.details_ip),
            value = exitIpText
        )
        HorizontalDivider(color = colors.outlineVariant)
        if (connected) {
            DetailTrafficRow(
                label = stringResource(R.string.details_total),
                downloadSize = DataSizeFormat.format(telemetry.rxBytes, language),
                uploadSize = DataSizeFormat.format(telemetry.txBytes, language)
            )
        } else {
            DetailValueRow(
                label = stringResource(R.string.details_total),
                value = "—"
            )
        }
    }
}
@Composable
private fun DetailTrafficRow(
    label: String,
    downloadSize: String,
    uploadSize: String
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        if (LocalDensity.current.fontScale * LocalThemeConfig.current.fontScale >= 1.3f) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TrafficAmount(arrow = "↓", size = downloadSize)
                TrafficAmount(arrow = "↑", size = uploadSize)
            }
        } else {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TrafficAmount(arrow = "↓", size = downloadSize)
                TrafficAmount(arrow = "↑", size = uploadSize)
            }
        }
    }
}

@Composable
private fun TrafficAmount(arrow: String, size: String) {
    val colors = LocalLuxyColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = arrow,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface
        )
        Text(
            text = size,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailValueRow(
    label: String,
    value: String
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    val language = LocalConfiguration.current.locales[0].language
    val translatedErrorMessage = UiText.translate(language, errorMessage)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (errorMessage.isNotBlank()) {
                Text(
                    text = translatedErrorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            textAlign = TextAlign.Left,
                            fontFamily = MonoFontFamily,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


