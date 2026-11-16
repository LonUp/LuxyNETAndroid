package com.example.ui.components

import com.example.i18n.Digits
import java.util.Locale

/**
 * Display-only formatting for the current VPN session. Subscription quota uses a different
 * source and must not be passed through here.
 */
object SessionStatsText {

    /** A value split so the screen can size the unit below the number. */
    data class Amount(val number: String, val unit: String)

    fun duration(durationMs: Long, language: String?): String {
        val totalSec = (durationMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        val text = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        return Digits.localize(text, language)
    }

    fun rate(bytesPerSec: Long, language: String?): String {
        val amount = rateParts(bytesPerSec, language)
        return "${amount.number} ${amount.unit}"
    }

    fun volume(bytes: Long, language: String?): String {
        val amount = volumeParts(bytes, language)
        return "${amount.number} ${amount.unit}"
    }

    fun rateParts(bytesPerSec: Long, language: String?): Amount =
        parts(bytesPerSec, language, perSecond = true)

    fun volumeParts(bytes: Long, language: String?): Amount =
        parts(bytes, language, perSecond = false)

    /**
     * Forces visual left-to-right order. Persian digits are Arabic numbers, so a plain RTL
     * paragraph would reverse the octets.
     */
    fun address(ip: String, language: String?): String {
        val localized = Digits.localize(ip, language)
        return "\u202D$localized\u202C"
    }

    private fun parts(bytes: Long, language: String?, perSecond: Boolean): Amount {
        val b = bytes.coerceAtLeast(0L)
        val (number, unit) = when {
            b < 1024L -> b.toString() to "B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f", b / 1024.0) to "KB"
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f", b / (1024.0 * 1024.0)) to "MB"
            else -> String.format(Locale.US, "%.2f", b / (1024.0 * 1024.0 * 1024.0)) to "GB"
        }
        val suffix = if (language == "fa") persianUnit(unit, perSecond) else {
            if (perSecond) "$unit/s" else unit
        }
        return Amount(Digits.localize(number, language), suffix)
    }

    private fun persianUnit(unit: String, perSecond: Boolean): String {
        val name = when (unit) {
            "KB" -> "ک.ب"
            "MB" -> "م.ب"
            "GB" -> "گ.ب"
            else -> "ب"
        }
        return if (perSecond) "$name/ث" else name
    }
}
