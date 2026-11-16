package com.example

import com.example.ui.components.SessionStatsText
import com.example.ui.screens.ServerIdentity
import com.example.vpn.TrafficAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {

    @Test
    fun ratesUseActualElapsedTimeAndStayNonNegative() {
        val acc = TrafficAccumulator()
        acc.reset(1_000L)

        val first = acc.sample(rawRx = 2_000L, rawTx = 500L, nowMs = 3_000L)
        assertEquals(2_000L, first.totalRx)
        assertEquals(500L, first.totalTx)
        assertEquals(1_000L, first.rxBytesPerSec)
        assertEquals(250L, first.txBytesPerSec)

        val stalled = acc.sample(rawRx = 2_000L, rawTx = 500L, nowMs = 4_000L)
        assertEquals(0L, stalled.rxBytesPerSec)
        assertEquals(0L, stalled.txBytesPerSec)
        assertEquals(2_000L, stalled.totalRx)
    }

    @Test
    fun counterRestartDoesNotDropOrNegateTheSession() {
        val acc = TrafficAccumulator()
        acc.reset(0L)
        acc.sample(rawRx = 100L, rawTx = 40L, nowMs = 1_000L)
        val restarted = acc.sample(rawRx = 15L, rawTx = 5L, nowMs = 2_000L)
        assertEquals(115L, restarted.totalRx)
        assertEquals(45L, restarted.totalTx)
        assertTrue(restarted.rxBytesPerSec >= 0L)
    }

    @Test
    fun totalsSaturateInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, TrafficAccumulator.saturatingAdd(Long.MAX_VALUE - 5L, 20L))
        assertEquals(0L, TrafficAccumulator.rate(delta = -4L, elapsedMs = 1_000L))
        assertEquals(0L, TrafficAccumulator.rate(delta = 5_000L, elapsedMs = 50L))
    }

    @Test
    fun durationAndUnitsFollowTheSelectedLanguage() {
        assertEquals("00:01:05", SessionStatsText.duration(65_000L, "en"))
        assertEquals("۰۰:۰۱:۰۵", SessionStatsText.duration(65_000L, "fa"))
        assertEquals("1.0 KB/s", SessionStatsText.rate(1024L, "en"))
        assertTrue(SessionStatsText.rate(1024L, "fa").contains("ک.ب/ث"))
        assertTrue(SessionStatsText.volume(1024L * 1024L, "fa").contains("م.ب"))
        assertFalse(SessionStatsText.volume(1024L * 1024L, "fa").contains("KB"))
    }

    @Test
    fun ipAddressIsForcedLeftToRight() {
        val shown = SessionStatsText.address("185.111.236.48", "fa")
        assertTrue(shown.startsWith("\u202D"))
        assertTrue(shown.endsWith("\u202C"))
        assertTrue(shown.contains("۱۸۵.۱۱۱.۲۳۶.۴۸"))
    }

    @Test
    fun serverRemarkBecomesANameAndCountry() {
        val coded = ServerIdentity.resolve("AE", "1", "en")
        assertEquals("United Arab Emirates", coded.title)
        assertEquals("AE", coded.countryCode)
        assertFalse(coded.title == "AE")
        assertFalse(coded.title == "1")

        val numbered = ServerIdentity.resolve("AE 1", "Local", "en")
        assertEquals("Server 1", numbered.title)
        assertEquals("United Arab Emirates", numbered.countryName)
        assertEquals("AE", numbered.countryCode)

        val technical = ServerIdentity.resolve("vless://de.example.com:443", "Imported", "en")
        assertFalse(technical.title.contains("example"))
        assertFalse(technical.title.contains("443"))
        assertFalse(technical.title.contains("vless"))
        assertEquals("DE", technical.countryCode)
    }
}
