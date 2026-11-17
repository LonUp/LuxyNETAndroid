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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.local.SubscriptionEntity
import com.example.ui.SubscriptionsPage
import com.example.ui.theme.LuxyVpnTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionsPageTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View
    private val subscription = SubscriptionEntity(id = "plan", name = "My plan", url = "https://example.com/private-token", nodeCount = 3)
    private var saved: List<String>? = null
    private var deleted: String? = null
    private var refreshed: String? = null
    private var home = false

    private fun render(
        subscriptions: List<SubscriptionEntity> = listOf(subscription),
        enabled: Boolean = true,
        busy: Boolean = false,
        language: String = "en",
        fontScale: Float = 1f
    ) {
        compose.setContent {
            val baseConfiguration = LocalConfiguration.current
            val baseContext = LocalContext.current
            val config = remember(language, baseConfiguration) {
                Configuration(baseConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
            }
            // Dialog windows need the activity context when no locale override is needed.
            val context = remember(baseContext, config) {
                if (baseConfiguration.locales[0].language == language) baseContext
                else baseContext.createConfigurationContext(config)
            }
            hostView = LocalView.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                LuxyVpnTheme(MinimalTheme) {
                    SubscriptionsPage(subscriptions, enabled, busy, remember { SnackbarHostState() },
                        onHome = { home = true }, onRefresh = { refreshed = it },
                        onSave = { id, name, url -> saved = listOf(id, name, url) }, onDelete = { deleted = it })
                }
            }
        }
    }

    @Test fun editValidatesUrlAndSavesNameAndMachineUrl() {
        render()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Renamed plan")
        compose.onNodeWithText("Subscription URL").performTextReplacement("ftp://example.com/token")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a name and a valid HTTP or HTTPS URL with a host.").assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithText("Subscription URL").performTextReplacement("https://example.org/new-token?x=123")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf("plan", "Renamed plan", "https://example.org/new-token?x=123"), saved) }
    }

    @Test fun deletingRequiresConfirmationAndCancelPreservesSubscription() {
        render()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("“My plan” and all its associated servers will be removed.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(deleted)
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onAllNodesWithText("Delete").filter(isEnabled()).onLast().performClick()
        compose.runOnIdle { assertEquals("plan", deleted) }
    }

    @Test fun unknownMetadataIsNotUnlimitedAndTokenIsNotOnCard() {
        render()
        compose.onNodeWithText("Used: Not reported").assertExists()
        compose.onNodeWithText("Remaining: Not reported").assertExists()
        compose.onNodeWithText("Allowance: Not reported").assertExists()
        compose.onNodeWithText("Expires: Not reported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Time remaining: Not reported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last successful update: Never").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(subscription.url, substring = true).assertDoesNotExist()
        screenshot("subscriptions-unknown-en")
    }

    @Test fun connectedAllowsViewingButDisablesMutations() {
        render(enabled = false)
        compose.onNodeWithText("Disconnect to refresh, edit or delete subscriptions. You can still view saved information.").assertExists()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, home) }
    }

    @Test fun busyProtectsRefreshAndOtherMutations() {
        render(busy = true)
        compose.onNodeWithText("Updating subscriptions…").assertExists()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        assertNull(refreshed)
    }

    @Test fun failedRefreshKeepsCachedSnapshotWithoutExposingErrorUrl() {
        render(subscriptions = listOf(subscription.copy(uploadBytes = 1024, downloadBytes = 1024,
            totalBytes = 8192, lastUpdatedAt = 1_700_000_000_000,
            lastError = "Failed fetching https://example.com/private-token")))
        compose.onNodeWithText("Last refresh failed. Saved information may be out of date.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Usage is a saved snapshot, not live traffic.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("private-token", substring = true).assertDoesNotExist()
        screenshot("subscriptions-cached-en")
    }

    @Test fun largePersianLongNameKeepsActionsReachableAndUnknownHonest() {
        val name = "اشتراک شخصی با نام بسیار طولانی برای بررسی نمایش خوانا و دسترسی به همهٔ گزینه‌ها"
        render(subscriptions = listOf(subscription.copy(name = name)), language = "fa", fontScale = 1.8f)
        compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("مصرف‌شده: گزارش نشده").performScrollTo().assertIsDisplayed()
        screenshot("subscriptions-fa-large")
        compose.onNodeWithText("به‌روزرسانی").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("ویرایش").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("حذف").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("subscriptions-fa-large-actions")
    }

    @Test fun emptyStateGuidesHomeImport() {
        render(subscriptions = emptyList())
        compose.onNodeWithText("No subscriptions yet. Import a subscription link on Home to see its servers and usage here.").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.runOnIdle { assertEquals(true, home) }
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
