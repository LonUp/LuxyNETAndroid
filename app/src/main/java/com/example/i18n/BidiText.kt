package com.example.i18n

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import java.util.Locale

/**
 * Display-only handling for machine-readable runs embedded in localized text.
 *
 * Addresses, ports, protocol tuples, dates and counters are LTR data even when the surrounding
 * sentence is RTL. Isolating them keeps the host, port and separators in source order instead of
 * letting the bidi algorithm reorder punctuation around the run.
 */
object BidiText {
    fun isolateLtr(value: String, locale: Locale = Locale.getDefault()): String =
        if (value.isEmpty()) value
        else BidiFormatter.getInstance(locale)
            .unicodeWrap(value, TextDirectionHeuristics.LTR)
            .toString()
}
