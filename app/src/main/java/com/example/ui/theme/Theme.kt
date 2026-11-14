package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.data.model.ColorPalette
import com.example.data.model.ThemeMode

@Composable
fun LuxyVpnTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    colorPalette: ColorPalette = ColorPalette.LUXY_OBSIDIAN_GOLD,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val scheme = getPaletteScheme(colorPalette, isDark)
    CompositionLocalProvider(LocalLuxyColors provides scheme) {
        MaterialTheme(
            colorScheme = scheme.toMaterialColorScheme(isDark),
            typography = LuxyTypography,
            content = content
        )
    }
}
