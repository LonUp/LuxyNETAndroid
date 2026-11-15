package com.example

import com.example.data.model.FontChoice
import com.example.data.model.CardStyle
import com.example.data.model.OrbDockPosition
import com.example.data.model.OrbShape
import com.example.data.model.ThemeCodec
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemeMode
import com.example.data.model.ThemePalette
import com.example.data.model.ThemePattern
import com.example.data.model.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ThemeCodecTest {

    private fun payload(pairs: String): String =
        "LX1." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(pairs.toByteArray(Charsets.UTF_8))

    @Test
    fun defaultConfigRoundTrips() {
        val config = ThemeConfig()
        val code = ThemeCodec.encode(config)
        assertTrue(code.startsWith("LX1."))
        assertEquals(config, ThemeCodec.decode(code))
    }

    @Test
    fun customConfigRoundTripsEveryDimension() {
        val config = ThemeConfig(
            presetId = ThemeConfig.CUSTOM_PRESET_ID,
            mode = ThemeMode.LIGHT,
            dark = ThemePalette("101010", "202020", "F0994B"),
            light = ThemePalette("F1EADB", "FFFDF7", "B45309"),
            font = FontChoice.SYSTEM,
            fontScale = 1.15f,
            boldText = true,
            radiusScale = 1.4f,
            pattern = ThemePattern.DOTS,
            cardStyle = CardStyle.OUTLINE,
            orbShape = OrbShape.HEXAGON,
            orbDock = OrbDockPosition.FLOATING,
            motionEnabled = false,
            simpleMode = true
        )
        assertEquals(config, ThemeCodec.decode(ThemeCodec.encode(config)))
    }

    @Test
    fun encodingIsDeterministic() {
        val config = ThemePreset.MIDNIGHT_VIOLET.applyTo(ThemeConfig())
        assertEquals(ThemeCodec.encode(config), ThemeCodec.encode(config))
    }

    @Test
    fun structurallyInvalidCodesReturnNull() {
        assertNull(ThemeCodec.decode(""))
        assertNull(ThemeCodec.decode("   "))
        assertNull(ThemeCodec.decode("graphite-amber"))
        assertNull(ThemeCodec.decode("LX1.!!!not-base64!!!"))
        assertNull(ThemeCodec.decode("XX1.cD1ncmFwaGl0ZS1hbWJlcg"))
        assertNull(ThemeCodec.decode("LX1."))
        assertNull(ThemeCodec.decode("LX1." + "A".repeat(2000)))
    }

    @Test
    fun unknownKeysAreIgnoredButKnownKeysApply() {
        val decoded = ThemeCodec.decode(payload("p:forest-emerald|zz:future-value|m:DARK"))
        assertNotNull(decoded)
        assertEquals("forest-emerald", decoded!!.presetId)
        assertEquals(ThemeMode.DARK, decoded.mode)
    }

    @Test
    fun invalidFieldsFallBackToDefaults() {
        val decoded = ThemeCodec.decode(payload("p:graphite-amber|db:ZZZZZZ|fs:not-a-number|os:PARALLELOGRAM"))
        assertNotNull(decoded)
        assertEquals(ThemePalette.GRAPHITE_AMBER_DARK.background, decoded!!.dark.background)
        assertEquals(1f, decoded.fontScale, 0.001f)
        assertEquals(OrbShape.CIRCLE, decoded.orbShape)
    }

    @Test
    fun lowercaseHexIsNormalisedAndHashAccepted() {
        val decoded = ThemeCodec.decode(payload("db:1a1a1a|dc:242424|da:f0994b"))
        assertNotNull(decoded)
        assertEquals("1A1A1A", decoded!!.dark.background)
        assertEquals("F0994B", decoded.dark.accent)
    }

    @Test
    fun scalesAreClampedToTheLegalRange() {
        val tooBig = ThemeCodec.decode(payload("fs:9.00|r:9.00"))
        assertEquals(ThemeCodec.MAX_SCALE, tooBig!!.fontScale, 0.001f)
        assertEquals(ThemeCodec.MAX_RADIUS, tooBig.radiusScale, 0.001f)

        val tooSmall = ThemeCodec.decode(payload("fs:0.10|r:0.10"))
        assertEquals(ThemeCodec.MIN_SCALE, tooSmall!!.fontScale, 0.001f)
        assertEquals(ThemeCodec.MIN_RADIUS, tooSmall.radiusScale, 0.001f)
    }

    @Test
    fun presetsCarryBothModesAndSwitchPresetIdOnly() {
        val chosen = ThemePreset.OCEAN_CYAN.applyTo(ThemeConfig(), keepMode = ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, chosen.mode)
        assertEquals(ThemePreset.OCEAN_CYAN.dark, chosen.dark)
        assertEquals(ThemePreset.OCEAN_CYAN.light, chosen.light)
        assertEquals(ThemeConfig.PRESET_GRAPHITE_AMBER, ThemePreset.apply("unknown", ThemeConfig()).presetId)
    }

    @Test
    fun tenPresetsAreDistinct() {
        assertEquals(10, ThemePreset.entries.size)
        assertEquals(10, ThemePreset.entries.map { it.dark.background }.distinct().size)
    }
}
