package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.ColorPalette
import com.example.data.model.ThemeMode

@Immutable
data class TunnelStatusColors(
    val connected: Color,
    val connecting: Color,
    val error: Color,
    val info: Color
)

val LocalTunnelStatusColors = staticCompositionLocalOf {
    TunnelStatusColors(
        connected = SemanticEmeraldDark,
        connecting = SemanticAmber,
        error = SemanticCoral,
        info = LuxyGoldSecondary
    )
}

private val LuxyDarkScheme = darkColorScheme(
    primary = LuxyGoldPrimary,
    onPrimary = Color(0xFF1E1600),
    primaryContainer = Color(0xFF3A2E0B),
    onPrimaryContainer = Color(0xFFF6E3A4),
    secondary = LuxyGoldSecondary,
    onSecondary = Color(0xFF002336),
    secondaryContainer = Color(0xFF0D3247),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = LuxyGoldTertiary,
    onTertiary = Color(0xFF241B00),
    tertiaryContainer = Color(0xFF3D3112),
    onTertiaryContainer = Color(0xFFFFEBA8),
    error = SemanticCoral,
    onError = Color.White,
    errorContainer = Color(0xFF4C1120),
    onErrorContainer = Color(0xFFFECDD3),
    background = LuxyDarkCanvas,
    onBackground = LuxyTextDark,
    surface = LuxyDarkSurface,
    onSurface = LuxyTextDark,
    surfaceVariant = LuxyDarkSurfaceVar,
    onSurfaceVariant = LuxyTextMutedDark,
    surfaceContainerLowest = LuxyDarkCanvas,
    surfaceContainerLow = LuxyDarkSurface,
    surfaceContainer = LuxyDarkSurfaceVar,
    surfaceContainerHigh = LuxyDarkSurfaceHigh,
    outline = LuxyDarkOutline,
    outlineVariant = LuxyDarkOutlineSubtle
)

private val LuxyLightScheme = lightColorScheme(
    primary = LuxyGoldLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E6B8),
    onPrimaryContainer = Color(0xFF332500),
    secondary = LuxyGoldLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3FF),
    onSecondaryContainer = Color(0xFF063752),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    error = SemanticCoralLight,
    onError = Color.White,
    background = LuxyLightCanvas,
    onBackground = LuxyTextLight,
    surface = LuxyLightSurface,
    onSurface = LuxyTextLight,
    surfaceVariant = LuxyLightSurfaceVar,
    onSurfaceVariant = LuxyTextMutedLight,
    surfaceContainerLowest = LuxyLightSurface,
    surfaceContainerLow = LuxyLightCanvas,
    surfaceContainer = LuxyLightSurfaceVar,
    surfaceContainerHigh = LuxyLightSurfaceHigh,
    outline = LuxyLightOutline,
    outlineVariant = Color(0xFFE5DCC8)
)

private val CatppuccinDarkScheme = darkColorScheme(
    primary = CatppuccinMochaMauve,
    onPrimary = Color(0xFF24103B),
    primaryContainer = Color(0xFF3A2858),
    onPrimaryContainer = Color(0xFFEFDFFF),
    secondary = CatppuccinMochaSapphire,
    onSecondary = Color(0xFF002433),
    secondaryContainer = Color(0xFF1E3A4C),
    onSecondaryContainer = Color(0xFFCAEFFF),
    tertiary = CatppuccinMochaPeach,
    onTertiary = Color(0xFF331804),
    tertiaryContainer = Color(0xFF472C1A),
    onTertiaryContainer = Color(0xFFFFDCC4),
    error = CatppuccinMochaRed,
    onError = Color(0xFF380914),
    errorContainer = Color(0xFF521828),
    onErrorContainer = Color(0xFFFFD8E0),
    background = CatppuccinMochaBase,
    onBackground = CatppuccinMochaText,
    surface = CatppuccinMochaMantle,
    onSurface = CatppuccinMochaText,
    surfaceVariant = CatppuccinMochaSurface0,
    onSurfaceVariant = CatppuccinMochaSubtext,
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = CatppuccinMochaMantle,
    surfaceContainer = CatppuccinMochaSurface0,
    surfaceContainerHigh = CatppuccinMochaSurface1,
    outline = Color(0xFF585B70),
    outlineVariant = CatppuccinMochaSurface0
)

private val CatppuccinLightScheme = lightColorScheme(
    primary = CatppuccinLatteMauve,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD9FF),
    onPrimaryContainer = Color(0xFF2C0062),
    secondary = CatppuccinLatteSapphire,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0F0F7),
    onSecondaryContainer = Color(0xFF003540),
    tertiary = CatppuccinLattePeach,
    onTertiary = Color.White,
    error = CatppuccinLatteRed,
    onError = Color.White,
    background = CatppuccinLatteBase,
    onBackground = CatppuccinLatteText,
    surface = CatppuccinLatteSurface,
    onSurface = CatppuccinLatteText,
    surfaceVariant = CatppuccinLatteSurface0,
    onSurfaceVariant = CatppuccinLatteSubtext,
    surfaceContainerLowest = CatppuccinLatteSurface,
    surfaceContainerLow = CatppuccinLatteBase,
    surfaceContainer = CatppuccinLatteSurface0,
    surfaceContainerHigh = CatppuccinLatteSurface1,
    outline = Color(0xFFBCC0CC),
    outlineVariant = CatppuccinLatteSurface0
)

private val DraculaDarkScheme = darkColorScheme(
    primary = DraculaPurple,
    onPrimary = Color(0xFF241142),
    primaryContainer = Color(0xFF3E2B5C),
    onPrimaryContainer = Color(0xFFEEDFFF),
    secondary = DraculaCyan,
    onSecondary = Color(0xFF002730),
    secondaryContainer = Color(0xFF1D414D),
    onSecondaryContainer = Color(0xFFCFF7FF),
    tertiary = DraculaPink,
    onTertiary = Color(0xFF3E0028),
    tertiaryContainer = Color(0xFF521E3D),
    onTertiaryContainer = Color(0xFFFFD8EC),
    error = DraculaRed,
    onError = Color.White,
    errorContainer = Color(0xFF57161B),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DraculaBackground,
    onBackground = DraculaForeground,
    surface = DraculaSurface,
    onSurface = DraculaForeground,
    surfaceVariant = DraculaSurfaceHigh,
    onSurfaceVariant = DraculaComment,
    surfaceContainerLowest = DraculaSurface,
    surfaceContainerLow = DraculaBackground,
    surfaceContainer = DraculaSurfaceHigh,
    surfaceContainerHigh = DraculaCurrentLine,
    outline = Color(0xFF6272A4),
    outlineVariant = DraculaCurrentLine
)

private val DraculaLightScheme = lightColorScheme(
    primary = DraculaLightPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FF),
    onPrimaryContainer = Color(0xFF280066),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F3FB),
    onSecondaryContainer = Color(0xFF003340),
    tertiary = DraculaLightPink,
    onTertiary = Color.White,
    error = Color(0xFFDC2626),
    onError = Color.White,
    background = DraculaLightBg,
    onBackground = Color(0xFF1F1D2E),
    surface = DraculaLightSurface,
    onSurface = Color(0xFF1F1D2E),
    surfaceVariant = DraculaLightSurfaceVar,
    onSurfaceVariant = Color(0xFF575275),
    surfaceContainerLowest = DraculaLightSurface,
    surfaceContainerLow = DraculaLightBg,
    surfaceContainer = DraculaLightSurfaceVar,
    surfaceContainerHigh = Color(0xFFE0D9F0),
    outline = DraculaLightOutline,
    outlineVariant = DraculaLightSurfaceVar
)

private val NordDarkScheme = darkColorScheme(
    primary = NordFrostCyan,
    onPrimary = Color(0xFF0A2430),
    primaryContainer = Color(0xFF294352),
    onPrimaryContainer = Color(0xFFD5F1FA),
    secondary = NordFrostBlue,
    onSecondary = Color(0xFF0F2033),
    secondaryContainer = Color(0xFF2F3E52),
    onSecondaryContainer = Color(0xFFD6E6FA),
    tertiary = NordAuroraGreen,
    onTertiary = Color(0xFF1A2913),
    error = NordAuroraRed,
    onError = Color.White,
    errorContainer = Color(0xFF4F2429),
    onErrorContainer = Color(0xFFFFDADC),
    background = NordPolarNight0,
    onBackground = NordSnowStorm2,
    surface = Color(0xFF272C36),
    onSurface = NordSnowStorm2,
    surfaceVariant = NordPolarNight1,
    onSurfaceVariant = NordSnowStorm0,
    surfaceContainerLowest = Color(0xFF232831),
    surfaceContainerLow = NordPolarNight0,
    surfaceContainer = NordPolarNight1,
    surfaceContainerHigh = NordPolarNight2,
    outline = NordPolarNight3,
    outlineVariant = NordPolarNight2
)

private val NordLightScheme = lightColorScheme(
    primary = NordLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E9F5),
    onPrimaryContainer = Color(0xFF0B2E42),
    secondary = NordLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EBE0),
    onSecondaryContainer = Color(0xFF133320),
    tertiary = Color(0xFFB4862E),
    onTertiary = Color.White,
    error = NordAuroraRed,
    onError = Color.White,
    background = NordSnowStorm2,
    onBackground = NordPolarNight0,
    surface = Color.White,
    onSurface = NordPolarNight0,
    surfaceVariant = NordLightSurfaceVar,
    onSurfaceVariant = NordPolarNight3,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = NordSnowStorm2,
    surfaceContainer = NordLightSurfaceVar,
    surfaceContainerHigh = NordSnowStorm0,
    outline = NordLightOutline,
    outlineVariant = NordLightSurfaceVar
)

private val TokyoDarkScheme = darkColorScheme(
    primary = TokyoBlue,
    onPrimary = Color(0xFF0A1D42),
    primaryContainer = Color(0xFF243359),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = TokyoPurple,
    onSecondary = Color(0xFF231340),
    secondaryContainer = Color(0xFF342954),
    onSecondaryContainer = Color(0xFFEADFFF),
    tertiary = TokyoCyan,
    onTertiary = Color(0xFF002736),
    error = TokyoRed,
    onError = Color(0xFF3B0914),
    errorContainer = Color(0xFF541827),
    onErrorContainer = Color(0xFFFFD8DF),
    background = TokyoBg,
    onBackground = TokyoText,
    surface = TokyoSurface,
    onSurface = TokyoText,
    surfaceVariant = TokyoSurfaceVar,
    onSurfaceVariant = TokyoSubtext,
    surfaceContainerLowest = TokyoSurface,
    surfaceContainerLow = TokyoBg,
    surfaceContainer = TokyoSurfaceVar,
    surfaceContainerHigh = TokyoSurfaceHigh,
    outline = TokyoOutline,
    outlineVariant = TokyoSurfaceVar
)

private val TokyoLightScheme = lightColorScheme(
    primary = TokyoDayBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF092452),
    secondary = TokyoDayPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEFF),
    onSecondaryContainer = Color(0xFF240F52),
    tertiary = Color(0xFF0F766E),
    onTertiary = Color.White,
    error = Color(0xFFC81E4B),
    onError = Color.White,
    background = TokyoDayBg,
    onBackground = TokyoDayText,
    surface = TokyoDaySurface,
    onSurface = TokyoDayText,
    surfaceVariant = TokyoDaySurfaceVar,
    onSurfaceVariant = Color(0xFF4D526E),
    surfaceContainerLowest = Color(0xFFF2F3F7),
    surfaceContainerLow = TokyoDaySurface,
    surfaceContainer = TokyoDaySurfaceVar,
    surfaceContainerHigh = Color(0xFFBCBEC9),
    outline = TokyoDayOutline,
    outlineVariant = TokyoDaySurfaceVar
)

private val SolarizedDarkScheme = darkColorScheme(
    primary = SolarizedCyan,
    onPrimary = Color(0xFF00211F),
    primaryContainer = Color(0xFF0A4346),
    onPrimaryContainer = Color(0xFFBEF5EE),
    secondary = SolarizedBlue,
    onSecondary = Color(0xFF002038),
    secondaryContainer = Color(0xFF0E3E5E),
    onSecondaryContainer = Color(0xFFCDE9FF),
    tertiary = SolarizedYellow,
    onTertiary = Color(0xFF2B2000),
    error = SolarizedRed,
    onError = Color.White,
    errorContainer = Color(0xFF521817),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SolarizedBase03,
    onBackground = SolarizedBase2,
    surface = SolarizedBase02,
    onSurface = SolarizedBase2,
    surfaceVariant = Color(0xFF0E4350),
    onSurfaceVariant = SolarizedBase1,
    surfaceContainerLowest = Color(0xFF002029),
    surfaceContainerLow = SolarizedBase03,
    surfaceContainer = SolarizedBase02,
    surfaceContainerHigh = Color(0xFF134B59),
    outline = SolarizedBase01,
    outlineVariant = Color(0xFF0D3E4A)
)

private val SolarizedLightScheme = lightColorScheme(
    primary = SolarizedCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF2EE),
    onPrimaryContainer = Color(0xFF003330),
    secondary = SolarizedBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EAFD),
    onSecondaryContainer = Color(0xFF002C4D),
    tertiary = SolarizedYellow,
    onTertiary = Color.White,
    error = SolarizedRed,
    onError = Color.White,
    background = SolarizedBase3,
    onBackground = SolarizedBase03,
    surface = Color(0xFFFFFBF0),
    onSurface = SolarizedBase03,
    surfaceVariant = SolarizedBase2,
    onSurfaceVariant = SolarizedBase01,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = SolarizedBase3,
    surfaceContainer = SolarizedBase2,
    surfaceContainerHigh = Color(0xFFE3DCBF),
    outline = Color(0xFFCCC4A8),
    outlineVariant = SolarizedBase2
)

private val GruvboxDarkScheme = darkColorScheme(
    primary = GruvboxDarkAqua,
    onPrimary = Color(0xFF132917),
    primaryContainer = Color(0xFF2D4530),
    onPrimaryContainer = Color(0xFFD2F0CF),
    secondary = GruvboxDarkOrange,
    onSecondary = Color(0xFF361600),
    secondaryContainer = Color(0xFF542D14),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = GruvboxDarkYellow,
    onTertiary = Color(0xFF302400),
    error = GruvboxDarkRed,
    onError = Color(0xFF3B0905),
    errorContainer = Color(0xFF571A14),
    onErrorContainer = Color(0xFFFFDAD4),
    background = GruvboxDarkBg,
    onBackground = GruvboxDarkFg,
    surface = GruvboxDarkSurface,
    onSurface = GruvboxDarkFg,
    surfaceVariant = GruvboxDarkSurface1,
    onSurfaceVariant = GruvboxDarkMuted,
    surfaceContainerLowest = GruvboxDarkSurface,
    surfaceContainerLow = GruvboxDarkBg,
    surfaceContainer = GruvboxDarkSurface1,
    surfaceContainerHigh = GruvboxDarkSurface2,
    outline = Color(0xFF665C54),
    outlineVariant = GruvboxDarkSurface1
)

private val GruvboxLightScheme = lightColorScheme(
    primary = GruvboxLightAqua,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE8D7),
    onPrimaryContainer = Color(0xFF0D331B),
    secondary = GruvboxLightOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAD8C5),
    onSecondaryContainer = Color(0xFF471600),
    tertiary = GruvboxLightGreen,
    onTertiary = Color.White,
    error = GruvboxLightRed,
    onError = Color.White,
    background = GruvboxLightBg,
    onBackground = GruvboxLightFg,
    surface = GruvboxLightSurface,
    onSurface = GruvboxLightFg,
    surfaceVariant = GruvboxLightSurface1,
    onSurfaceVariant = GruvboxLightMuted,
    surfaceContainerLowest = GruvboxLightSurface,
    surfaceContainerLow = GruvboxLightBg,
    surfaceContainer = GruvboxLightSurface1,
    surfaceContainerHigh = GruvboxLightOutline,
    outline = Color(0xFFBDAE93),
    outlineVariant = GruvboxLightSurface1
)

val LuxyVpnShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

fun resolveIsDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean =
    when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

fun paletteColorScheme(
    palette: ColorPalette,
    isDark: Boolean,
    context: Context
): ColorScheme = when (palette) {
    ColorPalette.LUXY_OBSIDIAN_GOLD -> if (isDark) LuxyDarkScheme else LuxyLightScheme
    ColorPalette.CATPPUCCIN -> if (isDark) CatppuccinDarkScheme else CatppuccinLightScheme
    ColorPalette.DRACULA -> if (isDark) DraculaDarkScheme else DraculaLightScheme
    ColorPalette.NORD -> if (isDark) NordDarkScheme else NordLightScheme
    ColorPalette.TOKYO_NIGHT -> if (isDark) TokyoDarkScheme else TokyoLightScheme
    ColorPalette.SOLARIZED -> if (isDark) SolarizedDarkScheme else SolarizedLightScheme
    ColorPalette.GRUVBOX -> if (isDark) GruvboxDarkScheme else GruvboxLightScheme
    ColorPalette.MATERIAL_YOU -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) LuxyDarkScheme else LuxyLightScheme
        }
    }
}

fun statusColorsFor(
    palette: ColorPalette,
    isDark: Boolean,
    colorScheme: ColorScheme
): TunnelStatusColors = when (palette) {
    ColorPalette.LUXY_OBSIDIAN_GOLD -> TunnelStatusColors(
        connected = if (isDark) SemanticEmeraldDark else SemanticEmeraldLight,
        connecting = if (isDark) SemanticAmber else SemanticAmberDarkText,
        error = if (isDark) SemanticCoral else SemanticCoralLight,
        info = colorScheme.secondary
    )
    ColorPalette.CATPPUCCIN -> TunnelStatusColors(
        connected = if (isDark) CatppuccinMochaGreen else CatppuccinLatteGreen,
        connecting = if (isDark) CatppuccinMochaPeach else CatppuccinLattePeach,
        error = if (isDark) CatppuccinMochaRed else CatppuccinLatteRed,
        info = colorScheme.secondary
    )
    ColorPalette.DRACULA -> TunnelStatusColors(
        connected = if (isDark) DraculaGreen else SemanticEmeraldLight,
        connecting = if (isDark) DraculaOrange else SemanticAmberDarkText,
        error = if (isDark) DraculaRed else SemanticCoralLight,
        info = colorScheme.secondary
    )
    ColorPalette.NORD -> TunnelStatusColors(
        connected = if (isDark) NordAuroraGreen else NordLightSecondary,
        connecting = if (isDark) NordAuroraYellow else Color(0xFFB4862E),
        error = NordAuroraRed,
        info = colorScheme.primary
    )
    ColorPalette.TOKYO_NIGHT -> TunnelStatusColors(
        connected = if (isDark) TokyoGreen else Color(0xFF485E30),
        connecting = if (isDark) TokyoAmber else Color(0xFF8F5E15),
        error = if (isDark) TokyoRed else Color(0xFFC81E4B),
        info = colorScheme.primary
    )
    ColorPalette.SOLARIZED -> TunnelStatusColors(
        connected = SolarizedGreen,
        connecting = SolarizedYellow,
        error = SolarizedRed,
        info = SolarizedCyan
    )
    ColorPalette.GRUVBOX -> TunnelStatusColors(
        connected = if (isDark) GruvboxDarkGreen else GruvboxLightGreen,
        connecting = if (isDark) GruvboxDarkYellow else GruvboxLightOrange,
        error = if (isDark) GruvboxDarkRed else GruvboxLightRed,
        info = colorScheme.primary
    )
    ColorPalette.MATERIAL_YOU -> TunnelStatusColors(
        connected = if (isDark) SemanticEmeraldDark else SemanticEmeraldLight,
        connecting = if (isDark) SemanticAmber else SemanticAmberDarkText,
        error = colorScheme.error,
        info = colorScheme.primary
    )
}

@Composable
fun LuxyVpnTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    colorPalette: ColorPalette = ColorPalette.LUXY_OBSIDIAN_GOLD,
    content: @Composable () -> Unit
) {
    val isDark = resolveIsDarkTheme(themeMode, isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = paletteColorScheme(colorPalette, isDark, context)
    val statusColors = statusColorsFor(colorPalette, isDark, colorScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }

    CompositionLocalProvider(LocalTunnelStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LuxyVpnTypography,
            shapes = LuxyVpnShapes,
            content = content
        )
    }
}
