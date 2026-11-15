package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemePalette
import java.util.Locale

/**
 * V2Fall token set (see REVIEW-AND-PLAN §3.1) plus the derived neutrals the screens need.
 *
 * The reference palette is warm graphite + amber, not a neutral grey scale: every derived
 * colour is mixed toward the theme accent so custom backgrounds keep their tint instead of
 * turning into flat #RRGGBB ladders.
 */
@Immutable
data class LuxyColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceLowest: Color,
    val surfaceLow: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val outlineVariant: Color,
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusError: Color,
    val statusInfo: Color,
    // v2fall-specific tokens
    val card2: Color,
    val faint: Color,
    val line: Color,
    val line2: Color,
    val nav: Color,
    val accentSoft: Color,
    val accentText: Color,
    val accentInk: Color,
    val orbInner: Color,
    val orbOuter: Color
) {
    fun toMaterialColorScheme(isDark: Boolean): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = surfaceLowest,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHigh,
            outline = outline,
            outlineVariant = outlineVariant
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = surfaceLowest,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHigh,
            outline = outline,
            outlineVariant = outlineVariant
        )
    }
}

val LuxyDarkScheme: LuxyColorScheme = schemeFor(ThemeConfig(), isDark = true)
val LuxyLightScheme: LuxyColorScheme = schemeFor(ThemeConfig(), isDark = false)

/** Expands the three user colours into the full token set for the requested mode. */
fun schemeFor(theme: ThemeConfig, isDark: Boolean): LuxyColorScheme =
    schemeForPalette(theme.palette(isDark), isDark)

fun schemeForPalette(palette: ThemePalette, isDark: Boolean): LuxyColorScheme {
    val bg = palette.background.toComposeColor() ?: Color(0xFF050100)
    val card = palette.card.toComposeColor() ?: Color(0xFF181412)
    val accent = palette.accent.toComposeColor() ?: Color(0xFFF0994B)
    return if (isDark) darkScheme(bg, card, accent) else lightScheme(bg, card, accent)
}

private fun darkScheme(bg: Color, card: Color, accent: Color): LuxyColorScheme {
    val white = Color.White
    val black = Color.Black
    val text = lerp(lerp(bg, white, 0.94f), accent, 0.05f)
    val muted = lerp(text, bg, 0.34f)
    val faint = lerp(text, bg, 0.66f)
    val card2 = lerp(card, lerp(white, accent, 0.30f), 0.05f)
    val accentSoft = accent.copy(alpha = 0.14f)
    val accentContainer = lerp(card, accent, 0.20f)
    val accentOnContainer = lerp(accent, white, 0.55f)
    val accentText = lerp(accent, white, 0.22f)
    val accentInk = lerp(accent, black, 0.82f)
    val ok = Color(0xFF3DD68C)
    val warn = Color(0xFFF5C542)
    val bad = Color(0xFFF2555A)
    val info = Color(0xFF38BDF8)
    return LuxyColorScheme(
        primary = accent,
        onPrimary = accentInk,
        primaryContainer = accentContainer,
        onPrimaryContainer = accentOnContainer,
        secondary = accentText,
        onSecondary = accentInk,
        secondaryContainer = accentContainer,
        onSecondaryContainer = text,
        tertiary = info,
        onTertiary = bg,
        tertiaryContainer = lerp(card, info, 0.22f),
        onTertiaryContainer = lerp(info, white, 0.55f),
        error = bad,
        // White on the light-red error fill measures 3.4:1; dark ink clears AA and matches the
        // accent button's ink treatment.
        onError = lerp(bad, black, 0.80f),
        errorContainer = lerp(card, bad, 0.20f),
        onErrorContainer = lerp(bad, white, 0.35f),
        background = bg,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = card2,
        onSurfaceVariant = muted,
        surfaceLowest = bg,
        surfaceLow = card,
        surfaceContainer = card2,
        surfaceHigh = card2,
        outline = text.copy(alpha = 0.17f),
        outlineVariant = text.copy(alpha = 0.09f),
        statusConnected = ok,
        statusConnecting = warn,
        statusError = bad,
        statusInfo = info,
        card2 = card2,
        faint = faint,
        line = text.copy(alpha = 0.09f),
        line2 = text.copy(alpha = 0.17f),
        nav = card.copy(alpha = 0.74f),
        accentSoft = accentSoft,
        accentText = accentText,
        accentInk = accentInk,
        orbInner = lerp(card, accent, 0.06f),
        orbOuter = lerp(bg, accent, 0.05f)
    )
}

private fun lightScheme(bg: Color, card: Color, accent: Color): LuxyColorScheme {
    val white = Color.White
    val black = Color.Black
    val text = lerp(lerp(bg, black, 0.85f), accent, 0.08f)
    val muted = lerp(text, bg, 0.35f)
    val faint = lerp(text, bg, 0.62f)
    val card2 = lerp(card, bg, 0.65f)
    val accentSoft = accent.copy(alpha = 0.14f)
    val accentContainer = lerp(card, accent, 0.16f)
    val accentOnContainer = lerp(accent, black, 0.42f)
    val accentText = lerp(accent, black, 0.12f)
    // Pure white clears AA on every light accent in the catalogue; a 94 % tint failed on
    // obsidian gold (4.38:1).
    val accentInk = white
    val ok = Color(0xFF0F7A49)
    val warn = Color(0xFF8A6508)
    val bad = Color(0xFFC8383D)
    val info = Color(0xFF0E7C93)
    return LuxyColorScheme(
        primary = accent,
        onPrimary = accentInk,
        primaryContainer = accentContainer,
        onPrimaryContainer = accentOnContainer,
        secondary = accentText,
        onSecondary = accentInk,
        secondaryContainer = accentContainer,
        onSecondaryContainer = text,
        tertiary = info,
        onTertiary = white,
        tertiaryContainer = lerp(card, info, 0.16f),
        onTertiaryContainer = lerp(info, black, 0.45f),
        error = bad,
        onError = white,
        errorContainer = lerp(card, bad, 0.12f),
        onErrorContainer = lerp(bad, black, 0.25f),
        background = bg,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = card2,
        onSurfaceVariant = muted,
        surfaceLowest = bg,
        surfaceLow = card,
        surfaceContainer = card2,
        surfaceHigh = card2,
        outline = text.copy(alpha = 0.18f),
        outlineVariant = text.copy(alpha = 0.10f),
        statusConnected = ok,
        statusConnecting = warn,
        statusError = bad,
        statusInfo = info,
        card2 = card2,
        faint = faint,
        line = text.copy(alpha = 0.10f),
        line2 = text.copy(alpha = 0.18f),
        nav = card.copy(alpha = 0.94f),
        accentSoft = accentSoft,
        accentText = accentText,
        accentInk = accentInk,
        orbInner = lerp(card, accent, 0.10f),
        orbOuter = lerp(bg, accent, 0.04f)
    )
}

/** Parses `RRGGBB` (the stored form) into an opaque Compose colour. */
fun String.toComposeColor(): Color? {
    val raw = trim().removePrefix("#")
    if (raw.length != 6) return null
    val value = raw.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}

fun Color.toHexRgb(): String = String.format(
    Locale.ROOT, "%02X%02X%02X",
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255)
)

val LocalLuxyColors = staticCompositionLocalOf { LuxyDarkScheme }
