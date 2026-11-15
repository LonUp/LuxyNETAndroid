package com.example.data.model

import java.util.Locale

/**
 * Text codec behind "share theme code" (reference: Settings → theme → share).
 *
 * Wire format: `LX1.` + base64url(no padding) of `key:value` pairs joined by `|`.
 * - Encoding is deterministic (fixed key order, values quantised to 2 decimals).
 * - Decoding is tolerant per field: unknown keys are ignored, invalid values fall back to
 *   the corresponding default, so a code from a newer build never crashes an older one.
 * - Anything structurally broken (wrong prefix, bad base64, empty payload) returns `null`.
 */
object ThemeCodec {

    const val PREFIX = "LX1."
    private const val SEP = '|'
    private const val KV = ':'
    private const val MAX_CODE_LENGTH = 1024

    private const val KEY_PRESET = "p"
    private const val KEY_MODE = "m"
    private const val KEY_DARK_BG = "db"
    private const val KEY_DARK_CARD = "dc"
    private const val KEY_DARK_ACCENT = "da"
    private const val KEY_LIGHT_BG = "lb"
    private const val KEY_LIGHT_CARD = "lc"
    private const val KEY_LIGHT_ACCENT = "la"
    private const val KEY_FONT = "f"
    private const val KEY_FONT_SCALE = "fs"
    private const val KEY_BOLD = "b"
    private const val KEY_RADIUS = "r"
    private const val KEY_PATTERN = "pa"
    private const val KEY_CARD_STYLE = "cs"
    private const val KEY_ORB_SHAPE = "os"
    private const val KEY_ORB_DOCK = "od"
    private const val KEY_MOTION = "mo"
    private const val KEY_SIMPLE = "sm"

    private val KNOWN_KEYS = setOf(
        KEY_PRESET, KEY_MODE, KEY_DARK_BG, KEY_DARK_CARD, KEY_DARK_ACCENT,
        KEY_LIGHT_BG, KEY_LIGHT_CARD, KEY_LIGHT_ACCENT, KEY_FONT, KEY_FONT_SCALE,
        KEY_BOLD, KEY_RADIUS, KEY_PATTERN, KEY_CARD_STYLE, KEY_ORB_SHAPE,
        KEY_ORB_DOCK, KEY_MOTION, KEY_SIMPLE
    )

    private val HEX = Regex("^[0-9A-Fa-f]{6}$")
    private val PRESET_ID = Regex("^[A-Za-z0-9._-]{1,40}$")

    fun encode(config: ThemeConfig): String {
        val c = normalize(config)
        val pairs = listOf(
            KEY_PRESET to c.presetId,
            KEY_MODE to c.mode.name,
            KEY_DARK_BG to c.dark.background,
            KEY_DARK_CARD to c.dark.card,
            KEY_DARK_ACCENT to c.dark.accent,
            KEY_LIGHT_BG to c.light.background,
            KEY_LIGHT_CARD to c.light.card,
            KEY_LIGHT_ACCENT to c.light.accent,
            KEY_FONT to c.font.name,
            KEY_FONT_SCALE to formatScale(c.fontScale),
            KEY_BOLD to bool(c.boldText),
            KEY_RADIUS to formatScale(c.radiusScale),
            KEY_PATTERN to c.pattern.name,
            KEY_CARD_STYLE to c.cardStyle.name,
            KEY_ORB_SHAPE to c.orbShape.name,
            KEY_ORB_DOCK to c.orbDock.name,
            KEY_MOTION to bool(c.motionEnabled),
            KEY_SIMPLE to bool(c.simpleMode)
        )
        val payload = pairs.joinToString(SEP.toString()) { (k, v) -> "$k$KV$v" }
        return PREFIX + Base64Url.encode(payload.toByteArray(Charsets.UTF_8))
    }

    /** Returns `null` only for structurally invalid input; fields degrade individually. */
    fun decode(code: String?): ThemeConfig? {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_CODE_LENGTH) return null
        if (!trimmed.startsWith(PREFIX)) return null
        val bytes = Base64Url.decode(trimmed.substring(PREFIX.length)) ?: return null
        val payload = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (payload.isBlank()) return null

        var config = ThemeConfig()
        var sawKnownKey = false
        for (entry in payload.split(SEP)) {
            val at = entry.indexOf(KV)
            if (at <= 0) continue
            val key = entry.substring(0, at).trim()
            val value = entry.substring(at + 1).trim()
            if (value.isEmpty() || key !in KNOWN_KEYS) continue
            sawKnownKey = true
            when (key) {
                KEY_PRESET -> {
                    if (PRESET_ID.matches(value)) {
                        config = config.copy(presetId = value)
                    }
                }
                KEY_MODE -> ThemeMode.parse(value).let { config = config.copy(mode = it) }
                KEY_DARK_BG -> hex(value)?.let { config = config.copy(dark = config.dark.copy(background = it)) }
                KEY_DARK_CARD -> hex(value)?.let { config = config.copy(dark = config.dark.copy(card = it)) }
                KEY_DARK_ACCENT -> hex(value)?.let { config = config.copy(dark = config.dark.copy(accent = it)) }
                KEY_LIGHT_BG -> hex(value)?.let { config = config.copy(light = config.light.copy(background = it)) }
                KEY_LIGHT_CARD -> hex(value)?.let { config = config.copy(light = config.light.copy(card = it)) }
                KEY_LIGHT_ACCENT -> hex(value)?.let { config = config.copy(light = config.light.copy(accent = it)) }
                KEY_FONT -> config = config.copy(font = FontChoice.parse(value))
                KEY_FONT_SCALE -> scale(value)?.let { config = config.copy(fontScale = it) }
                KEY_BOLD -> boolValue(value)?.let { config = config.copy(boldText = it) }
                KEY_RADIUS -> scale(value)?.let { config = config.copy(radiusScale = it) }
                KEY_PATTERN -> config = config.copy(pattern = ThemePattern.parse(value))
                KEY_CARD_STYLE -> config = config.copy(cardStyle = CardStyle.parse(value))
                KEY_ORB_SHAPE -> config = config.copy(orbShape = OrbShape.parse(value))
                KEY_ORB_DOCK -> config = config.copy(orbDock = OrbDockPosition.parse(value))
                KEY_MOTION -> boolValue(value)?.let { config = config.copy(motionEnabled = it) }
                KEY_SIMPLE -> boolValue(value)?.let { config = config.copy(simpleMode = it) }
            }
        }
        return if (sawKnownKey) normalize(config) else null
    }

    /** Clamps are part of the contract: the UI and the codec agree on the legal ranges. */
    fun normalize(config: ThemeConfig): ThemeConfig = config.copy(
        presetId = config.presetId.takeIf { PRESET_ID.matches(it) } ?: ThemeConfig.PRESET_GRAPHITE_AMBER,
        fontScale = quantize(config.fontScale, MIN_SCALE, MAX_SCALE),
        radiusScale = quantize(config.radiusScale, MIN_RADIUS, MAX_RADIUS),
        dark = config.dark.normalized(),
        light = config.light.normalized()
    )

    private fun ThemePalette.normalized(): ThemePalette = ThemePalette(
        background = hex(background) ?: background.uppercase(Locale.ROOT),
        card = hex(card) ?: card.uppercase(Locale.ROOT),
        accent = hex(accent) ?: accent.uppercase(Locale.ROOT)
    )

    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.30f
    const val MIN_RADIUS = 0.6f
    const val MAX_RADIUS = 1.6f

    private fun quantize(value: Float, min: Float, max: Float): Float {
        if (value.isNaN()) return 1f
        val clamped = value.coerceIn(min, max)
        return (Math.round(clamped * 100f) / 100f)
    }

    private fun formatScale(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    private fun scale(value: String): Float? = value.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun bool(value: Boolean): String = if (value) "1" else "0"

    private fun boolValue(value: String): Boolean? = when (value.lowercase(Locale.ROOT)) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    /** Accepts `RRGGBB` or `#RRGGBB`; returns canonical uppercase without the hash. */
    fun hex(value: String?): String? {
        val raw = value?.trim()?.removePrefix("#") ?: return null
        return if (HEX.matches(raw)) raw.uppercase(Locale.ROOT) else null
    }
}

/** Minimal base64url without padding (java.util.Base64 is API 26; minSdk is 24). */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val REVERSE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
            out.append(ALPHABET[(n ushr 12) and 0x3F])
            out.append(ALPHABET[(n ushr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append(ALPHABET[(n ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val index = if (c.code < 128) REVERSE[c.code] else -1
            if (index < 0) return null
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
