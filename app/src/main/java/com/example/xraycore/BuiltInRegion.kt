package com.example.xraycore

import android.icu.util.TimeZone as IcuTimeZone
import java.util.Locale
import java.util.TimeZone

/** Device-region hints only: neither a UI language nor a UTC offset identifies a country. */
object BuiltInRegion {
    private val isoCountries = Locale.getISOCountries().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    // IANA's country/location aliases without a slash. Do not accept ambiguous abbreviations
    // (CST, IST, EST, CET, etc.) just because ICU assigns one of their meanings a canonical ID.
    private val locationAliases = setOf(
        "Cuba", "Egypt", "Eire", "GB", "Hongkong", "Iceland", "Iran", "Israel",
        "Jamaica", "Japan", "Kwajalein", "Libya", "Navajo", "NZ", "NZ-CHAT",
        "Poland", "Portugal", "PRC", "ROC", "ROK", "Singapore", "Turkey"
    )

    // These ISO codes have no delegated ccTLD. Other exceptions are ccTLDs marketed globally:
    // sending their unrelated international services outside the tunnel is not a safe default.
    private val excludedTlds = setOf(
        "bq", "eh", "um", "ai", "cc", "co", "fm", "gg", "io", "la", "ly",
        "me", "nu", "tk", "to", "tv", "ws"
    )

    // Explicit service suffixes, not keyword/wildcard matches (in particular, never all .com).
    private val iranDomains = listOf(
        "digikala.com", "divar.ir", "sheypoor.com", "torob.com", "emalls.ir",
        "snapp.ir", "snapp.market", "snapp.taxi", "snappfood.ir", "snapptrip.com", "snappmarket.com",
        "snappshop.ir", "snappbox.ir", "snapp.doctor", "tapsi.ir", "aparat.com",
        "filimo.com", "namava.ir", "telewebion.com", "cafebazaar.ir", "myket.ir",
        "rubika.ir", "eitaa.com", "bale.ai", "splus.ir", "gap.im", "neshan.org",
        "balad.ir", "shaparak.ir", "zarinpal.com",
        "varzesh3.com", "okala.com", "basalam.com", "jabama.com"
    )

    private val iranRules = listOf("domain:ir") + iranDomains.map { "domain:$it" }

    /**
     * A named geographic timezone wins over a conflicting locale (including a traveler using
     * Persian). Unknown, fixed-offset and shared/abbreviated zones need an explicit locale
     * country; language-only locales deliberately have no fallback. ICU supplies the timezone
     * metadata already installed on the device, without downloading geodata.
     */
    fun country(
        timeZoneId: String = TimeZone.getDefault().id,
        locale: Locale = Locale.getDefault()
    ): String? {
        val localeCountry = supportedCountry(locale.country)
        // This legacy name explicitly spans two countries, unlike the GB and Eire aliases.
        if (timeZoneId == "GB-Eire") return localeCountry?.takeIf { it == "gb" || it == "ie" }

        val geographicId = (timeZoneId.contains('/') && !timeZoneId.startsWith("SystemV/")) ||
            timeZoneId in locationAliases
        if (!geographicId) return localeCountry
        return try {
            val canonical = IcuTimeZone.getCanonicalID(timeZoneId) ?: return localeCountry
            // Keep the original region first: tzdb links can cross national borders, e.g.
            // Europe/Bratislava and Europe/Prague. Canonicalization must not change the country.
            region(timeZoneId) ?: region(canonical) ?: localeCountry
        } catch (_: RuntimeException) {
            // Missing platform ICU metadata (including JVM stubs) must not break compilation.
            localeCountry
        } catch (_: LinkageError) {
            localeCountry
        }
    }

    /** Xray domain: rules match the suffix and its subdomains, never a substring. */
    fun directDomains(country: String?): List<String> {
        val code = country?.lowercase(Locale.ROOT) ?: return emptyList()
        if (code !in isoCountries || code in excludedTlds) return emptyList()
        if (code == "ir") return iranRules
        return listOf("domain:${if (code == "gb") "uk" else code}")
    }

    private fun supportedCountry(value: String): String? =
        value.lowercase(Locale.ROOT).takeIf { it in isoCountries }

    private fun region(id: String): String? = try {
        supportedCountry(IcuTimeZone.getRegion(id))
    } catch (_: IllegalArgumentException) {
        null
    }
}
