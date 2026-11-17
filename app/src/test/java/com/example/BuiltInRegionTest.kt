package com.example

import com.example.xraycore.BuiltInRegion
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BuiltInRegionTest {
    @Test
    fun geographicTimezoneWinsOverConflictingLocaleAndLanguage() {
        assertEquals("ir", BuiltInRegion.country("Asia/Tehran", Locale("fa")))
        assertEquals("ir", BuiltInRegion.country("Asia/Tehran", Locale.US))
        assertEquals("us", BuiltInRegion.country("America/New_York", Locale("fa", "IR")))
        assertEquals("de", BuiltInRegion.country("Europe/Berlin", Locale("fa")))
        assertEquals("gb", BuiltInRegion.country("Europe/London", Locale.US))
        assertEquals("ae", BuiltInRegion.country("Asia/Dubai", Locale.forLanguageTag("ar-Arab-EG")))
    }

    @Test
    fun aliasesResolveWithoutLosingOriginalGeographicRegion() {
        val aliases = mapOf(
            "Iran" to "ir", "US/Eastern" to "us", "Canada/Eastern" to "ca",
            "GB" to "gb", "Eire" to "ie", "Japan" to "jp",
            "Asia/Calcutta" to "in", "Asia/Katmandu" to "np", "Europe/Kiev" to "ua",
            "Europe/Bratislava" to "sk", "Europe/Prague" to "cz",
            "Europe/Stockholm" to "se", "Europe/Oslo" to "no"
        )
        aliases.forEach { (zone, expected) ->
            assertEquals(zone, expected, BuiltInRegion.country(zone, Locale.ROOT))
        }
    }

    @Test
    fun sharedZonesOffsetsAndAbbreviationsDoNotInventCountry() {
        for (zone in listOf(
            "CET", "EET", "MET", "EST", "CST", "IST", "PST", "PST8PDT",
            "SystemV/EST5EDT", "UTC", "GMT", "Etc/GMT-3", "GMT+03:30", "W-SU"
        )) {
            assertNull(zone, BuiltInRegion.country(zone, Locale.ROOT))
            assertNull(zone, BuiltInRegion.country(zone, Locale("fa")))
        }
        assertEquals("fr", BuiltInRegion.country("CET", Locale.FRANCE))
        assertEquals("ca", BuiltInRegion.country("CST", Locale.CANADA))
        assertEquals("ir", BuiltInRegion.country("GMT+03:30", Locale("fa", "IR")))
        assertNull(BuiltInRegion.country("GB-Eire", Locale.ROOT))
        assertNull(BuiltInRegion.country("GB-Eire", Locale.US))
        assertEquals("gb", BuiltInRegion.country("GB-Eire", Locale.UK))
        assertEquals("ie", BuiltInRegion.country("GB-Eire", Locale("en", "IE")))
    }

    @Test
    fun unknownTimezoneNeedsExplicitIsoCountryNotRtlLanguage() {
        for (zone in listOf("", "Unknown/Nowhere", "Etc/Unknown", "Asia/NotAPlace")) {
            assertNull(BuiltInRegion.country(zone, Locale.ROOT))
            assertNull(BuiltInRegion.country(zone, Locale("fa")))
            assertNull(BuiltInRegion.country(zone, Locale("ar")))
            assertNull(BuiltInRegion.country(zone, Locale.forLanguageTag("he-Hebr")))
            assertNull(BuiltInRegion.country(zone, Locale("en", "ZZ")))
            assertNull(BuiltInRegion.country(zone, Locale.forLanguageTag("es-419")))
            assertEquals("ir", BuiltInRegion.country(zone, Locale("fa", "IR")))
            assertEquals("af", BuiltInRegion.country(zone, Locale.forLanguageTag("fa-Arab-AF")))
            assertEquals("il", BuiltInRegion.country(zone, Locale.forLanguageTag("he-Hebr-IL")))
        }
    }

    @Test
    fun defaultsUseDeviceTimezoneAndLocale() {
        val previousZone = TimeZone.getDefault()
        val previousLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            Locale.setDefault(Locale("fa", "IR"))
            assertEquals("us", BuiltInRegion.country())
        } finally {
            TimeZone.setDefault(previousZone)
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    @Config(sdk = [24])
    fun minimumAndroidVersionSupportsIcuRegionInference() {
        assertEquals("ir", BuiltInRegion.country("Iran", Locale("fa")))
        assertEquals("us", BuiltInRegion.country("US/Eastern", Locale("fa", "IR")))
        assertNull(BuiltInRegion.country("GMT+03:30", Locale("fa")))
    }

    @Test
    fun countrySuffixesAreValidAndNeverGlobalGenericDomains() {
        assertEquals(listOf("domain:uk"), BuiltInRegion.directDomains("gb"))
        assertEquals(listOf("domain:us"), BuiltInRegion.directDomains("us"))
        assertEquals(listOf("domain:de"), BuiltInRegion.directDomains("DE"))
        assertEquals(listOf("domain:cn"), BuiltInRegion.directDomains("cn"))
        assertEquals(listOf("domain:tr"), BuiltInRegion.directDomains("tr"))
        assertEquals(listOf("domain:ae"), BuiltInRegion.directDomains("ae"))
        assertEquals(emptyList<String>(), BuiltInRegion.directDomains(null))
        assertEquals(emptyList<String>(), BuiltInRegion.directDomains("unknown"))
        assertEquals(emptyList<String>(), BuiltInRegion.directDomains("uk"))
        for (country in Locale.getISOCountries()) {
            val domains = BuiltInRegion.directDomains(country)
            assertEquals(domains.size, domains.distinct().size)
            assertTrue(country, domains.all { it.startsWith("domain:") })
            for (global in listOf("com", "net", "org", "gov", "mil", "gb")) {
                assertFalse(country, "domain:$global" in domains)
            }
        }
    }

    @Test
    fun undelegatedAndGloballyMarketedTldsStayInsideTunnel() {
        for (country in listOf("bq", "eh", "um", "ai", "io", "co", "me", "tv", "cc")) {
            assertEquals(country, emptyList<String>(), BuiltInRegion.directDomains(country))
        }
    }

    @Test
    fun iranIncludesDomesticServicesAsExactDomainSuffixRules() {
        val domains = BuiltInRegion.directDomains("ir")
        val expected = listOf(
            "ir", "digikala.com", "divar.ir", "sheypoor.com", "torob.com", "emalls.ir",
            "snapp.ir", "snapp.market", "snapp.taxi", "snappfood.ir", "snapptrip.com", "snappmarket.com",
            "snappshop.ir", "snappbox.ir", "snapp.doctor", "tapsi.ir", "aparat.com",
            "filimo.com", "namava.ir", "telewebion.com", "cafebazaar.ir", "myket.ir",
            "rubika.ir", "eitaa.com", "bale.ai", "splus.ir", "gap.im", "neshan.org",
            "balad.ir", "shaparak.ir", "zarinpal.com",
            "varzesh3.com", "okala.com", "basalam.com", "jabama.com"
        ).map { "domain:$it" }
        assertEquals(expected, domains)
        assertFalse("domain:ai" in domains)
        assertFalse("domain:im" in domains)
        assertFalse("domain:com" in domains)
        assertFalse(domains.any { it.contains('*') || it.startsWith("regexp:") })
        assertFalse(domains.any { it == "domain:snapp" || it == "domain:digikala" })
    }
}

/** Also exercise the locale fallback with Android's unimplemented plain-JVM ICU stubs. */
class BuiltInRegionJvmTest {
    @Test
    fun unavailablePlatformIcuDoesNotBreakRegionPolicy() {
        assertEquals("ir", BuiltInRegion.country("Asia/Tehran", Locale("fa", "IR")))
        assertEquals(listOf("domain:us"), BuiltInRegion.directDomains("us"))
        assertNull(BuiltInRegion.country("Unknown/Nowhere", Locale.ROOT))
    }
}
