package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.FontChoice

/**
 * Persian-first type ramp: Vazirmatn 15 px body on a 1.75 leading (reference §3.2), Latin
 * numerals inherited from the same face inside RTL text, system mono for numbers and codes.
 */
val VazirmatnFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

val DisplayFontFamily: FontFamily = VazirmatnFamily
val BodyFontFamily: FontFamily = VazirmatnFamily
val MonoFontFamily: FontFamily = FontFamily.Monospace

/** 15 sp at scale 1.0 with the reference's 1.75 line height. */
private fun ramp(sp: Float, scale: Float, weight: FontWeight, family: FontFamily): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = scaled(sp, scale),
        lineHeight = scaled(sp * 1.75f, scale)
    )

private fun scaled(value: Float, scale: Float): TextUnit = (value * scale).sp

private fun FontWeight.bumped(): FontWeight = when (this) {
    FontWeight.Normal, FontWeight.Light, FontWeight.Thin, FontWeight.ExtraLight -> FontWeight.Medium
    FontWeight.Medium -> FontWeight.SemiBold
    else -> FontWeight.Bold
}

fun luxyTypography(
    scale: Float = 1f,
    boldText: Boolean = false,
    fontChoice: FontChoice = FontChoice.VAZIRMATN
): Typography {
    val body = if (fontChoice == FontChoice.SYSTEM) FontFamily.Default else VazirmatnFamily
    val display = if (fontChoice == FontChoice.SYSTEM) FontFamily.Default else VazirmatnFamily
    val regular = if (boldText) FontWeight.Medium else FontWeight.Normal
    val medium = if (boldText) FontWeight.SemiBold else FontWeight.Medium
    val semibold = if (boldText) FontWeight.Bold else FontWeight.SemiBold
    val bold = FontWeight.Bold
    val s = scale.coerceIn(0.85f, 1.30f)
    return Typography(
        displaySmall = ramp(32f, s, bold, display),
        displayMedium = ramp(38f, s, bold, display),
        displayLarge = ramp(46f, s, bold, display),
        headlineLarge = ramp(28f, s, semibold, display),
        headlineMedium = ramp(24f, s, semibold, display),
        headlineSmall = ramp(21f, s, semibold, display),
        titleLarge = ramp(18f, s, semibold, display),
        titleMedium = ramp(16f, s, medium, body),
        titleSmall = ramp(14f, s, medium, body),
        bodyLarge = ramp(15f, s, regular, body),
        bodyMedium = ramp(15f, s, regular, body),
        bodySmall = ramp(12f, s, regular, body),
        labelLarge = ramp(14f, s, medium, body),
        labelMedium = ramp(12f, s, medium, body),
        labelSmall = ramp(11f, s, regular, MonoFontFamily)
    )
}

val LuxyTypography: Typography = luxyTypography()
