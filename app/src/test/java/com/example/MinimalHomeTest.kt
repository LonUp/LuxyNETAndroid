package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.data.model.ConnectionState
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
        selectedCountryAvailable: Boolean = true,
        onSelectCountry: (String?) -> Unit = {},
        onConfirmCountrySwitch: (String?) -> Unit = {}
    ) {
        compose.setContent {
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
                        selectedCountryAvailable = selectedCountryAvailable,
                        onSelectCountry = onSelectCountry,
                        onConfirmCountrySwitch = onConfirmCountrySwitch
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
        compose.onNodeWithText("اشتراک‌ها").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(openedSubscriptions)
    }

    @Test fun connectedStateKeepsDisconnectAvailableAndShowsReadyCount() {
        render(
            state = ConnectionState.CONNECTED,
            servers = 11,
            selectedCountry = CountryOption("AL", "Albania", "🇦🇱"),
            onSubscriptions = {}
        )
        compose.onNodeWithContentDescription("Disconnect").assertIsEnabled()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Albania").assertIsDisplayed()
        compose.onNodeWithText("11 servers ready").assertIsDisplayed()
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
        screenshot("connected-en")
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
            onSelectCountry = { selected = it }
        )
        compose.onNodeWithText("Automatic").assertIsDisplayed().performClick()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithText("Germany").assertIsDisplayed()
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
            servers = 2,
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
