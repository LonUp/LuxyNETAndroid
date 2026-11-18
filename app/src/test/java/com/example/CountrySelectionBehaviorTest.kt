package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.core.ServerCountryResolver
import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountrySelectionBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        val app = compose.activity.application as LuxyVpnApplication
        runBlocking {
            app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
            app.repository.updateSettings { StabilitySettings() }
        }
    }

    private fun profile(id: String, name: String) = ServerProfileEntity(
        id = id,
        name = name,
        address = "$id.example.com",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555"
    )

    @Test
    fun activityDerivesCountriesFromImportedConfigsAndPersistsSelection() {
        val app = compose.activity.application as LuxyVpnApplication
        val locale = compose.activity.resources.configuration.locales[0]
        val autoLabel = compose.activity.getString(R.string.location_automatic)
        val recLabel = compose.activity.getString(R.string.location_recommended)
        val deName = ServerCountryResolver.displayName("DE", locale)
        val usName = ServerCountryResolver.displayName("US", locale)
        runBlocking {
            app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
            app.repository.setSelectedCountry(null)
            app.repository.saveProfiles(
                listOf(
                    profile("us1", "🇺🇸 United States 1"),
                    profile("de1", "🇩🇪 Germany 1"),
                    profile("de2", "DE-02"),
                    profile("unk1", "My server")
                )
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(autoLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(autoLabel).performClick()
        compose.onNodeWithText(recLabel).assertIsDisplayed()
        compose.onNodeWithText(deName).assertIsDisplayed()
        compose.onNodeWithText(usName).assertIsDisplayed()

        compose.onNodeWithText(deName).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(recLabel).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(deName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(deName).assertIsDisplayed()
        compose.waitUntil(10_000) {
            runBlocking { app.repository.settingsFlow.first().selectedCountryCode == "DE" }
        }
        assertEquals("DE", runBlocking { app.repository.settingsFlow.first().selectedCountryCode })
    }
}
