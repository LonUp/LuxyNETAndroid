package com.example.i18n

import java.text.SimpleDateFormat
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Gregorian dates only. Persian localization changes digits, not the calendar.
 * Jalali conversion is not the app's date policy. java.text keeps minSdk 24.
 */
object AppDateFormat {
    fun dateTime(epochMillis: Long, language: String?, zone: TimeZone = TimeZone.getDefault()): String =
        Digits.localize(format("yyyy-MM-dd HH:mm", epochMillis, zone), language)

    fun date(epochMillis: Long, language: String?, zone: TimeZone = TimeZone.getDefault()): String =
        Digits.localize(format("yyyy-MM-dd", epochMillis, zone), language)

    private fun format(pattern: String, epochMillis: Long, zone: TimeZone): String {
        val calendar = GregorianCalendar(zone, Locale.US)
        calendar.timeInMillis = epochMillis
        val formatter = SimpleDateFormat(pattern, Locale.US)
        formatter.calendar = calendar
        formatter.timeZone = zone
        return formatter.format(calendar.time)
    }
}
