package com.example.i18n

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Display-only decimal sizes. Stored counters stay raw bytes.
 *
 * Scaling is by 1,000, never 1,024. A binary quantity must not be relabeled as KB/MB/GB.
 * Persian uses familiar names (کیلوبایت، مگابایت، گیگابایت) and Persian digits.
 */
object DataSizeFormat {
    private val UNITS_EN = arrayOf("B", "KB", "MB", "GB", "TB")
    private val UNITS_FA = arrayOf("بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت")
    private const val STEP = 1_000.0

    fun format(bytes: Long, language: String?): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        var amount = safe
        var unit = 0
        while (amount >= STEP && unit < UNITS_EN.lastIndex) {
            amount /= STEP
            unit++
        }
        val fa = language == "fa"
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            if (fa) {
                decimalSeparator = '٫'
                groupingSeparator = '٬'
            }
        }
        val pattern = when {
            unit == 0 -> "#,##0"
            amount < 10.0 -> "#,##0.00"
            amount < 100.0 -> "#,##0.0"
            else -> "#,##0"
        }
        val number = DecimalFormat(pattern, symbols).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = true
        }.format(amount)
        val label = if (fa) UNITS_FA[unit] else UNITS_EN[unit]
        return "${Digits.localize(number, language)} $label"
    }
}
