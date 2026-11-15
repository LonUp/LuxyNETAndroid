package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemePalette
import com.example.ui.components.SubscriptionHero
import com.example.ui.screens.AppearanceScreen
import com.example.ui.screens.buildDiagnostics
import com.example.ui.theme.LuxyVpnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the P2 surfaces (subscription hero, theme editor) in Robolectric so their visible
 * contracts break the build instead of waiting for a device screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2SurfacesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }

    private fun subscription(
        totalBytes: Long = 10L * GIB,
        usedBytes: Long = 2560L * 1024L * 1024L,
        expiresInSec: Long? = 5L * 86_400L + 3_600L
    ) = SubscriptionEntity(
        id = "sub-panel",
        name = "Panel",
        url = "https://panel.example/sub",
        lastUpdatedAt = System.currentTimeMillis(),
        nodeCount = 12,
        lastStatus = "Synced 12 nodes",
        downloadBytes = usedBytes,
        totalBytes = totalBytes,
        expireAtEpochSec = expiresInSec?.let { System.currentTimeMillis() / 1000L + it } ?: 0L
    )

    @Test
    fun heroShowsRemainingQuotaExpiryAndPercent() {
        var refreshes = 0
        composeRule.setContent {
            LuxyVpnTheme {
                SubscriptionHero(subscription = subscription(), onRefresh = { refreshes++ })
            }
        }

        composeRule.onNodeWithText("7.50 GB").assertIsDisplayed()
        composeRule.onNodeWithText("/ 10.00 GB").assertIsDisplayed()
        composeRule.onNodeWithText("5 days left").assertIsDisplayed()
        composeRule.onNodeWithText("25% used").assertIsDisplayed()
        composeRule.onNodeWithText("Panel").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Refresh subscription").performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun heroWithoutQuotaShowsUsageAndNoPercentChip() {
        composeRule.setContent {
            LuxyVpnTheme {
                SubscriptionHero(
                    subscription = subscription(totalBytes = 0L, expiresInSec = null),
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithText("2.50 GB used").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("% used", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("days left", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun expiredPlanIsLabelled() {
        composeRule.setContent {
            LuxyVpnTheme {
                SubscriptionHero(
                    subscription = subscription(expiresInSec = -3_600L),
                    onRefresh = {}
                )
            }
        }
        composeRule.onNodeWithText("Expired").assertIsDisplayed()
    }

    @Test
    fun appearancePresetTapWritesTheWholeTheme() {
        var applied by mutableStateOf(ThemeConfig())
        composeRule.setContent {
            LuxyVpnTheme(applied) {
                AppearanceScreen(
                    theme = applied,
                    onChange = { applied = it },
                    onBack = {},
                    onMessage = {}
                )
            }
        }

        composeRule.onNodeWithText("Forest emerald").performClick()
        assertEquals("forest-emerald", applied.presetId)
        assertEquals("3DD68C", applied.dark.accent)
        // The light palette travels with the preset instead of being left behind.
        assertEquals("14784B", applied.light.accent)
        composeRule.onNodeWithText("Theme code").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun simpleModeHidesAdvancedSections() {
        composeRule.setContent {
            LuxyVpnTheme {
                AppearanceScreen(
                    theme = ThemeConfig(simpleMode = true),
                    onChange = {},
                    onBack = {},
                    onMessage = {}
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("Typography").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Orb shape").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Dark palette").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun editingAColourSwitchesThePresetToCustom() {
        val edited = ThemeConfig().withPalette(
            isDark = true,
            palette = ThemePalette(background = "111111", card = "222222", accent = "333333")
        )
        assertEquals(ThemeConfig.CUSTOM_PRESET_ID, edited.presetId)
        assertEquals("111111", edited.dark.background)
        assertEquals("F0994B", ThemeConfig().dark.accent)
    }

    @Test
    fun diagnosticsCarryCountsAndCapTheLogTail() {
        val events = (1..40).map { index ->
            StabilityEventEntity(
                id = index.toLong(),
                timestamp = 1_700_000_000_000L + index * 1_000L,
                level = "INFO",
                category = "TUNNEL",
                message = "event $index"
            )
        }
        val text = buildDiagnostics(
            versionName = "3.0",
            nodeCount = 12,
            stateName = "CONNECTED",
            transport = "Wi-Fi",
            logLines = events
        )
        assertTrue(text.contains("state=CONNECTED transport=Wi-Fi nodes=12"))
        assertTrue(text.contains("event 1"))
        assertTrue(text.contains("event 30"))
        assertFalse(text.contains("event 31"))
        assertFalse(text.contains("203.0.113"))
    }
}
