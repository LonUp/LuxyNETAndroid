package com.example.i18n

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDateFormatTest {
    @Test fun datesStayGregorianAndOnlyDigitsFollowLocale() {
        val millis = 1_795_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("2026-11-18 11:06", AppDateFormat.dateTime(millis, "en", utc))
        assertEquals("۲۰۲۶-۱۱-۱۸ ۱۱:۰۶", AppDateFormat.dateTime(millis, "fa", utc))
        assertEquals("2026-11-18", AppDateFormat.date(millis, "en", utc))
        assertEquals("۲۰۲۶-۱۱-۱۸", AppDateFormat.date(millis, "fa", utc))
    }
}
