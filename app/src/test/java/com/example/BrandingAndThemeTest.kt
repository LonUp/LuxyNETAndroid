package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.EncryptedConfigVault
import com.example.core.SubscriptionFetcher
import com.example.data.local.SettingsDataStore
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ColorPalette
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeMode
import com.example.ui.LuxyVpnUiState
import com.example.ui.screens.StabilityRulesScreen
import com.example.ui.theme.LuxyVpnTheme
import com.example.ui.theme.paletteColorScheme
import com.example.ui.theme.statusColorsFor
import com.example.xraycore.LuxyVpnCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrandingAndThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun brandConstantsAndEnumsAreStrictlyLuxyVpn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LuxyVPN", context.getString(R.string.app_name))
        assertEquals("LuxyVPN/3.0", SubscriptionFetcher.DEFAULT_USER_AGENT)
        assertTrue(LuxyVpnCore.CORE_VERSION.startsWith("LuxyVpnCore"))
        assertEquals("luxy-zsx://", EncryptedConfigVault.PREFIX_ZSX)

        val presetNames = AntiCensorshipPreset.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "BALANCED_STEALTH",
                "STRICT_DPI_BYPASS",
                "CDN_FRONTING",
                "MAX_STEALTH",
                "DIRECT_LOW_LATENCY",
                "CUSTOM"
            ),
            presetNames
        )
    }

    @Test
    fun allEightColorPalettesProduceDistinctValidDarkAndLightSchemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(8, ColorPalette.entries.size)

        val darkPrimaries = mutableListOf<Pair<ColorPalette, androidx.compose.ui.graphics.Color>>()
        val lightPrimaries = mutableListOf<Pair<ColorPalette, androidx.compose.ui.graphics.Color>>()

        ColorPalette.entries.forEach { palette ->
            val darkScheme = paletteColorScheme(palette, isDark = true, context = context)
            val lightScheme = paletteColorScheme(palette, isDark = false, context = context)
            val darkStatus = statusColorsFor(palette, isDark = true, colorScheme = darkScheme)
            val lightStatus = statusColorsFor(palette, isDark = false, colorScheme = lightScheme)

            assertNotEquals("Dark background and onBackground must differ for $palette", darkScheme.background, darkScheme.onBackground)
            assertNotEquals("Light background and onBackground must differ for $palette", lightScheme.background, lightScheme.onBackground)
            assertNotEquals("Dark primary and onPrimary must differ for $palette", darkScheme.primary, darkScheme.onPrimary)
            assertNotEquals("Light primary and onPrimary must differ for $palette", lightScheme.primary, lightScheme.onPrimary)
            assertNotEquals("Connected and error status colors must differ for $palette", darkStatus.connected, darkStatus.error)
            assertNotEquals("Connected and error light status colors must differ for $palette", lightStatus.connected, lightStatus.error)

            darkPrimaries.add(palette to darkScheme.primary)
            lightPrimaries.add(palette to lightScheme.primary)
        }

        // Static palettes (excluding dynamic MATERIAL_YOU fallback) must have distinct dark backgrounds
        val staticDarkBgs = ColorPalette.entries
            .filter { it != ColorPalette.MATERIAL_YOU }
            .map { paletteColorScheme(it, isDark = true, context = context).background }
            .toSet()
        assertEquals(7, staticDarkBgs.size)
    }

    @Test
    fun settingsDataStorePersistsColorPaletteAndThemeMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.setThemeMode(ThemeMode.LIGHT)
        store.setColorPalette(ColorPalette.TOKYO_NIGHT)
        var loaded = store.settingsFlow.first()
        assertEquals(ThemeMode.LIGHT, loaded.themeMode)
        assertEquals(ColorPalette.TOKYO_NIGHT, loaded.colorPalette)

        store.setThemeMode(ThemeMode.SYSTEM)
        store.setColorPalette(ColorPalette.GRUVBOX)
        loaded = store.settingsFlow.first()
        assertEquals(ThemeMode.SYSTEM, loaded.themeMode)
        assertEquals(ColorPalette.GRUVBOX, loaded.colorPalette)
    }

    @Test
    fun settingsPaletteGalleryRendersAllEightPalettesAndSwitchesSelection() {
        var selectedPalette: ColorPalette? = null
        val uiState = LuxyVpnUiState(
            settings = StabilitySettings(
                themeMode = ThemeMode.DARK,
                colorPalette = ColorPalette.LUXY_OBSIDIAN_GOLD
            )
        )

        composeTestRule.setContent {
            LuxyVpnTheme(
                themeMode = uiState.settings.themeMode,
                colorPalette = uiState.settings.colorPalette
            ) {
                StabilityRulesScreen(
                    uiState = uiState,
                    onSetThemeMode = {},
                    onSetColorPalette = { selectedPalette = it },
                    onSetAutoConnectOnLaunch = {},
                    onSetAutoReconnect = {},
                    onSetSmartFailover = {},
                    onSetMtu = {},
                    onSetRoutingMode = {},
                    onToggleRule = { _, _ -> },
                    onSaveRule = {},
                    onDeleteRule = {},
                    onSetSplitTunnelConfig = { _, _, _ -> },
                    onSetPrivacyOpSecConfig = { _, _, _, _ -> },
                    onOpenExpertLab = {}
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("palette_card_luxy_obsidian_gold"))
        composeTestRule.onNodeWithText("Color Palette").assertIsDisplayed()
        ColorPalette.entries.forEach { palette ->
            val tag = "palette_card_${palette.name.lowercase()}"
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
            composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("palette_card_catppuccin"))
        composeTestRule.onNodeWithTag("palette_card_catppuccin").performClick()
        assertEquals(ColorPalette.CATPPUCCIN, selectedPalette)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("palette_card_dracula"))
        composeTestRule.onNodeWithTag("palette_card_dracula").performClick()
        assertEquals(ColorPalette.DRACULA, selectedPalette)
    }
}
