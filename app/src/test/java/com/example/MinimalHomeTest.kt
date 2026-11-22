package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.core.CountryOption
import com.example.data.model.ConnectionSourceSummary
import com.example.data.model.ConnectionState
import com.example.data.model.TunnelTelemetry
import com.example.ui.MinimalHome
import com.example.ui.theme.LuxyVpnTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimalHomeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View

    private fun render(
        state: ConnectionState = ConnectionState.DISCONNECTED,
        servers: Int = 3,
        importing: Boolean = false,
        selecting: Boolean = false,
        language: String = "en",
        fontScale: Float = 1f,
        onSubscriptions: (() -> Unit)? = null,
        selectedCountry: CountryOption? = null,
        availableCountries: List<CountryOption> = emptyList(),
        countryCounts: Map<String, Int> = emptyMap(),
        selectedCountryAvailable: Boolean = true,
        onSelectCountry: (String?) -> Unit = {},
        onConfirmCountrySwitch: (String?) -> Unit = {},
        enabledSourceCount: Int = if (servers > 0) 1 else 0,
        totalStoredCount: Int = servers,
        connectionSources: List<ConnectionSourceSummary> = emptyList(),
        sourceChangePendingReconnect: Boolean = false,
        onToggleSubscriptionSource: (String, Boolean) -> Unit = { _, _ -> },
        onToggleManualSource: (Boolean) -> Unit = {},
        telemetry: TunnelTelemetry = TunnelTelemetry(connectionState = state),
        detailsExpanded: Boolean = false,
        toggleDetailsState: Boolean = false,
        onToggleDetailsExpanded: () -> Unit = {}
    ) {
        compose.setContent {
            var expandedDetails by remember { mutableStateOf(detailsExpanded) }
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            hostView = LocalView.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                LuxyVpnTheme(MinimalTheme) {
                    MinimalHome(
                        state = state,
                        serverCount = servers,
                        importing = importing,
                        selecting = selecting,
                        permissionPending = false,
                        snackbar = remember { SnackbarHostState() },
                        onConnect = {},
                        onSubscriptions = onSubscriptions,
                        selectedCountry = selectedCountry,
                        availableCountries = availableCountries,
                        countryCounts = countryCounts,
                        selectedCountryAvailable = selectedCountryAvailable,
                        onSelectCountry = onSelectCountry,
                        onConfirmCountrySwitch = onConfirmCountrySwitch,
                        enabledSourceCount = enabledSourceCount,
                        totalStoredCount = totalStoredCount,
                        connectionSources = connectionSources,
                        sourceChangePendingReconnect = sourceChangePendingReconnect,
                        onToggleSubscriptionSource = onToggleSubscriptionSource,
                        onToggleManualSource = onToggleManualSource,
                        telemetry = telemetry,
                        detailsExpanded = if (toggleDetailsState) expandedDetails else detailsExpanded,
                        onToggleDetailsExpanded = {
                            if (toggleDetailsState) expandedDetails = !expandedDetails
                            onToggleDetailsExpanded()
                        }
                    )
                }
            }
        }
    }

    @Test fun importingCannotConnectOrOpenLocationSelector() {
        render(importing = true)
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Select location: Automatic").assertIsNotEnabled()
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
    }

    @Test fun automaticSelectionCanBeCancelledWithoutHomeImportButtons() {
        render(selecting = true, servers = 0)
        compose.onNodeWithContentDescription("Cancel").assertIsEnabled()
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
    }

    @Test fun failedConnectionCanBeRetriedWithoutSelectingAServer() {
        render(state = ConnectionState.ERROR)
        compose.onNodeWithContentDescription("Connect").assertIsEnabled()
    }

    @Test fun emptyLibraryDoesNotOfferAnUnusableConnectionAndGuidesToSubscriptions() {
        var openedSubscriptions = false
        render(servers = 0, onSubscriptions = { openedSubscriptions = true })
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
        compose.onNodeWithText("Add configuration").assertIsDisplayed().performClick()
        assertTrue(openedSubscriptions)
        screenshot("empty-en")
    }

    @Test fun largePersianTextKeepsEssentialActionsReachable() {
        var openedSubscriptions = false
        render(language = "fa", fontScale = 1.8f, onSubscriptions = { openedSubscriptions = true })
        compose.onNodeWithContentDescription("اتصال").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("ready-fa-large")
        compose.onNodeWithText("اشتراک‌ها").assertIsDisplayed().performClick()
        assertTrue(openedSubscriptions)
    }

    @Test fun connectedStateKeepsDisconnectAvailableAndShowsSourceAndConfigCounts() {
        render(
            state = ConnectionState.CONNECTED,
            servers = 11,
            enabledSourceCount = 2,
            selectedCountry = CountryOption("AL", "Albania", "🇦🇱"),
            onSubscriptions = {}
        )
        compose.onNodeWithContentDescription("Disconnect").assertIsEnabled()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Albania").assertIsDisplayed()
        compose.onNodeWithText("2 sources enabled").assertIsDisplayed()
        compose.onNodeWithText("11 matching configurations").assertIsDisplayed()
        compose.onAllNodesWithText("servers ready", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
        screenshot("connected-en")
    }
    @Test fun automaticSelectionCountsEligibleConfigurations() {
        render(servers = 3)
        compose.onNodeWithText("3 eligible configurations").assertIsDisplayed()
    }

    @Test fun allSourcesDisabledPreventsConnectAndOpensConnectionSourcesSheet() {
        var toggledSub: Pair<String, Boolean>? = null
        val sources = listOf(
            ConnectionSourceSummary(
                id = "sub-1",
                isManual = false,
                name = "Premium Europe",
                totalCount = 12,
                eligibleCount = 7,
                isEnabledForConnection = false,
                isConnected = false,
                hasError = false
            )
        )
        render(
            servers = 0,
            enabledSourceCount = 0,
            totalStoredCount = 12,
            connectionSources = sources,
            onToggleSubscriptionSource = { id, enabled -> toggledSub = id to enabled }
        )
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onNodeWithText("All connection sources are disabled.").assertIsDisplayed()
        compose.onNodeWithText("Enable a source").assertIsDisplayed().performClick()
        compose.onNodeWithText("Connection Sources").assertIsDisplayed()
        compose.onAllNodesWithText("Changes apply to the next connection.").assertCountEquals(0)
        compose.onNodeWithText("Premium Europe").assertIsDisplayed()
        compose.onNodeWithText("12 stored · 7 eligible").assertIsDisplayed()
        compose.onNodeWithContentDescription("Premium Europe").performClick()
        assertEquals("sub-1" to true, toggledSub)
    }

    @Test fun pendingSourceChangeShowsNextConnectionNoteEvenWhileDisconnected() {
        val sources = listOf(
            ConnectionSourceSummary(
                id = "sub-1",
                isManual = false,
                name = "Premium Europe",
                totalCount = 12,
                eligibleCount = 7,
                isEnabledForConnection = false,
                isConnected = false,
                hasError = false
            )
        )
        render(
            servers = 0,
            enabledSourceCount = 0,
            totalStoredCount = 12,
            connectionSources = sources,
            sourceChangePendingReconnect = true
        )
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Enable a source").performClick()
        compose.onNodeWithText("Connection Sources").assertIsDisplayed()
        compose.onNodeWithText("Changes apply to the next connection.").assertIsDisplayed()
    }
    @Test fun connectedPersianStateKeepsDisconnectAvailable() {
        render(state = ConnectionState.CONNECTED, language = "fa", onSubscriptions = {})
        compose.onNodeWithContentDescription("قطع اتصال").assertIsEnabled()
        screenshot("connected-fa")
    }

    @Test fun locationSelectorOpensSheetAndSelectsCountryWhenDisconnected() {
        var selected: String? = "UNSET"
        val countries = listOf(
            CountryOption("DE", "Germany", "🇩🇪"),
            CountryOption("US", "United States", "🇺🇸")
        )
        render(
            availableCountries = countries,
            countryCounts = mapOf("DE" to 2, "US" to 1),
            onSelectCountry = { selected = it }
        )
        compose.onNodeWithText("Automatic").assertIsDisplayed().performClick()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithText("Germany").assertIsDisplayed()
        compose.onAllNodesWithText("2 matching configurations").assertCountEquals(0)
        compose.onNodeWithContentDescription("2 matching configurations").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("1 matching configuration").assertCountEquals(0)
        compose.onNodeWithText("United States").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        screenshot("location-options-en", modal = true)

        compose.onNodeWithText("Germany").performClick()
        assertEquals("DE", selected)
        compose.onAllNodesWithText("Recommended").assertCountEquals(0)
    }

    @Test fun noEligibleCountriesLeavesAutomaticAsTheOnlyOption() {
        var selected: String? = "UNSET"
        val germany = CountryOption("DE", "Germany", "🇩🇪")
        render(
            selectedCountry = germany,
            selectedCountryAvailable = false,
            onSelectCountry = { selected = it }
        )
        compose.onNodeWithContentDescription("Select location: 🇩🇪 Germany").performClick()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Selected").assertCountEquals(0)
        compose.onNodeWithText("Automatic").performClick()
        assertEquals(null, selected)
    }

    @Test fun changingLocationWhileConnectedRequiresConfirmationAndCancelPreservesState() {
        var confirmed: String? = "UNSET"
        val de = CountryOption("DE", "Germany", "🇩🇪")
        val us = CountryOption("US", "United States", "🇺🇸")
        render(
            state = ConnectionState.CONNECTED,
            selectedCountry = de,
            availableCountries = listOf(de, us),
            onConfirmCountrySwitch = { confirmed = it }
        )
        compose.onNodeWithContentDescription("Select location: 🇩🇪 Germany").assertIsDisplayed().performClick()
        // Selecting the already-active country closes the sheet without confirmation.
        compose.onAllNodesWithText("Germany").onLast().performClick()
        compose.onAllNodesWithText("Switch location?").assertCountEquals(0)

        // Selecting a different country opens confirmation; cancelling preserves connection.
        compose.onNodeWithContentDescription("Select location: 🇩🇪 Germany").performClick()
        compose.onNodeWithText("United States").performClick()
        compose.onNodeWithText("Switch location?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("UNSET", confirmed)
        compose.onAllNodesWithText("Switch location?").assertCountEquals(0)

        // Confirming triggers reconnect to the chosen country.
        compose.onNodeWithContentDescription("Select location: 🇩🇪 Germany").performClick()
        compose.onNodeWithText("United States").performClick()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals("US", confirmed)
    }

    @Test fun unavailableSelectedCountryShowsClearMessageWithoutSwitching() {
        val de = CountryOption("DE", "Germany", "🇩🇪")
        render(
            servers = 0,
            totalStoredCount = 2,
            enabledSourceCount = 1,
            selectedCountry = de,
            availableCountries = listOf(CountryOption("US", "United States", "🇺🇸")),
            selectedCountryAvailable = false
        )
        compose.onNodeWithText("Germany").assertIsDisplayed()
        compose.onNodeWithText("No servers available for the selected country").assertIsDisplayed()
    }

    @Config(qualifiers = "fa-w360dp-h640dp")
    @Test fun largePersianLocationSheetAndConfirmationStayReadable() {
        val de = CountryOption("DE", "آلمان", "🇩🇪")
        val nl = CountryOption("NL", "هلند", "🇳🇱")
        render(
            state = ConnectionState.CONNECTED,
            language = "fa",
            fontScale = 1.8f,
            selectedCountry = de,
            availableCountries = listOf(de, nl),
            countryCounts = mapOf(de.code to 12, nl.code to 1)
        )
        compose.onNodeWithText("آلمان").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("پیشنهادی").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("۱۲ پیکربندی منطبق").assertIsDisplayed()
        screenshot("location-options-fa-large", modal = true)
        compose.onNodeWithText("هلند").performScrollTo().performClick()
        compose.onNodeWithText("تغییر موقعیت؟").assertIsDisplayed()
        compose.onNodeWithText("اتصال مجدد").assertIsDisplayed()
        compose.onNodeWithText("لغو").assertIsDisplayed()
    }

    private fun connectedTelemetry() = TunnelTelemetry(
        connectionState = ConnectionState.CONNECTED,
        connectedAtElapsedMs = 0L,
        rxBytes = 12_345_678L,
        txBytes = 1_234_567L,
        exitIp = "203.0.113.7",
        exitCountryCode = "NL"
    )

    @Test fun homeDetailsStayCollapsedByDefaultAndOnlyReportTheTap() {
        var toggles = 0
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(),
            onToggleDetailsExpanded = { toggles++ }
        )
        compose.onNodeWithText("Connection Details").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("IP").assertCountEquals(0)
        compose.onNodeWithText("Connection Details").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onAllNodesWithText("IP").assertCountEquals(0)
    }

    @Test fun expandedDetailsShowFlaggedIpAndTotalOnly() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(),
            detailsExpanded = true
        )
        compose.onNodeWithText("IP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("🇳🇱 203.0.113.7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12.3 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.23 MB").performScrollTo().assertIsDisplayed()
        listOf("Exit Country", "Uptime", "Download", "Upload", "Status").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        listOf("Protected", "Exit verified", "Checking connection…", "Connected (unverified)", "Verification failed")
            .forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test fun expandedPersianDetailsUseDecimalUnitsAndKeepTheIpInOrder() {
        render(
            state = ConnectionState.CONNECTED,
            language = "fa",
            telemetry = connectedTelemetry(),
            detailsExpanded = true
        )
        compose.onNodeWithText("۱۲٫۳ مگابایت").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("۱٫۲۳ مگابایت").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("203.0.113.7", substring = true).assertIsDisplayed()
        screenshot("connected-expanded-fa")
    }

    @Test fun connectedDurationRendersInsideThePowerButton() {
        render(state = ConnectionState.CONNECTED, telemetry = connectedTelemetry())
        compose.onNodeWithText("00:00:00").assertIsDisplayed()
        val buttonBounds = compose.onNodeWithContentDescription("Disconnect")
            .fetchSemanticsNode().boundsInRoot
        val timerBounds = compose.onNodeWithText("00:00:00")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(timerBounds.left >= buttonBounds.left)
        assertTrue(timerBounds.top >= buttonBounds.top)
        assertTrue(timerBounds.right <= buttonBounds.right)
        assertTrue(timerBounds.bottom <= buttonBounds.bottom)
    }

    @Test fun disconnectedHomeDoesNotShowConnectionDuration() {
        render(state = ConnectionState.DISCONNECTED)
        compose.onAllNodesWithText("00:00:00").assertCountEquals(0)
    }

    private fun assertDetailsExpansionDoesNotShiftOrb() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(),
            toggleDetailsState = true
        )
        val collapsedTop = compose.onNodeWithContentDescription("Disconnect")
            .fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Connection Details").performClick()
        val expandedTop = compose.onNodeWithContentDescription("Disconnect")
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(collapsedTop, expandedTop, 0.5f)
    }

    @Test fun detailsExpansionDoesNotShiftOrbAt360x640() {
        assertDetailsExpansionDoesNotShiftOrb()
    }

    @Config(qualifiers = "w360dp-h720dp")
    @Test fun detailsExpansionDoesNotShiftOrbAt360x720() {
        assertDetailsExpansionDoesNotShiftOrb()
        val orbCenter = compose.onNodeWithContentDescription("Disconnect").fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("Power button should sit near the visual center", orbCenter / hostView.height in 0.38f..0.55f)
        screenshot("connected-expanded-en")
    }

    @Config(qualifiers = "fa-w360dp-h720dp")
    @Test fun largePersianConnectedExpandedDetailsRendersCleanly() {
        render(
            state = ConnectionState.CONNECTED,
            language = "fa",
            fontScale = 1.6f,
            telemetry = connectedTelemetry(),
            detailsExpanded = true
        )
        compose.onNodeWithText("جزئیات اتصال").assertIsDisplayed()
        compose.onNodeWithText("IP").assertIsDisplayed()
        compose.onNodeWithText("مجموع").assertIsDisplayed()
        screenshot("connected-expanded-fa-large")
    }
    @Config(qualifiers = "w640dp-h360dp")
    @Test fun landscapeKeepsHeroAndLowerContentReachable() {
        render(
            state = ConnectionState.CONNECTED,
            enabledSourceCount = 2,
            telemetry = connectedTelemetry(),
            fontScale = 1.6f,
            detailsExpanded = true
        )
        compose.onNodeWithContentDescription("Disconnect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 sources enabled").performScrollTo().assertIsDisplayed()
    }


    @Test fun reconnectingShowsItsOwnStatusInsteadOfConnected() {
        render(
            state = ConnectionState.RECONNECTING,
            telemetry = TunnelTelemetry(connectionState = ConnectionState.RECONNECTING)
        )
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.onAllNodesWithText("Connected").assertCountEquals(0)
    }

    @Test fun failoverShowsItsOwnStatusInsteadOfConnected() {
        render(
            state = ConnectionState.FAILOVER,
            telemetry = TunnelTelemetry(connectionState = ConnectionState.FAILOVER)
        )
        compose.onNodeWithText("Switching server…").assertIsDisplayed()
        compose.onAllNodesWithText("Connected").assertCountEquals(0)
    }

    @Test fun errorBannerHidesTechnicalDetailUntilRequested() {
        render(
            state = ConnectionState.ERROR,
            telemetry = TunnelTelemetry(
                connectionState = ConnectionState.ERROR,
                lastError = "Could not reach the server.",
                lastErrorTechnicalDetail = "tcp: dial 10.0.0.5:443 timeout"
            )
        )
        compose.onNodeWithText("Could not reach the server.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("tcp: dial 10.0.0.5:443 timeout").assertCountEquals(0)
        compose.onNodeWithText("Technical details").performScrollTo().performClick()
        compose.onNodeWithText("tcp: dial 10.0.0.5:443 timeout").performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String, modal: Boolean = false) {
        val directory = System.getenv("LUXY_SCREENSHOTS_DIR") ?: return
        val target = File(directory, "$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = if (modal) {
                val manager = Class.forName("android.view.WindowManagerGlobal")
                val instance = manager.getMethod("getInstance").invoke(null)
                val windows = manager.getMethod("getWindowViews").invoke(instance) as List<*>
                windows.filterIsInstance<View>().last { it.width > 0 && it.height > 0 }
            } else {
                hostView.rootView
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
