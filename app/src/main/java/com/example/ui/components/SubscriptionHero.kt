package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.SubscriptionUserInfo
import com.example.data.local.SubscriptionEntity
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalLuxyMetrics
import com.example.ui.theme.MonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Subscription hero: plan name, remaining quota bar and expiry, mirroring the reference home
 * card. Everything comes from the stored `subscription-userinfo` snapshot, so it renders without
 * a network call; the refresh button re-fetches and updates the snapshot.
 */
@Composable
fun SubscriptionHero(
    subscription: SubscriptionEntity,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val info = remember(subscription) {
        SubscriptionUserInfo(
            uploadBytes = subscription.uploadBytes,
            downloadBytes = subscription.downloadBytes,
            totalBytes = subscription.totalBytes,
            expireAtEpochSec = subscription.expireAtEpochSec
        )
    }
    val title = subscription.profileTitle?.takeIf { it.isNotBlank() } ?: subscription.name
    val expired = info.isExpired()
    val daysLeft = info.daysLeft()
    val accent = when {
        expired -> colors.statusError
        info.hasQuota && info.usageFraction > 0.9f -> colors.statusConnecting
        else -> colors.primary
    }

    LuxyCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subscription.lastStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.sub_hero_refresh),
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (info.hasQuota) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatGigabytes(info.remainingBytes),
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = accent
                )
                Text(
                    text = stringResource(R.string.sub_hero_of_total, formatGigabytes(info.totalBytes)),
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(metrics.radiusPill))
                    .background(colors.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(info.usageFraction.coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(metrics.radiusPill))
                        .background(accent)
                )
            }
        } else {
            Text(
                text = stringResource(
                    R.string.sub_hero_used,
                    Formatters.formatBytes(info.usedBytes)
                ),
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
                color = colors.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (info.hasExpiry) {
                HeroChip(
                    label = if (expired) {
                        stringResource(R.string.sub_hero_expired)
                    } else {
                        stringResource(R.string.sub_hero_days_left, daysLeft)
                    },
                    tint = if (expired) colors.statusError else colors.statusInfo
                )
            }
            if (info.hasQuota) {
                HeroChip(
                    label = stringResource(
                        R.string.sub_hero_percent_used,
                        (info.usageFraction * 100).toInt()
                    ),
                    tint = colors.onSurfaceVariant
                )
            }
            subscription.lastUpdatedAt?.let { updated ->
                HeroChip(
                    label = stringResource(R.string.sub_hero_updated, formatUpdatedAt(updated)),
                    tint = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeroChip(label: String, tint: Color) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Text(text = label, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** Two-decimal GB; the hero only ever shows traffic, so one fixed unit beats a per-value suffix. */
internal fun formatGigabytes(bytes: Long): String =
    String.format(Locale.US, "%.2f GB", bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0))

/** Clock time for today, month + day for older snapshots. */
internal fun formatUpdatedAt(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val sameDay = nowMs - timestampMs in 0L..(24L * 60L * 60L * 1000L)
    val pattern = if (sameDay) "HH:mm" else "MMM d"
    return SimpleDateFormat(pattern, Locale.US).format(Date(timestampMs))
}
