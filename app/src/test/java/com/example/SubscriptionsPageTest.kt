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
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.ui.SubscriptionsPage
import com.example.ui.theme.LuxyVpnTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val subscription = SubscriptionEntity(
        id = "plan",
        name = "My plan",
        url = "https://example.com/private-token",
        nodeCount = 3
    )
    private var saved: List<String>? = null
    private var deleted: String? = null
    private var refreshed: String? = null
    private var home = false
    private var clipboardClicked = false
    private var fileClicked = false
    private var addedUrl: Pair<String, String>? = null
    private var exportedPassphrase: String? = null
    private var restoreRequested = false

    private fun render(
        subscriptions: List<SubscriptionEntity> = listOf(subscription),
        enabled: Boolean = true,
        busy: Boolean = false,
        language: String = "en",
        fontScale: Float = 1f,
        standaloneServerCount: Int = 0,
        manualConfigsEnabled: Boolean = true,
        connectedSubscriptionId: String? = null,
        eligibleCounts: Map<String, Int> = emptyMap(),
        smartCleanupPlan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0)
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
                    SubscriptionsPage(
                        subscriptions = subscriptions,
                        managementEnabled = enabled,
                        busy = busy,
                        snackbar = remember { SnackbarHostState() },
                        onHome = { home = true },
                        onRefresh = { refreshed = it },
                        onSave = { id, name, url -> saved = listOf(id, name, url) },
                        onDelete = { deleted = it },
                        standaloneServerCount = standaloneServerCount,
                        manualConfigsEnabled = manualConfigsEnabled,
                        connectedSubscriptionId = connectedSubscriptionId,
                        eligibleCounts = eligibleCounts,
                        smartCleanupPlan = smartCleanupPlan,
                        onImportClipboard = { clipboardClicked = true },
                        onImportFile = { fileClicked = true },
                        onAddSubscriptionUrl = { url, name -> addedUrl = url to name },
                        onExportBackup = { exportedPassphrase = it },
                        onRequestRestoreBackup = { restoreRequested = true }
                    )
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

    @Test fun unknownMetadataIsNotUnlimitedHidesTimeRemainingAndNeverFalselyLabelsActive() {
        render()
        compose.onNodeWithText("Choose which sources Automatic can use.").assertIsDisplayed()
        compose.onNodeWithText("Automatic uses enabled sources only.").assertIsDisplayed()
        compose.onNodeWithText("Not updated").assertIsDisplayed()
        compose.onNodeWithText("Used").assertIsDisplayed()
        compose.onNodeWithText("Remaining").assertIsDisplayed()
        compose.onNodeWithText("Allowance").assertIsDisplayed()
        compose.onNodeWithText("Expires").assertIsDisplayed()
        compose.onAllNodesWithText("Not reported").assertCountEquals(4)
        compose.onAllNodesWithText("Time remaining", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Last updated: Never").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(subscription.url, substring = true).assertDoesNotExist()
        screenshot("subscriptions-unknown-en")
    }

    @Test fun activeStatusShownOnlyWhenUpdatedWithServersAndValidQuotaAndExpiry() {
        val activeSub = SubscriptionEntity(
            id = "conn",
            name = "Connection",
            url = "https://example.com/sub",
            nodeCount = 10,
            uploadBytes = 1024L * 1024 * 1024,
            downloadBytes = 1024L * 1024 * 1024,
            totalBytes = 0L,
            expireAtEpochSec = 0L,
            lastUpdatedAt = 1_795_000_000_000L,
            lastStatus = "Updated"
        )
        render(subscriptions = listOf(activeSub))
        compose.onNodeWithText("Connection").assertIsDisplayed()
        compose.onNodeWithText("10 servers").assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("2 GiB").assertIsDisplayed()
        compose.onAllNodesWithText("Not reported").assertCountEquals(3)
        screenshot("subscriptions-active-en")
    }

    @Test fun expiredExhaustedAndEmptySubscriptionsNeverShowActive() {
        val nowSec = System.currentTimeMillis() / 1000L
        val expired = subscription.copy(
            id = "exp",
            name = "Expired plan",
            lastUpdatedAt = 1_700_000_000_000L,
            lastStatus = "Updated",
            expireAtEpochSec = nowSec - 3600
        )
        val exhausted = subscription.copy(
            id = "exh",
            name = "Exhausted plan",
            lastUpdatedAt = 1_700_000_000_000L,
            lastStatus = "Updated",
            uploadBytes = 5000,
            downloadBytes = 5000,
            totalBytes = 8000
        )
        val empty = subscription.copy(
            id = "emp",
            name = "Empty plan",
            nodeCount = 0,
            lastUpdatedAt = 1_700_000_000_000L,
            lastStatus = "Updated"
        )
        render(subscriptions = listOf(expired, exhausted, empty))
        compose.onAllNodesWithText("Active").assertCountEquals(0)
        compose.onAllNodesWithText("Expired").assertCountEquals(2) // status pill + timeLeft under Expires
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Quota reached"))
        compose.onNodeWithText("Quota reached").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No servers"))
        compose.onNodeWithText("No servers").assertIsDisplayed()
    }

    @Test fun connectedAllowsViewingButDisablesMutations() {
        render(enabled = false)
        compose.onNodeWithText("Disconnect to add, refresh, edit, or delete configurations. You can still view saved information.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add configuration").assertIsNotEnabled()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, home) }
    }

    @Test fun busyProtectsRefreshAndOtherMutations() {
        render(busy = true)
        compose.onNodeWithText("Updating configurations…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add configuration").assertIsNotEnabled()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        assertNull(refreshed)
    }

    @Test fun failedRefreshKeepsCachedSnapshotWithoutExposingErrorUrl() {
        render(
            subscriptions = listOf(
                subscription.copy(
                    uploadBytes = 1024,
                    downloadBytes = 1024,
                    totalBytes = 8192,
                    lastUpdatedAt = 1_700_000_000_000,
                    lastStatus = "Failed",
                    lastError = "Failed fetching https://example.com/private-token"
                )
            )
        )
        compose.onNodeWithText("Update failed").assertIsDisplayed()
        compose.onAllNodesWithText("Active").assertCountEquals(0)
        compose.onNodeWithText("Last refresh failed. Saved information may be out of date.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("private-token", substring = true).assertDoesNotExist()
        screenshot("subscriptions-cached-en")
    }

    @Test fun addButtonOpensConfigurationSheetAndHandlesAllThreeOptions() {
        render()
        compose.onNodeWithContentDescription("Add configuration").assertIsEnabled().performClick()
        compose.onNodeWithText("Add Configuration").assertIsDisplayed()
        compose.onNodeWithText("Import from clipboard").assertIsDisplayed()
        compose.onNodeWithText("Paste a subscription or configuration").assertIsDisplayed()
        compose.onNodeWithText("Import from file").assertIsDisplayed()
        compose.onNodeWithText("Select a configuration file").assertIsDisplayed()
        compose.onNodeWithText("Add subscription URL").assertIsDisplayed()
        compose.onNodeWithText("Import from a subscription link").assertIsDisplayed()
        screenshot("subscriptions-add-sheet-en")

        compose.onNodeWithText("Import from clipboard").performClick()
        assertTrue(clipboardClicked)
        compose.onAllNodesWithText("Add Configuration").assertCountEquals(0)

        compose.onNodeWithContentDescription("Add configuration").performClick()
        compose.onNodeWithText("Import from file").performClick()
        assertTrue(fileClicked)
        compose.onAllNodesWithText("Add Configuration").assertCountEquals(0)

        compose.onNodeWithContentDescription("Add configuration").performClick()
        compose.onNodeWithText("Add subscription URL").performClick()
        compose.onNodeWithText("Subscription URL").performTextReplacement("vless://not-http")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Enter a valid HTTP or HTTPS URL with a host.").assertIsDisplayed()
        assertNull(addedUrl)

        compose.onNodeWithText("Subscription URL").performTextReplacement("https://example.com/sub?token=1")
        compose.onNodeWithText("Name (optional)").performTextReplacement("Work")
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle { assertEquals("https://example.com/sub?token=1" to "Work", addedUrl) }
    }

    @Test fun largePersianLongNameKeepsActionsReachableAndUnknownHonest() {
        val name = "اشتراک شخصی با نام بسیار طولانی برای بررسی نمایش خوانا و دسترسی به همهٔ گزینه‌ها"
        render(subscriptions = listOf(subscription.copy(name = name)), language = "fa", fontScale = 1.8f)
        compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("مصرف‌شده").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("گزارش نشده").onLast().performScrollTo().assertIsDisplayed()
        screenshot("subscriptions-fa-large")
        compose.onNodeWithText("به‌روزرسانی").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("ویرایش").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("حذف").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("subscriptions-fa-large-actions")
    }

    @Test fun emptyStateOffersClearAddConfigurationActionAndManualCard() {
        render(subscriptions = emptyList(), standaloneServerCount = 2)
        compose.onNodeWithText("No subscriptions yet").assertIsDisplayed()
        compose.onNodeWithText("2 manual configuration(s) saved").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Manual Configurations").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add Configuration"))
        compose.onNodeWithText("Add Configuration").performClick()
        compose.onNodeWithText("Import from clipboard").assertIsDisplayed()
        compose.onNodeWithText("Import from file").assertIsDisplayed()
        compose.onNodeWithText("Add subscription URL").assertIsDisplayed()
    }

    @Test fun connectedExcludedSourceShowsBothPills() {
        render(
            subscriptions = listOf(subscription.copy(isEnabledForConnection = false)),
            connectedSubscriptionId = "plan"
        )
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Excluded").assertIsDisplayed()
    }

    @Test fun connectedIncludedSourceShowsConnectedAndIncluded() {
        render(connectedSubscriptionId = "plan", manualConfigsEnabled = false)
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Included").assertIsDisplayed()
    }

    @Test fun serverCountNeverAdvertisesUnusableServers() {
        render(eligibleCounts = mapOf("plan" to 2))
        compose.onNodeWithText("3 servers · 2 eligible").assertIsDisplayed()
    }

    @Test fun smartCleanupActionOpensTheCleanupSheet() {
        val profile = ServerProfileEntity(
            id = "dup",
            name = "duplicate-node",
            protocol = "VLESS",
            address = "dup.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555"
        )
        render(
            smartCleanupPlan = SmartCleanupPlan(
                groups = listOf(
                    SmartCleanupGroup(
                        category = SmartCleanupCategory.DUPLICATES,
                        items = listOf(
                            SmartCleanupItem(
                                profile = profile,
                                category = SmartCleanupCategory.DUPLICATES,
                                reason = "Same host and credentials"
                            )
                        ),
                        defaultSelected = false
                    )
                ),
                totalSuggested = 1
            )
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Smart Cleanup"))
        compose.onNodeWithText("Smart Cleanup").performClick()
        compose.onNodeWithText("Review suggested configurations before moving to Trash.")
            .assertIsDisplayed()
    }

    @Test fun addSheetOffersEncryptedBackupExportAndRestore() {
        render()
        compose.onNodeWithContentDescription("Add configuration").performClick()
        compose.onNodeWithText("Export encrypted backup").assertIsDisplayed()
        compose.onNodeWithText("Restore from backup").assertIsDisplayed()

        compose.onNodeWithText("Export encrypted backup").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("secret1")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("secret1")
        compose.onNodeWithText("Export").performClick()
        compose.runOnIdle { assertEquals("secret1", exportedPassphrase) }

        compose.onNodeWithContentDescription("Add configuration").performClick()
        compose.onNodeWithText("Restore from backup").performClick()
        compose.runOnIdle { assertTrue(restoreRequested) }
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
