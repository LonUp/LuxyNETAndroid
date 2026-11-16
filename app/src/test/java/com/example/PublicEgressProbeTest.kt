package com.example

import com.example.core.PublicEgressProbe
import com.example.i18n.Digits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicEgressProbeTest {

    @Test
    fun parsesIpWhoIsPayload() {
        val parsed = PublicEgressProbe.parseEgressBody(
            """
            {"success":true,"ip":"203.0.113.8","country":"Iran","country_code":"IR",
             "connection":{"isp":"MCI","org":"Mobile Communication Company of Iran"}}
            """.trimIndent()
        )
        assertEquals("203.0.113.8", parsed!!.ip)
        assertEquals("IR", parsed.countryCode)
        assertEquals("MCI", parsed.provider)
    }

    @Test
    fun parsesIpApiPayloadAndRejectsFailures() {
        val parsed = PublicEgressProbe.parseEgressBody(
            """{"ip":"198.51.100.4","country":"DE","country_name":"Germany","org":"Example ISP"}"""
        )
        assertEquals("DE", parsed!!.countryCode)
        assertEquals("Germany", parsed.countryName)
        assertEquals("Example ISP", parsed.provider)
        assertNull(PublicEgressProbe.parseEgressBody("""{"success":false}"""))
        assertNull(PublicEgressProbe.parseEgressBody("not-json"))
    }

    @Test
    fun persianDigitsReplaceOnlyDisplayedNumerals() {
        assertEquals("۱۸۵.۱۱۱.۲۳۶.۴۸", Digits.localize("185.111.236.48", "fa"))
        assertEquals("22", Digits.localize("22", "en"))
        assertEquals("Wi-Fi", Digits.localize("Wi-Fi", "fa"))
    }
}
