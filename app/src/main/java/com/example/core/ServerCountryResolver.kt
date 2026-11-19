package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
data class CountryOption(
    val code: String,
    val name: String,
    val flag: String
)

/**
 * Derives explicit country metadata from imported server remarks without inventing locations
 * for unlabeled servers or guessing from hostnames/IPs.
 */
object ServerCountryResolver {
    private val validIsoCodes: Set<String> = Locale.getISOCountries().toSet()

    private val arrowRegex = Regex("(?:->|=>|→|➔|➡)")

    // Unambiguous uppercase 2-letter ISO codes commonly used in server remarks.
    // Excludes English words and protocol/tech acronyms (AT, IN, IS, IT, MY, NO, TO, BY, DO,
    // SO, ME, ID, TV, AI, IO, SS, WS, CF, EC, MD, PK, PR, PS, PM, AM, AS, SH, PY, RS, SA).
    private val safeTwoLetterCodes = setOf(
        "AE", "AL", "AR", "AU", "AZ", "BE", "BG", "BH", "BR", "CA", "CH", "CL", "CN", "CO",
        "CY", "CZ", "DE", "DK", "EE", "EG", "ES", "FI", "FR", "GB", "GE", "GR", "HK", "HR",
        "HU", "IE", "IL", "IR", "JP", "KE", "KR", "KW", "KZ", "LT", "LU", "LV", "MK", "MT",
        "MX", "NG", "NL", "NZ", "OM", "PH", "PL", "PT", "QA", "RO", "RU", "SE", "SG", "SI",
        "SK", "TH", "TR", "TW", "UA", "UK", "US", "UZ", "VN", "ZA"
    )

    private val safeThreeLetterCodes = mapOf(
        "ARE" to "AE", "AUS" to "AU", "BRA" to "BR", "CHE" to "CH", "DEU" to "DE",
        "FIN" to "FI", "FRA" to "FR", "GBR" to "GB", "HKG" to "HK", "IRN" to "IR",
        "JPN" to "JP", "KOR" to "KR", "NLD" to "NL", "POL" to "PL", "RUS" to "RU",
        "SGP" to "SG", "SWE" to "SE", "TUR" to "TR", "TWN" to "TW", "UKR" to "UA",
        "USA" to "US"
    )

    private val usDisambiguationTokens = listOf("united states", "usa", "u.s.a", "u.s", "atlanta")

    private val countryAliases: Map<String, String> = mapOf(
        // US state disambiguation (checked longest-first before country names)
        "new jersey" to "US",
        "new mexico" to "US",
        "atlanta, georgia" to "US",
        "georgia, us" to "US",
        "georgia, usa" to "US",
        // English aliases
        "usa" to "US",
        "u.s.a" to "US",
        "u.s" to "US",
        "united states of america" to "US",
        "united states" to "US",
        "america" to "US",
        "uk" to "GB",
        "u.k" to "GB",
        "united kingdom" to "GB",
        "great britain" to "GB",
        "britain" to "GB",
        "england" to "GB",
        "scotland" to "GB",
        "wales" to "GB",
        "uae" to "AE",
        "u.a.e" to "AE",
        "united arab emirates" to "AE",
        "emirates" to "AE",
        "holland" to "NL",
        "netherlands" to "NL",
        "the netherlands" to "NL",
        "germany" to "DE",
        "deutschland" to "DE",
        "france" to "FR",
        "turkey" to "TR",
        "turkiye" to "TR",
        "finland" to "FI",
        "sweden" to "SE",
        "switzerland" to "CH",
        "austria" to "AT",
        "poland" to "PL",
        "italy" to "IT",
        "spain" to "ES",
        "portugal" to "PT",
        "ireland" to "IE",
        "norway" to "NO",
        "denmark" to "DK",
        "belgium" to "BE",
        "luxembourg" to "LU",
        "iceland" to "IS",
        "canada" to "CA",
        "singapore" to "SG",
        "japan" to "JP",
        "south korea" to "KR",
        "korea" to "KR",
        "republic of korea" to "KR",
        "north korea" to "KP",
        "hong kong" to "HK",
        "hongkong" to "HK",
        "taiwan" to "TW",
        "russia" to "RU",
        "russian federation" to "RU",
        "czechia" to "CZ",
        "czech republic" to "CZ",
        "australia" to "AU",
        "new zealand" to "NZ",
        "india" to "IN",
        "brazil" to "BR",
        "mexico" to "MX",
        "argentina" to "AR",
        "chile" to "CL",
        "colombia" to "CO",
        "south africa" to "ZA",
        "ukraine" to "UA",
        "romania" to "RO",
        "bulgaria" to "BG",
        "hungary" to "HU",
        "greece" to "GR",
        "cyprus" to "CY",
        "malta" to "MT",
        "moldova" to "MD",
        "latvia" to "LV",
        "lithuania" to "LT",
        "estonia" to "EE",
        "slovakia" to "SK",
        "slovenia" to "SI",
        "croatia" to "HR",
        "serbia" to "RS",
        "albania" to "AL",
        "north macedonia" to "MK",
        "armenia" to "AM",
        "georgia" to "GE",
        "azerbaijan" to "AZ",
        "kazakhstan" to "KZ",
        "uzbekistan" to "UZ",
        "iran" to "IR",
        "iraq" to "IQ",
        "israel" to "IL",
        "saudi arabia" to "SA",
        "qatar" to "QA",
        "bahrain" to "BH",
        "oman" to "OM",
        "kuwait" to "KW",
        "malaysia" to "MY",
        "thailand" to "TH",
        "vietnam" to "VN",
        "indonesia" to "ID",
        "philippines" to "PH",
        "china" to "CN",
        "macau" to "MO",
        "macao" to "MO",
        "egypt" to "EG",
        "nigeria" to "NG",
        "kenya" to "KE",
        "belarus" to "BY",
        // Persian country names & common aliases
        "امریکا" to "US",
        "آمریکا" to "US",
        "ایالات متحده" to "US",
        "ایالات متحده امریکا" to "US",
        "ایالات متحده آمریکا" to "US",
        "انگلیس" to "GB",
        "انگلستان" to "GB",
        "بریتانیا" to "GB",
        "امارات" to "AE",
        "امارات متحده عربی" to "AE",
        "هلند" to "NL",
        "المان" to "DE",
        "آلمان" to "DE",
        "فرانسه" to "FR",
        "ترکیه" to "TR",
        "فنلاند" to "FI",
        "سوئد" to "SE",
        "سوئیس" to "CH",
        "اتریش" to "AT",
        "لهستان" to "PL",
        "ایتالیا" to "IT",
        "اسپانیا" to "ES",
        "پرتغال" to "PT",
        "ایرلند" to "IE",
        "نروژ" to "NO",
        "دانمارک" to "DK",
        "بلژیک" to "BE",
        "لوکزامبورگ" to "LU",
        "ایسلند" to "IS",
        "کانادا" to "CA",
        "سنگاپور" to "SG",
        "ژاپن" to "JP",
        "کره جنوبی" to "KR",
        "هنگ کنگ" to "HK",
        "تایوان" to "TW",
        "روسیه" to "RU",
        "چک" to "CZ",
        "جمهوری چک" to "CZ",
        "استرالیا" to "AU",
        "نیوزیلند" to "NZ",
        "هند" to "IN",
        "برزیل" to "BR",
        "مکزیک" to "MX",
        "ارژانتین" to "AR",
        "آرژانتین" to "AR",
        "شیلی" to "CL",
        "کلمبیا" to "CO",
        "افریقای جنوبی" to "ZA",
        "آفریقای جنوبی" to "ZA",
        "اوکراین" to "UA",
        "رومانی" to "RO",
        "بلغارستان" to "BG",
        "مجارستان" to "HU",
        "یونان" to "GR",
        "قبرس" to "CY",
        "مالت" to "MT",
        "مولداوی" to "MD",
        "لتونی" to "LV",
        "لیتوانی" to "LT",
        "استونی" to "EE",
        "اسلواکی" to "SK",
        "اسلوونی" to "SI",
        "کرواسی" to "HR",
        "صربستان" to "RS",
        "البانی" to "AL",
        "آلبانی" to "AL",
        "مقدونیه شمالی" to "MK",
        "ارمنستان" to "AM",
        "گرجستان" to "GE",
        "اذربایجان" to "AZ",
        "آذربایجان" to "AZ",
        "قزاقستان" to "KZ",
        "ازبکستان" to "UZ",
        "ایران" to "IR",
        "عراق" to "IQ",
        "اسرائیل" to "IL",
        "عربستان" to "SA",
        "عربستان سعودی" to "SA",
        "قطر" to "QA",
        "بحرین" to "BH",
        "عمان" to "OM",
        "کویت" to "KW",
        "مالزی" to "MY",
        "تایلند" to "TH",
        "ویتنام" to "VN",
        "اندونزی" to "ID",
        "فیلیپین" to "PH",
        "چین" to "CN",
        "ماکائو" to "MO",
        "مصر" to "EG",
        "نیجریه" to "NG",
        "کنیا" to "KE",
        "بلاروس" to "BY"
    )

    private val cityAliases: Map<String, String> = mapOf(
        "frankfurt" to "DE", "munich" to "DE", "berlin" to "DE", "nuremberg" to "DE",
        "falkenstein" to "DE", "dusseldorf" to "DE", "hamburg" to "DE",
        "amsterdam" to "NL", "rotterdam" to "NL",
        "istanbul" to "TR", "ankara" to "TR", "izmir" to "TR", "antalya" to "TR",
        "london" to "GB", "manchester" to "GB",
        "paris" to "FR", "marseille" to "FR", "lyon" to "FR",
        "zurich" to "CH", "geneva" to "CH", "bern" to "CH",
        "helsinki" to "FI", "stockholm" to "SE",
        "warsaw" to "PL", "krakow" to "PL", "gdansk" to "PL",
        "vienna" to "AT", "milan" to "IT", "rome" to "IT",
        "madrid" to "ES", "barcelona" to "ES", "lisbon" to "PT",
        "dublin" to "IE", "oslo" to "NO", "copenhagen" to "DK", "brussels" to "BE",
        "prague" to "CZ", "bucharest" to "RO", "sofia" to "BG", "budapest" to "HU",
        "athens" to "GR", "reykjavik" to "IS", "riga" to "LV", "vilnius" to "LT",
        "tallinn" to "EE", "chisinau" to "MD", "kyiv" to "UA", "kiev" to "UA",
        "moscow" to "RU", "saint petersburg" to "RU", "novosibirsk" to "RU",
        "yerevan" to "AM", "tbilisi" to "GE", "baku" to "AZ", "almaty" to "KZ", "astana" to "KZ",
        "dubai" to "AE", "abu dhabi" to "AE", "riyadh" to "SA", "jeddah" to "SA",
        "doha" to "QA", "manama" to "BH", "muscat" to "OM", "tehran" to "IR",
        "tel aviv" to "IL", "jerusalem" to "IL",
        "tokyo" to "JP", "osaka" to "JP", "seoul" to "KR", "taipei" to "TW",
        "toronto" to "CA", "montreal" to "CA", "vancouver" to "CA",
        "new york" to "US", "los angeles" to "US", "chicago" to "US", "dallas" to "US",
        "miami" to "US", "seattle" to "US", "san jose" to "US", "ashburn" to "US",
        "atlanta" to "US", "phoenix" to "US", "denver" to "US", "silicon valley" to "US",
        "newark" to "US", "boston" to "US", "houston" to "US", "las vegas" to "US",
        "sydney" to "AU", "melbourne" to "AU", "auckland" to "NZ",
        "mumbai" to "IN", "bangalore" to "IN", "chennai" to "IN", "new delhi" to "IN",
        "sao paulo" to "BR", "johannesburg" to "ZA", "bangkok" to "TH",
        "hanoi" to "VN", "ho chi minh" to "VN", "manila" to "PH", "jakarta" to "ID",
        "kuala lumpur" to "MY", "cairo" to "EG", "nairobi" to "KE", "lagos" to "NG",
        // Persian city names
        "فرانکفورت" to "DE", "مونیخ" to "DE", "برلین" to "DE",
        "امستردام" to "NL", "آمستردام" to "NL",
        "استانبول" to "TR", "لندن" to "GB", "پاریس" to "FR", "زوریخ" to "CH",
        "هلسینکی" to "FI", "استکهلم" to "SE", "ورشو" to "PL", "وین" to "AT",
        "میلان" to "IT", "مادرید" to "ES", "بارسلونا" to "ES", "لیسبون" to "PT",
        "دوبلین" to "IE", "اسلو" to "NO", "کپنهاگ" to "DK", "بروکسل" to "BE",
        "پراگ" to "CZ", "بخارست" to "RO", "صوفیه" to "BG", "بوداپست" to "HU",
        "اتن" to "GR", "آتن" to "GR", "کی یف" to "UA", "مسکو" to "RU",
        "ایروان" to "AM", "تفلیس" to "GE", "باکو" to "AZ", "الماتی" to "KZ", "آلماتی" to "KZ",
        "دبی" to "AE", "ابوظبی" to "AE", "ریاض" to "SA", "دوحه" to "QA", "تهران" to "IR",
        "توکیو" to "JP", "اوساکا" to "JP", "سئول" to "KR", "تایپه" to "TW",
        "تورنتو" to "CA", "مونترال" to "CA", "ونکوور" to "CA",
        "نیویورک" to "US", "لس انجلس" to "US", "لس آنجلس" to "US",
        "شیکاگو" to "US", "دالاس" to "US", "میامی" to "US", "سیاتل" to "US",
        "سن خوزه" to "US", "اتلانتا" to "US", "آتلانتا" to "US",
        "سیدنی" to "AU", "ملبورن" to "AU", "بمبئی" to "IN", "سائوپائولو" to "BR",
        "بانکوک" to "TH", "جاکارتا" to "ID", "مانیل" to "PH", "کوالالامپور" to "MY",
        "قاهره" to "EG"
    )

    private val sortedCountryPhrases: List<Pair<String, String>> by lazy {
        val map = LinkedHashMap<String, String>()
        for ((phrase, code) in countryAliases) {
            map[normalizeForMatch(phrase)] = code
        }
        val faLocale = Locale.forLanguageTag("fa")
        for (iso in validIsoCodes) {
            for (loc in listOf(Locale.ENGLISH, faLocale)) {
                val display = normalizeForMatch(Locale("", iso).getDisplayCountry(loc))
                if (display.length >= 3 && display != iso.lowercase(Locale.ROOT)) {
                    map.putIfAbsent(display, iso)
                }
            }
        }
        map.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
    }

    private val sortedCityPhrases: List<Pair<String, String>> by lazy {
        cityAliases.entries
            .map { normalizeForMatch(it.key) to it.value }
            .sortedByDescending { it.first.length }
    }

    fun normalizeCode(raw: String?): String? {
        val upper = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (upper.length != 2) return null
        val canonical = if (upper == "UK") "GB" else upper
        return canonical.takeIf { it in validIsoCodes }
    }

    fun flagEmoji(countryCode: String): String {
        val code = normalizeCode(countryCode) ?: return ""
        val first = 0x1F1E6 + (code[0] - 'A')
        val second = 0x1F1E6 + (code[1] - 'A')
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun displayName(countryCode: String, locale: Locale = Locale.getDefault()): String {
        val code = normalizeCode(countryCode) ?: return ""
        return Locale("", code).getDisplayCountry(locale).ifBlank { code }
    }

    fun optionForCode(countryCode: String, locale: Locale = Locale.getDefault()): CountryOption? {
        val code = normalizeCode(countryCode) ?: return null
        return CountryOption(
            code = code,
            name = displayName(code, locale),
            flag = flagEmoji(code)
        )
    }

    fun displayLabel(countryCode: String, locale: Locale = Locale.getDefault()): String {
        val option = optionForCode(countryCode, locale) ?: return ""
        return if (option.flag.isNotEmpty()) "${option.flag} ${option.name}" else option.name
    }

    fun availableCountries(
        profiles: List<ServerProfileEntity>,
        locale: Locale = Locale.getDefault()
    ): List<CountryOption> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return profiles.asSequence()
            .mapNotNull(::effectiveCountryCode)
            .distinct()
            .mapNotNull { optionForCode(it, locale) }
            .sortedWith { a, b ->
                val cmp = collator.compare(a.name, b.name)
                if (cmp != 0) cmp else a.code.compareTo(b.code)
            }
            .toList()
    }

    fun matches(profile: ServerProfileEntity, selectedCountryCode: String?): Boolean {
        val target = normalizeCode(selectedCountryCode) ?: return true
        return effectiveCountryCode(profile) == target
    }

    fun effectiveCountryCode(profile: ServerProfileEntity): String? {
        val storedCode = normalizeCode(profile.countryCode)
        val storedConfidence = CountryConfidence.parse(profile.countryConfidence)
        if (storedConfidence == CountryConfidence.VERIFIED && storedCode != null) {
            return storedCode
        }
        val manual = normalizeCode(profile.manualCountryCode)
        if (manual != null) return manual
        if (storedCode != null && storedConfidence == CountryConfidence.ESTIMATED) {
            return storedCode
        }
        return resolveCode(profile.name)
    }

    fun effectiveConfidence(profile: ServerProfileEntity): CountryConfidence {
        val storedCode = normalizeCode(profile.countryCode)
        val storedConfidence = CountryConfidence.parse(profile.countryConfidence)
        if (storedConfidence == CountryConfidence.VERIFIED && storedCode != null) {
            return CountryConfidence.VERIFIED
        }
        if (normalizeCode(profile.manualCountryCode) != null) {
            return CountryConfidence.ESTIMATED
        }
        if (storedCode != null && storedConfidence == CountryConfidence.ESTIMATED) {
            return CountryConfidence.ESTIMATED
        }
        return if (resolveCode(profile.name) != null) CountryConfidence.ESTIMATED else CountryConfidence.UNKNOWN
    }

    fun effectiveSource(profile: ServerProfileEntity): CountrySource {
        val storedCode = normalizeCode(profile.countryCode)
        val storedConfidence = CountryConfidence.parse(profile.countryConfidence)
        if (storedConfidence == CountryConfidence.VERIFIED && storedCode != null) {
            return CountrySource.EXIT_VERIFIED
        }
        if (normalizeCode(profile.manualCountryCode) != null) {
            return CountrySource.MANUAL
        }
        if (storedCode != null && storedConfidence == CountryConfidence.ESTIMATED) {
            return CountrySource.parse(profile.countrySource).takeIf { it != CountrySource.NONE }
                ?: CountrySource.METADATA
        }
        return if (resolveCode(profile.name) != null) CountrySource.METADATA else CountrySource.NONE
    }

    fun resolveCode(profile: ServerProfileEntity): String? = effectiveCountryCode(profile)

    fun resolveCode(rawName: String): String? {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return null

        val arrowMatch = arrowRegex.findAll(trimmed).lastOrNull()
        if (arrowMatch != null) {
            val head = trimmed.substring(0, arrowMatch.range.first).trim()
            val tail = trimmed.substring(arrowMatch.range.last + 1).trim()
            if (head.isNotEmpty()) {
                // Multi-hop / relay remark: never assume the entry hop's country is the exit country.
                return if (tail.isNotEmpty()) resolveSegment(tail) else null
            }
            if (tail.isNotEmpty()) {
                resolveSegment(tail)?.let { return it }
            }
        }
        return resolveSegment(trimmed)
    }
    private fun resolveSegment(segment: String): String? {
        extractFlagCode(segment)?.let { return it }

        val normalized = normalizeForMatch(segment)
        val nameMatches = findPhraseMatches(normalized, sortedCountryPhrases)
        if (nameMatches.isNotEmpty()) {
            val resolved = if ("GE" in nameMatches && hasUsDisambiguation(segment, normalized)) {
                (nameMatches - "GE") + "US"
            } else {
                nameMatches
            }
            if (resolved.size == 1) return resolved.single()
            if (resolved.size > 1) return null
        }

        extractUppercaseIsoCode(segment)?.let { return it }

        val cityMatches = findPhraseMatches(normalized, sortedCityPhrases)
        if (cityMatches.size == 1) return cityMatches.single()
        return null
    }

    private fun hasUsDisambiguation(raw: String, normalized: String): Boolean {
        if (usDisambiguationTokens.any { containsPhrase(normalized, it) }) return true
        return extractUppercaseIsoCodes(raw).contains("US")
    }

    private fun extractFlagCode(text: String): String? {
        val codes = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + charCount < text.length) {
                val nextCp = text.codePointAt(i + charCount)
                if (nextCp in 0x1F1E6..0x1F1FF) {
                    val c1 = ('A'.code + (cp - 0x1F1E6)).toChar()
                    val c2 = ('A'.code + (nextCp - 0x1F1E6)).toChar()
                    normalizeCode("$c1$c2")?.let { codes += it }
                    i += charCount + Character.charCount(nextCp)
                    continue
                }
            }
            // GB subdivision flags (England / Scotland / Wales)
            if (cp == 0x1F3F4 && i + charCount < text.length) {
                val rest = text.substring(i + charCount)
                if (rest.startsWith("\uDB40\uDC67\uDB40\uDC62")) {
                    codes += "GB"
                }
            }
            i += charCount
        }
        return codes.singleOrNull()
    }

    private fun extractUppercaseIsoCode(text: String): String? =
        extractUppercaseIsoCodes(text).singleOrNull()

    private fun extractUppercaseIsoCodes(text: String): Set<String> {
        val codes = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in 'A'..'Z') {
                val start = i
                while (i < text.length && text[i] in 'A'..'Z') i++
                val token = text.substring(start, i)
                val beforeOk = start == 0 || isIsoBoundary(text.codePointBefore(start))
                val afterOk = i == text.length || isIsoBoundary(text.codePointAt(i))
                if (beforeOk && afterOk) {
                    when (token.length) {
                        2 -> if (token in safeTwoLetterCodes) {
                            normalizeCode(token)?.let { codes += it }
                        }
                        3 -> safeThreeLetterCodes[token]?.let { codes += it }
                    }
                }
            } else {
                i++
            }
        }
        return codes
    }

    private fun isIsoBoundary(cp: Int): Boolean {
        if (cp == '.'.code) return false
        return !Character.isLetterOrDigit(cp)
    }

    private fun findPhraseMatches(
        normalized: String,
        phrases: List<Pair<String, String>>
    ): Set<String> {
        val matchedCodes = LinkedHashSet<String>()
        val occupied = BooleanArray(normalized.length)
        for ((phrase, code) in phrases) {
            var searchFrom = 0
            while (searchFrom <= normalized.length - phrase.length) {
                val idx = normalized.indexOf(phrase, searchFrom)
                if (idx < 0) break
                val end = idx + phrase.length
                val beforeOk = idx == 0 || !Character.isLetterOrDigit(normalized.codePointBefore(idx))
                val afterOk = end == normalized.length || !Character.isLetterOrDigit(normalized.codePointAt(end))
                if (beforeOk && afterOk && (idx until end).none { occupied[it] }) {
                    for (pos in idx until end) occupied[pos] = true
                    matchedCodes += code
                }
                searchFrom = idx + 1
            }
        }
        return matchedCodes
    }

    private fun containsPhrase(normalized: String, phrase: String): Boolean {
        var searchFrom = 0
        while (searchFrom <= normalized.length - phrase.length) {
            val idx = normalized.indexOf(phrase, searchFrom)
            if (idx < 0) break
            val end = idx + phrase.length
            val beforeOk = idx == 0 || !Character.isLetterOrDigit(normalized.codePointBefore(idx))
            val afterOk = end == normalized.length || !Character.isLetterOrDigit(normalized.codePointAt(end))
            if (beforeOk && afterOk) return true
            searchFrom = idx + 1
        }
        return false
    }

    private fun normalizeForMatch(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            when (ch) {
                '\u200C', '\u200D', '\u00A0' -> sb.append(' ')
                'ي', 'ى' -> sb.append('ی')
                'ك' -> sb.append('ک')
                'ة' -> sb.append('ه')
                'أ', 'إ', 'آ' -> sb.append('ا')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }
}
