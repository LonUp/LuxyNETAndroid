package com.example

import com.example.core.SubscriptionUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUserInfoTest {

    @Test
    fun parsesThePanelsStandardHeader() {
        val info = SubscriptionUserInfo.parse(
            "upload=1073741824; download=2147483648; total=10737418240; expire=1767225600"
        )
        requireNotNull(info)
        assertEquals(1073741824L, info.uploadBytes)
        assertEquals(2147483648L, info.downloadBytes)
        assertEquals(10737418240L, info.totalBytes)
        assertEquals(1767225600L, info.expireAtEpochSec)
        assertEquals(3221225472L, info.usedBytes)
        assertEquals(7516192768L, info.remainingBytes)
        assertTrue(info.hasQuota)
        assertTrue(info.hasExpiry)
    }

    @Test
    fun toleratesSpacingCaseAndUnknownKeys() {
        val info = SubscriptionUserInfo.parse(
            "Upload = 100 ;DOWNLOADS=5; download=200; total=1000; reset_day=7"
        )
        requireNotNull(info)
        assertEquals(100L, info.uploadBytes)
        assertEquals(200L, info.downloadBytes)
        assertEquals(1000L, info.totalBytes)
        assertEquals(0L, info.expireAtEpochSec)
        assertFalse(info.hasExpiry)
    }

    @Test
    fun ignoresNonNumericValuesInsteadOfGuessing() {
        // Several panels emit an ISO date for `expire`; it must not become a bogus epoch.
        val info = SubscriptionUserInfo.parse(
            "upload=0; download=50; total=100; expire=2026-01-01T00:00:00Z"
        )
        requireNotNull(info)
        assertEquals(0L, info.expireAtEpochSec)
        assertEquals(50L, info.usedBytes)
    }

    @Test
    fun returnsNullForAbsentOrEmptyHeaders() {
        assertNull(SubscriptionUserInfo.parse(null))
        assertNull(SubscriptionUserInfo.parse(""))
        assertNull(SubscriptionUserInfo.parse("   "))
        assertNull(SubscriptionUserInfo.parse("upload=0; download=0; total=0; expire=0"))
        assertNull(SubscriptionUserInfo.parse("garbage"))
    }

    @Test
    fun clampsUsageAndRemainingWhenThePanelOverreports() {
        val info = SubscriptionUserInfo(uploadBytes = 900, downloadBytes = 900, totalBytes = 1000)
        assertEquals(1f, info.usageFraction)
        assertEquals(0L, info.remainingBytes)
    }

    @Test
    fun reportsExpiryInWholeDaysAndFlagsExpiredPlans() {
        val now = 1_767_225_600L
        val info = SubscriptionUserInfo(totalBytes = 1, expireAtEpochSec = now + 3L * 86_400L + 3600L)
        assertEquals(3, info.daysLeft(now))
        assertFalse(info.isExpired(now))

        val expired = SubscriptionUserInfo(totalBytes = 1, expireAtEpochSec = now - 1)
        assertTrue(expired.isExpired(now))
        assertEquals(0, expired.daysLeft(now))
    }

    @Test
    fun exposesDaysHoursAndMinutesUntilExpiry() {
        val now = 1_767_225_600L
        val expires = SubscriptionUserInfo(expireAtEpochSec = now + 3L * 86_400L + 3_600L + 30L * 60L)
        assertEquals(SubscriptionUserInfo.RemainingTime(3L, 1, 30), expires.remainingTime(now))

        val underMinute = SubscriptionUserInfo(expireAtEpochSec = now + 59L)
        assertEquals(SubscriptionUserInfo.RemainingTime(0L, 0, 0), underMinute.remainingTime(now))

        val expired = SubscriptionUserInfo(expireAtEpochSec = now - 1L)
        assertEquals(SubscriptionUserInfo.RemainingTime(0L, 0, 0), expired.remainingTime(now))
    }

    @Test
    fun noQuotaMeansNoFractionAndNoRemaining() {
        val info = SubscriptionUserInfo(uploadBytes = 10, downloadBytes = 20)
        assertFalse(info.hasQuota)
        assertEquals(0f, info.usageFraction)
        assertEquals(0L, info.remainingBytes)
        assertEquals(30L, info.usedBytes)
    }

    @Test
    fun saturatesUsedBytesAndClampsNegativeCounters() {
        val info = SubscriptionUserInfo(
            uploadBytes = Long.MAX_VALUE - 10L,
            downloadBytes = 100L,
            totalBytes = Long.MAX_VALUE
        )

        assertEquals(Long.MAX_VALUE, info.usedBytes)
        assertEquals(1f, info.usageFraction)
        assertEquals(0L, info.remainingBytes)
        assertEquals(Long.MAX_VALUE, SubscriptionUserInfo.usedBytes(Long.MAX_VALUE, 1L))
        assertEquals(5L, SubscriptionUserInfo.usedBytes(-10L, 5L))
    }

    @Test
    fun distantExpiryDoesNotOverflowDisplayedTime() {
        val info = SubscriptionUserInfo(expireAtEpochSec = Long.MAX_VALUE)

        assertEquals(Int.MAX_VALUE, info.daysLeft(0L))
        assertEquals(
            Long.MAX_VALUE / 86_400L,
            info.remainingTime(Long.MIN_VALUE).days
        )
    }
}
