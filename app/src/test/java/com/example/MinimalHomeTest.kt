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
import com.example.data.model.CountryConfidence
import com.example.data.model.TunnelTelemetry
import com.example.data.model.VerificationState
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
        compose.onNodeWithText("All connection sources are disabled. Enable a source to connect.").assertIsDisplayed()
        compose.onNodeWithText("0 sources enabled").performClick()
        compose.onNodeWithText("Connection Sources").assertIsDisplayed()
        compose.onNodeWithText("Premium Europe").assertIsDisplayed()
        compose.onNodeWithText("12 stored · 7 eligible").assertIsDisplayed()
        compose.onNodeWithContentDescription("Premium Europe").performClick()
        assertEquals("sub-1" to true, toggledSub)
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
        compose.onNodeWithText("2 matching configurations").assertIsDisplayed()
        compose.onNodeWithText("United States").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()

        compose.onNodeWithText("Germany").performClick()
        assertEquals("DE", selected)
        compose.onAllNodesWithText("Recommended").assertCountEquals(0)
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
            availableCountries = listOf(de, nl)
        )
        compose.onNodeWithText("آلمان").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("پیشنهادی").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("هلند").performScrollTo().performClick()
        compose.onNodeWithText("تغییر موقعیت؟").assertIsDisplayed()
        compose.onNodeWithText("اتصال مجدد").assertIsDisplayed()
        compose.onNodeWithText("لغو").assertIsDisplayed()
    }

    private fun connectedTelemetry(
        verification: VerificationState = VerificationState.VERIFIED,
        dnsProtected: Boolean = true,
        ipv6Protected: Boolean = true,
        lockdownEnabled: Boolean = false,
        exitVerified: Boolean = false
    ) = TunnelTelemetry(
        connectionState = ConnectionState.CONNECTED,
        exitIp = "203.0.113.7",
        exitCountryCode = "NL",
        exitCountryConfidence = CountryConfidence.VERIFIED,
        dnsLeakProtected = dnsProtected,
        ipv6LeakProtected = ipv6Protected,
        lockdownEnabled = lockdownEnabled,
        exitVerified = exitVerified,
        verificationState = verification
    )

    @Test fun homeDetailsStayCollapsedByDefaultAndOnlyReportTheTap() {
        var toggles = 0
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(),
            onToggleDetailsExpanded = { toggles++ }
        )
        compose.onNodeWithText("Connection Details").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Exit IP").assertCountEquals(0)
        compose.onNodeWithText("Connection Details").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onAllNodesWithText("Exit IP").assertCountEquals(0)
    }

    @Test fun expandedDetailsShowVerifiedExitAndNetworkProtection() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(),
            detailsExpanded = true
        )
        compose.onNodeWithText("Exit IP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("203.0.113.7").assertIsDisplayed()
        compose.onNodeWithText("Exit verified · Tunnel DNS & IPv6 active").performScrollTo().assertIsDisplayed()
    }

    @Test fun verifiedLeakCheckedTunnelIsReportedAsProtected() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(lockdownEnabled = true)
        )
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Protected").assertIsDisplayed()
    }
    @Test fun verifiedTunnelWithoutLockdownShowsExitVerifiedOnly() {
        render(state = ConnectionState.CONNECTED, telemetry = connectedTelemetry())
        compose.onNodeWithText("Exit verified").assertIsDisplayed()
        compose.onAllNodesWithText("Protected").assertCountEquals(0)
    }

    @Test fun connectedWithoutLeakChecksIsNeverReportedAsProtected() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(
                verification = VerificationState.IDLE,
                dnsProtected = false,
                ipv6Protected = false
            )
        )
        compose.onNodeWithText("Connected (unverified)").assertIsDisplayed()
        compose.onAllNodesWithText("Protected").assertCountEquals(0)
    }
    @Test fun exitVerifiedPartialStateShowsExitVerified() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(
                verification = VerificationState.PARTIAL,
                dnsProtected = false,
                ipv6Protected = false,
                exitVerified = true
            )
        )
        compose.onNodeWithText("Exit verified").assertIsDisplayed()
        compose.onAllNodesWithText("Protected").assertCountEquals(0)
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
        screenshot("connected-expanded-en")
    }
    @Config(qualifiers = "w640dp-h360dp")
    @Test fun landscapeKeepsHeroAndLowerContentReachable() {
        render(
            state = ConnectionState.CONNECTED,
            enabledSourceCount = 2,
            telemetry = connectedTelemetry()
        )
        compose.onNodeWithText("Connection Details").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 sources enabled").performScrollTo().assertIsDisplayed()
    }

    @Test fun checkingVerificationIsReportedAsChecking() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(verification = VerificationState.CHECKING)
        )
        compose.onNodeWithText("Checking connection…").assertIsDisplayed()
        compose.onAllNodesWithText("Protected").assertCountEquals(0)
    }

    @Test fun failedVerificationIsReportedAsFailed() {
        render(
            state = ConnectionState.CONNECTED,
            telemetry = connectedTelemetry(
                verification = VerificationState.FAILED,
                dnsProtected = false,
                ipv6Protected = false
            )
        )
        compose.onNodeWithText("Verification failed").assertIsDisplayed()
        compose.onAllNodesWithText("Protected").assertCountEquals(0)
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

    private fun screenshot(name: String) {
        val directory = System.getenv("LUXY_SCREENSHOTS_DIR") ?: return
        val target = File(directory, "$name.png").apply { parentFile.mkdirs() }
        compose.runOnIdle {
            val view = hostView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
