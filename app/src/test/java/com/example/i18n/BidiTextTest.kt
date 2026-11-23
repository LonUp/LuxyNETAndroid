package com.example.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BidiTextTest {
    private val fa = Locale.forLanguageTag("fa")

    @Test
    fun machineReadableRunIsIsolatedFromSurroundingRtlText() {
        val wrapped = BidiText.isolateLtr("VLESS · ams.example.com:443", fa)

        // BidiFormatter emits either ISO 2046 isolates or the legacy embedding controls,
        // depending on the platform; both keep the run LTR.
        assertTrue(
            "expected a bidi isolation control around $wrapped",
            wrapped.contains("\u2066") || wrapped.contains("\u202A") || wrapped.contains("\u200E")
        )
        assertTrue(wrapped.contains("VLESS · ams.example.com:443"))
    }

    @Test
    fun addressesAndDatesKeepTheirSourceOrder() {
        assertEquals(
            "203.0.113.7:443",
            BidiText.isolateLtr("203.0.113.7:443", fa)
                .replace("\u2066", "")
                .replace("\u2067", "")
                .replace("\u2068", "")
                .replace("\u2069", "")
                .replace("\u202A", "")
                .replace("\u202B", "")
                .replace("\u202C", "")
                .replace("\u200E", "")
                .replace("\u200F", "")
        )
    }

    @Test
    fun emptyValueStaysEmpty() {
        assertEquals("", BidiText.isolateLtr("", fa))
    }
}
