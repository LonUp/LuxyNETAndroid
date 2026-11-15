package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemeMode

/**
 * Applies a [ThemeConfig] to Compose: tokens, radius ladder, type ramp, motion flag and the
 * Material colour scheme derived from the same palette.
 */
@Composable
fun LuxyVpnTheme(
    theme: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (theme.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val scheme = remember(theme.dark, theme.light, isDark) { schemeFor(theme, isDark) }
    val metrics = remember(theme.radiusScale) { metricsFor(theme.radiusScale) }
    val typography = remember(theme.fontScale, theme.boldText, theme.font) {
        luxyTypography(theme.fontScale, theme.boldText, theme.font)
    }
    CompositionLocalProvider(
        LocalLuxyColors provides scheme,
        LocalLuxyMetrics provides metrics,
        LocalThemeConfig provides theme,
        LocalIsDarkTheme provides isDark,
        LocalLuxyMotion provides theme.motionEnabled
    ) {
        MaterialTheme(
            colorScheme = scheme.toMaterialColorScheme(isDark),
            typography = typography,
            content = content
        )
    }
}
