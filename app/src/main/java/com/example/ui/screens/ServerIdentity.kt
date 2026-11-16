package com.example.ui.screens

import com.example.i18n.Digits
import com.example.i18n.UiText
import java.util.Locale

/**
 * Turns a stored profile remark into a name and country a person can read.
 * Endpoints, ports, protocols and transports are dropped. Nothing is invented:
 * a country is shown only when the remark actually carries one.
 */
object ServerIdentity {

    data class Display(
        val title: String,
        val countryName: String,
        val countryCode: String?
    )

    fun resolve(name: String, groupName: String, language: String?): Display {
        val codeFromFlag = regionalCode(name)
        val rawTokens = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        val cleaned = stripNoise(name)
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in junk }
        val code = codeFromFlag
            ?: rawTokens.firstNotNullOfOrNull { countryCodeOf(it) }
            ?: tokens.firstNotNullOfOrNull { countryCodeOf(it) }
            ?: countryCodeOf(groupName)
            ?: knownNameCode(cleaned)
        val index = tokens.firstOrNull { it.length <= 2 && it.all(Char::isDigit) }
        val words = tokens.filter { token ->
            token != index && countryCodeOf(token) == null && !token.equals(code, true)
        }
        val phrase = words.joinToString(" ").trim()
        val countryName = code?.let { localizedCountry(it, language) }.orEmpty()
        val serverLabel = index?.let {
            "${UiText.translate(language, "Server")} ${Digits.localize(it, language)}"
        }
        val title = when {
            phrase.isNotBlank() && !phrase.equals(countryName, true) -> Digits.localize(phrase, language)
            serverLabel != null -> serverLabel
            countryName.isNotBlank() -> countryName
            else -> UiText.translate(language, "Server")
        }
        return Display(title = title, countryName = countryName, countryCode = code)
    }

    private fun stripNoise(raw: String): String {
        val noFlags = buildString {
            raw.codePoints().forEach { cp ->
                if (cp !in 0x1F1E6..0x1F1FF) appendCodePoint(cp)
            }
        }
        return noFlags
            .replace(Regex(":\\d{2,5}\\b"), " ")
            .replace(Regex("(?i)\\b(vless|vmess|trojan|hysteria2?|tuic|wireguard|amneziawg|reality|xtls|tls|tcp|udp|ws|grpc|xhttp|http|h2|quic|mkcp|shadowsocks|ssr?)\\b"), " ")
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), " ")
            .replace(Regex("(?i)\\b[a-z0-9-]+\\.[a-z]{2,}\\b"), " ")
            .replace(Regex("[:/#|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun regionalCode(text: String): String? {
        val points = text.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                return "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
            }
        }
        return null
    }

    private fun countryCodeOf(token: String): String? {
        val upper = token.trim().uppercase(Locale.ROOT)
        aliases[upper]?.let { return it }
        if (upper.length != 2 || !upper.all(Char::isLetter)) return null
        return upper.takeIf { localizedCountry(it, "en").isNotBlank() }
    }

    private fun knownNameCode(text: String): String? {
        val key = text.lowercase(Locale.ROOT)
        return englishNames.entries.firstOrNull { key.contains(it.key) }?.value
    }

    private fun localizedCountry(code: String, language: String?): String {
        val locale = Locale.forLanguageTag(language ?: "en")
        val name = Locale("", code).getDisplayCountry(locale)
        if (name.isBlank() || name.equals(code, true)) return ""
        return name
    }

    private val junk = setOf("com", "net", "org", "io", "www", "cdn", "node", "vip")

    private val aliases = mapOf(
        "UAE" to "AE",
        "UK" to "GB",
        "USA" to "US",
        "KOR" to "KR",
        "GER" to "DE",
        "NED" to "NL",
        "SGP" to "SG",
        "JPN" to "JP"
    )

    private val englishNames = mapOf(
        "united arab emirates" to "AE",
        "united states" to "US",
        "united kingdom" to "GB",
        "germany" to "DE",
        "netherlands" to "NL",
        "france" to "FR",
        "turkey" to "TR",
        "japan" to "JP",
        "singapore" to "SG",
        "finland" to "FI",
        "sweden" to "SE",
        "canada" to "CA",
        "australia" to "AU",
        "iran" to "IR"
    )
}
