package com.example

import com.example.core.ServerCountryResolver
import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.HealthPolicy
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class ServerCountryResolverTest {
    private fun node(name: String, address: String = "server.example") = ServerProfileEntity(
        name = name,
        address = address,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555"
    )

    @Test
    fun resolvesFlagsNamesCodesAndCitiesWithoutFalsePositives() {
        assertEquals("DE", ServerCountryResolver.resolveCode(node("🇩🇪 Germany 01")))
        assertEquals("US", ServerCountryResolver.resolveCode(node("🇺🇸 US-East")))
        assertEquals("GB", ServerCountryResolver.resolveCode(node("🇬🇧 London")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("🇮🇷 -> 🇩🇪 Relay")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("🇩🇪 Germany 🇩🇪")))
        assertNull(ServerCountryResolver.resolveCode(node("🇺🇸 🇩🇪 Mixed")))

        assertEquals("NL", ServerCountryResolver.resolveCode(node("Holland 02")))
        assertEquals("TR", ServerCountryResolver.resolveCode(node("Türkiye Istanbul")))
        assertEquals("AE", ServerCountryResolver.resolveCode(node("UAE Dubai")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("آلمان ۱")))
        assertEquals("HK", ServerCountryResolver.resolveCode(node("هنگ‌کنگ")))

        assertEquals("US", ServerCountryResolver.resolveCode(node("US - Georgia")))
        assertEquals("GE", ServerCountryResolver.resolveCode(node("Georgia - Tbilisi")))
        assertEquals("US", ServerCountryResolver.resolveCode(node("New Mexico 01")))
        assertEquals("US", ServerCountryResolver.resolveCode(node("New Jersey 02")))
        assertEquals("RO", ServerCountryResolver.resolveCode(node("Romania 01")))

        assertEquals("DE", ServerCountryResolver.resolveCode(node("DE-01")))
        assertEquals("NL", ServerCountryResolver.resolveCode(node("[NL] Server")))
        assertEquals("GB", ServerCountryResolver.resolveCode(node("UK | London")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("Frankfurt-01")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("فرانکفورت")))
        assertEquals("DE", ServerCountryResolver.resolveCode(node("Irancell - Germany")))

        // Unknown or unlabeled servers must never invent a country.
        for (unknown in listOf(
            "My server",
            "server.example:443",
            "198.51.100.1:443",
            "Node 1",
            "VIP-01",
            "WS-TLS-01",
            "SS-Fast",
            "MD5-Node",
            "EC2-Node",
            "de.example.com",
            "TLS1",
            "CF-Worker",
            "Chrome-TLS",
            "Backup-Node",
            "Slow-Node",
            "Similar-1",
            "Domain-01",
            ""
        )) {
            assertNull("Expected null for '$unknown'", ServerCountryResolver.resolveCode(node(unknown, address = "de.example.com")))
        }
        // Multi-hop arrow with unlabeled exit must never assume entry country is exit country.
        assertNull(ServerCountryResolver.resolveCode(node("🇮🇷 -> Relay 01")))
        assertNull(ServerCountryResolver.resolveCode(node("🇩🇪 Germany -> Fast")))
    }

    @Test
    fun verifiedCountryExpiresAfterSevenDaysAndFallsBackToManualOrMetadata() {
        val estimated = node("🇩🇪 Germany 01").copy(
            countryCode = "DE",
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.METADATA.name
        )
        assertEquals("DE", ServerCountryResolver.effectiveCountryCode(estimated))
        assertEquals(CountryConfidence.ESTIMATED, ServerCountryResolver.effectiveConfidence(estimated))

        val manual = node("Unlabeled").copy(
            manualCountryCode = "NL",
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.MANUAL.name
        )
        assertEquals("NL", ServerCountryResolver.effectiveCountryCode(manual))
        assertEquals(CountryConfidence.ESTIMATED, ServerCountryResolver.effectiveConfidence(manual))
        assertEquals(CountrySource.MANUAL, ServerCountryResolver.effectiveSource(manual))

        val nowMs = System.currentTimeMillis()
        val verified = manual.copy(
            countryCode = "FR",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countrySource = CountrySource.EXIT_VERIFIED.name,
            countryVerifiedAt = nowMs
        )
        assertEquals("FR", ServerCountryResolver.effectiveCountryCode(verified, nowMs))
        assertEquals(CountryConfidence.VERIFIED, ServerCountryResolver.effectiveConfidence(verified, nowMs))
        assertEquals(CountrySource.EXIT_VERIFIED, ServerCountryResolver.effectiveSource(verified, nowMs))

        val expired = verified.copy(
            countryVerifiedAt = nowMs - HealthPolicy.EXIT_VERIFICATION_STALE_MS - 1L
        )
        assertEquals("NL", ServerCountryResolver.effectiveCountryCode(expired, nowMs))
        assertEquals(CountryConfidence.ESTIMATED, ServerCountryResolver.effectiveConfidence(expired, nowMs))
        assertEquals(CountrySource.MANUAL, ServerCountryResolver.effectiveSource(expired, nowMs))
        assertEquals("NL", ServerCountryResolver.effectiveCountryCode(verified.copy(countryVerifiedAt = null), nowMs))

        val metadataFallback = node("🇺🇸 United States").copy(
            countryCode = "FR",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countrySource = CountrySource.EXIT_VERIFIED.name,
            countryVerifiedAt = expired.countryVerifiedAt
        )
        assertEquals("US", ServerCountryResolver.effectiveCountryCode(metadataFallback, nowMs))
        assertEquals(CountryConfidence.ESTIMATED, ServerCountryResolver.effectiveConfidence(metadataFallback, nowMs))
        assertEquals(CountrySource.METADATA, ServerCountryResolver.effectiveSource(metadataFallback, nowMs))
    }
    @Test
    fun availableCountriesDeduplicatesSortsAlphabeticallyAndIgnoresUnknowns() {
        val profiles = listOf(
            node("🇩🇪 Frankfurt 1"),
            node("DE-02"),
            node("آلمان ۳"),
            node("🇺🇸 US East"),
            node("Holland 1"),
            node("My server"),
            node("198.51.100.1:443")
        )

        val en = ServerCountryResolver.availableCountries(profiles, Locale.ENGLISH)
        assertEquals(listOf("DE", "NL", "US"), en.map { it.code })
        assertEquals(listOf("🇩🇪", "🇳🇱", "🇺🇸"), en.map { it.flag })
        assertEquals(listOf("Germany", "Netherlands", "United States"), en.map { it.name })

        val fa = ServerCountryResolver.availableCountries(profiles, Locale.forLanguageTag("fa"))
        assertEquals(3, fa.size)
        assertEquals(setOf("DE", "NL", "US"), fa.map { it.code }.toSet())
        assertTrue(fa.all { it.name.isNotBlank() && it.flag.isNotEmpty() })
    }

    @Test
    fun matchesFiltersBySelectedCountryOrAllowsAllForAutomatic() {
        val de = node("🇩🇪 Germany 1")
        val us = node("🇺🇸 US 1")
        val unknown = node("My server")

        assertTrue(ServerCountryResolver.matches(de, null))
        assertTrue(ServerCountryResolver.matches(us, null))
        assertTrue(ServerCountryResolver.matches(unknown, null))

        assertTrue(ServerCountryResolver.matches(de, "DE"))
        assertTrue(ServerCountryResolver.matches(de, "de"))
        assertFalse(ServerCountryResolver.matches(us, "DE"))
        assertFalse(ServerCountryResolver.matches(unknown, "DE"))
    }
}
