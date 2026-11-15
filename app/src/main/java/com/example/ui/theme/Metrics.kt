package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeConfig

/**
 * Radius ladder and density from the reference (20 / 14 / 10, 16 px gutters, 12 px card gaps,
 * 44 px rows). Only the radius is user-tunable, via the theme's `radiusScale`.
 */
@Immutable
data class LuxyMetrics(
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val radiusXl: Dp,
    val radiusPill: Dp,
    val gutter: Dp,
    val cardGap: Dp,
    val cardPadding: Dp,
    val rowHeight: Dp,
    val orbSize: Dp
)

fun metricsFor(radiusScale: Float): LuxyMetrics {
    val s = radiusScale.coerceIn(0.6f, 1.6f)
    return LuxyMetrics(
        radiusSm = (10f * s).dp,
        radiusMd = (14f * s).dp,
        radiusLg = (20f * s).dp,
        radiusXl = (24f * s).dp,
        radiusPill = 999.dp,
        gutter = 16.dp,
        cardGap = 12.dp,
        cardPadding = 14.dp,
        rowHeight = 44.dp,
        orbSize = 156.dp
    )
}

val LocalLuxyMetrics = staticCompositionLocalOf { metricsFor(1f) }
val LocalThemeConfig = staticCompositionLocalOf { ThemeConfig() }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Motion is a user preference (`pal-motion` in the reference): honour it in every animation. */
val LocalLuxyMotion = staticCompositionLocalOf { true }
