package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ManualConfigCounts
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.data.model.TrashEntry
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
        manualProfiles: List<ServerProfileEntity> = emptyList(),
        manualCounts: ManualConfigCounts = ManualConfigCounts(total = standaloneServerCount),
        trashEntries: List<TrashEntry> = emptyList(),
        connectedSubscriptionId: String? = null,
        connectedIsManual: Boolean = false,
        eligibleCounts: Map<String, Int> = emptyMap(),
        smartCleanupPlan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
        onToggleManualSource: (Boolean) -> Unit = {}
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
                        manualProfiles = manualProfiles,
                        manualCounts = manualCounts,
                        trashEntries = trashEntries,
                        connectedSubscriptionId = connectedSubscriptionId,
                        connectedIsManual = connectedIsManual,
                        eligibleCounts = eligibleCounts,
                        smartCleanupPlan = smartCleanupPlan,
                        onToggleManualSource = onToggleManualSource,
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

    private fun expandSubscriptionDetails(id: String = subscription.id) {
        compose.onNodeWithTag("subscription_details_$id").performScrollTo().performClick()
    }

    private fun manualProfile(id: String, name: String) = ServerProfileEntity(
        id = id,
        name = name,
        protocol = "VLESS",
        address = "$id.example.com",
        port = 443,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        sourceType = SourceType.MANUAL.name
    )

    private fun trashEntry(index: Int) = TrashEntry(
        id = "trash-$index",
        batchId = "batch",
        deletedAt = 1_700_000_000_000L,
        expiresAt = 1_702_592_000_000L,
        label = "Manual cleanup",
        category = SmartCleanupCategory.INVALID.name,
        reason = "Missing credentials",
        profileId = "trash-profile-$index",
        profileName = "Trash config $index",
        sourceType = SourceType.MANUAL.name,
        subscriptionId = null,
        profile = null
    )

    @Test fun editValidatesUrlAndSavesNameAndMachineUrl() {
        render()
        expandSubscriptionDetails()
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
        expandSubscriptionDetails()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("“My plan” and all its associated servers will be removed.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(deleted)
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onAllNodesWithText("Delete").filter(isEnabled()).onLast().performClick()
        compose.runOnIdle { assertEquals("plan", deleted) }
    }

    @Test fun completelyUnknownMetadataUsesOneMutedSummaryAndNeverLabelsActive() {
        render()
        compose.onNodeWithText("Manage your connection sources.").assertIsDisplayed()
        compose.onAllNodesWithText("Choose which sources Automatic can use.").assertCountEquals(0)
        compose.onAllNodesWithText("Automatic uses enabled sources only.").assertCountEquals(0)
        compose.onNodeWithText("Usage and expiry not reported").assertIsDisplayed()
        compose.onAllNodesWithText("Not reported").assertCountEquals(0)
        compose.onAllNodesWithText("Used").assertCountEquals(0)
        compose.onAllNodesWithText("Remaining").assertCountEquals(0)
        compose.onAllNodesWithText("Allowance").assertCountEquals(0)
        compose.onAllNodesWithText("Expires").assertCountEquals(0)
        compose.onNodeWithText("Not updated").assertIsDisplayed()
        compose.onAllNodesWithText("Active").assertCountEquals(0)
        compose.onAllNodesWithText("Time remaining", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("subscription_details_plan").assertIsDisplayed()
        compose.onAllNodesWithText("Refresh").assertCountEquals(0)
        compose.onAllNodesWithText("Last updated: Never").assertCountEquals(0)
        expandSubscriptionDetails()
        compose.onNodeWithText("Last updated: Never").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Not reported").assertCountEquals(0)
        compose.onAllNodesWithText(subscription.url, substring = true).assertCountEquals(0)
        screenshot("subscriptions-unknown-en")
    }

    @Test fun healthyUpdatedSubscriptionGroupsMissingMetadataAndExpandsActions() {
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
        compose.onAllNodesWithText("Active").assertCountEquals(0)
        compose.onNodeWithText("2.15 GB").assertIsDisplayed()
        compose.onAllNodesWithText("Remaining").assertCountEquals(0)
        compose.onAllNodesWithText("Refresh").assertCountEquals(0)
        expandSubscriptionDetails(activeSub.id)
        compose.onNodeWithText("Used").assertIsDisplayed()
        compose.onNodeWithText("Remaining, Allowance, Expires not reported").assertIsDisplayed()
        compose.onAllNodesWithText("Not reported").assertCountEquals(0)
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Last updated:", substring = true).assertIsDisplayed()
        screenshot("subscriptions-active-en")
    }

    @Test fun allowanceAndExpiryWithoutUsageShowsAllowanceInExpandedDetailsWithoutDuplicatingExpiry() {
        val sub = SubscriptionEntity(
            id = "exp-allow",
            name = "Allowance Plan",
            url = "https://example.com/sub",
            nodeCount = 5,
            uploadBytes = 0L,
            downloadBytes = 0L,
            totalBytes = 10L * 1024 * 1024 * 1024,
            expireAtEpochSec = (System.currentTimeMillis() / 1000L) + 86_400L * 10,
            lastUpdatedAt = 1_795_000_000_000L,
            lastStatus = "Updated"
        )
        render(subscriptions = listOf(sub))
        compose.onNodeWithText("Expires").assertIsDisplayed()
        compose.onAllNodesWithText("Allowance").assertCountEquals(0)
        expandSubscriptionDetails(sub.id)
        compose.onNodeWithText("Allowance").assertIsDisplayed()
        compose.onNodeWithText("10.7 GB").assertIsDisplayed()
        compose.onAllNodesWithText("Expires").assertCountEquals(1)
        compose.onNodeWithText("Used, Remaining not reported").assertIsDisplayed()
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
        compose.onAllNodesWithText("Expired").assertCountEquals(1)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Quota reached"))
        compose.onNodeWithText("Quota reached").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No servers"))
        compose.onNodeWithText("No servers").assertIsDisplayed()
    }

    @Test fun connectedAllowsViewingButDisablesMutations() {
        render(enabled = false)
        expandSubscriptionDetails()
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
        expandSubscriptionDetails()
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
        expandSubscriptionDetails()
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
        compose.onAllNodesWithText("Export encrypted backup").assertCountEquals(0)
        compose.onAllNodesWithText("Restore from backup").assertCountEquals(0)
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
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("مصرف و انقضا گزارش نشده است").performScrollTo().assertIsDisplayed()
        screenshot("subscriptions-fa-large")
        expandSubscriptionDetails()
        compose.onNodeWithText("به‌روزرسانی").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("ویرایش").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("حذف").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("subscriptions-fa-large-actions")
    }
    @Test fun largeRtlSubscriptionCardKeepsServerTextWhole() {
        val name = "Premium Europe subscription for a large-font layout check"
        render(
            subscriptions = listOf(subscription.copy(name = name, nodeCount = 220)),
            language = "fa",
            fontScale = 1.8f,
            eligibleCounts = mapOf("plan" to 180)
        )
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNode(hasText("سرور ·", substring = true)).assertIsDisplayed()
        screenshot("subscriptions-fa-large-server-count")
    }

    @Test fun emptyStateOffersClearAddConfigurationActionAndManualCard() {
        render(subscriptions = emptyList(), standaloneServerCount = 2)
        compose.onNodeWithText("No subscriptions yet").assertIsDisplayed()
        compose.onNodeWithText("2 manual configuration(s) saved").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Manual Configurations").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manual Configurations: Use for connection")
            .assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Open Manual Configurations").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add Configuration"))
        compose.onNodeWithText("Add Configuration").performClick()
        compose.onNodeWithText("Import from clipboard").assertIsDisplayed()
        compose.onNodeWithText("Import from file").assertIsDisplayed()
        compose.onNodeWithText("Add subscription URL").assertIsDisplayed()
        compose.onAllNodesWithText("Export encrypted backup").assertCountEquals(0)
        compose.onAllNodesWithText("Restore from backup").assertCountEquals(0)
    }

    @Test fun emptyManualCardHasNoSwitchOrPreferenceAndOpensTheImportSheet() {
        var toggledManual: Boolean? = null
        render(
            subscriptions = emptyList(),
            standaloneServerCount = 0,
            manualConfigsEnabled = false,
            onToggleManualSource = { toggledManual = it }
        )
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Manual Configurations").assertIsDisplayed()
        compose.onNodeWithText("No manual configurations yet. Import from your clipboard or a file.")
            .assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Manual Configurations: Use for connection"))
            .assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Open Manual Configurations")).assertCountEquals(0)
        assertNull(toggledManual)
        screenshot("subscriptions-empty-manual-en")

        compose.onNodeWithText("Import").assertIsDisplayed()
        compose.onNodeWithText("Manual Configurations").performClick()
        compose.onNodeWithText("Import from clipboard").assertIsDisplayed()
        compose.onNodeWithText("Import from file").assertIsDisplayed()
        compose.onNodeWithText("Add subscription URL").assertIsDisplayed()
        compose.onAllNodesWithText("Manage imported local configs.").assertCountEquals(0)
        assertNull(toggledManual)
    }

    @Test fun emptyManualAddActionStaysDisabledWhileManagementIsBlocked() {
        render(subscriptions = emptyList(), standaloneServerCount = 0, enabled = false)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Import").assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodesWithText("Import from clipboard").assertCountEquals(0)
    }

    @Test fun oneAndManyManualConfigsKeepSwitchAndFalsePreference() {
        var profiles by androidx.compose.runtime.mutableStateOf(emptyList<ServerProfileEntity>())
        var toggled: Boolean? = null
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SubscriptionsPage(
                    subscriptions = emptyList(),
                    managementEnabled = true,
                    busy = false,
                    snackbar = remember { SnackbarHostState() },
                    onHome = {},
                    onRefresh = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    manualProfiles = profiles,
                    manualCounts = ManualConfigCounts(total = profiles.size),
                    manualConfigsEnabled = false,
                    onToggleManualSource = { toggled = it }
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onAllNodes(hasContentDescription("Manual Configurations: Use for connection"))
            .assertCountEquals(0)
        compose.runOnIdle { profiles = listOf(manualProfile("m1", "Manual one")) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("1 config").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manual Configurations: Use for connection")
            .assertIsDisplayed().assertIsOff()

        compose.onNodeWithContentDescription("Manual Configurations: Use for connection").performClick()
        compose.runOnIdle { assertEquals(true, toggled) }

        compose.runOnIdle {
            profiles = profiles + manualProfile("m2", "Manual two")
            toggled = null
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("2 configs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manual Configurations: Use for connection")
            .assertIsDisplayed().assertIsOff()

        compose.onNodeWithContentDescription("Manual Configurations: Use for connection").performClick()
        compose.runOnIdle { assertEquals(true, toggled) }
    }

    @Test fun smartCleanupBadgeIgnoresTrashWhenThereAreNoSuggestions() {
        render(
            subscriptions = emptyList(),
            trashEntries = listOf(trashEntry(1), trashEntry(2), trashEntry(3))
        )
        compose.onNodeWithContentDescription("Smart Cleanup").assertIsDisplayed()
        compose.onAllNodesWithText("3").assertCountEquals(0)
        compose.onAllNodesWithText("0").assertCountEquals(0)

        compose.onNodeWithContentDescription("Smart Cleanup").performClick()
        compose.onNodeWithText("Trash (3)").assertIsDisplayed()
    }

    @Test fun smartCleanupBadgeShowsSuggestionCountInsteadOfTrashCount() {
        val suggestions = (1..2).map { index ->
            SmartCleanupItem(
                profile = manualProfile("dup-$index", "Duplicate $index").copy(
                    sourceType = SourceType.SUBSCRIPTION.name,
                    subscriptionId = subscription.id
                ),
                category = SmartCleanupCategory.DUPLICATES,
                reason = "Duplicate of keeper"
            )
        }
        render(
            subscriptions = listOf(subscription),
            trashEntries = listOf(trashEntry(1), trashEntry(2), trashEntry(3)),
            smartCleanupPlan = SmartCleanupPlan(
                groups = listOf(SmartCleanupGroup(SmartCleanupCategory.DUPLICATES, suggestions, defaultSelected = true)),
                totalSuggested = suggestions.size
            )
        )
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onAllNodesWithText("3").assertCountEquals(0)
        screenshot("subscriptions-cleanup-badge-en")
        compose.onNodeWithContentDescription("Smart Cleanup").performClick()
        compose.onNodeWithText("Genuine duplicates").assertIsDisplayed()
        compose.onNodeWithText("Preview").performClick()
        compose.onAllNodesWithText("Source: Subscription · My plan")[0].assertIsDisplayed()
    }

    @Test fun manualConnectedBadgeAppearsOnlyWhenConnected() {
        var connectedManual by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SubscriptionsPage(
                    subscriptions = emptyList(),
                    managementEnabled = true,
                    busy = false,
                    snackbar = remember { SnackbarHostState() },
                    onHome = {},
                    onRefresh = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    standaloneServerCount = 2,
                    connectedIsManual = connectedManual
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onAllNodesWithText("Connected").assertCountEquals(0)

        compose.runOnIdle { connectedManual = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test fun connectedBadgeOnlyAppearsWhenConnectedAndEligibilityUsesSwitches() {
        var sub by androidx.compose.runtime.mutableStateOf(subscription.copy(isEnabledForConnection = false))
        var connectedId by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SubscriptionsPage(
                    subscriptions = listOf(sub),
                    managementEnabled = true,
                    busy = false,
                    snackbar = remember { SnackbarHostState() },
                    onHome = {},
                    onRefresh = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    connectedSubscriptionId = connectedId,
                    manualConfigsEnabled = false
                )
            }
        }
        compose.onAllNodesWithText("Connected").assertCountEquals(0)
        compose.onAllNodesWithText("Included").assertCountEquals(0)
        compose.onAllNodesWithText("Excluded").assertCountEquals(0)
        compose.onNodeWithContentDescription("My plan: Use for connection").assertIsDisplayed()

        compose.runOnIdle {
            sub = subscription
            connectedId = "plan"
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onAllNodesWithText("Included").assertCountEquals(0)
        compose.onAllNodesWithText("Excluded").assertCountEquals(0)
    }
    @Test fun eligibleServerCountIsNotClampedByStoredTotal() {
        render(eligibleCounts = mapOf("plan" to 4))
        compose.onNodeWithText("3 servers · 4 eligible").assertIsDisplayed()
    }

    @Test fun backupSheetStaysReachableWhileConnectedAndLocksRestore() {
        render(enabled = false)
        compose.onNodeWithContentDescription("Add configuration").assertIsNotEnabled()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Backup & Restore"))
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
        compose.onNodeWithText("Export a snapshot or restore a saved backup").assertIsDisplayed()
        compose.onNodeWithText("Backup & Restore").performClick()

        compose.onNodeWithText("Export encrypted backup").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Restore from backup").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Disconnect before restoring a backup.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, restoreRequested) }

        compose.onNodeWithText("Export encrypted backup").performClick()
        compose.onNodeWithText("Export encrypted backup").assertIsDisplayed()
        screenshot("subscriptions-backup-sheet-connected-en")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("secret1")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("secret1")
        compose.onNodeWithText("Export").performClick()
        compose.runOnIdle {
            assertEquals("secret1", exportedPassphrase)
            assertEquals(false, restoreRequested)
        }
    }

    @Test fun backupSheetRestoreRequestsWhenManagementIsAvailable() {
        render()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Backup & Restore"))
        compose.onNodeWithText("Backup & Restore").performClick()
        compose.onNodeWithText("Restore from backup").assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithText("Disconnect before restoring a backup.").assertCountEquals(0)
        compose.onNodeWithText("Restore from backup").performClick()
        compose.runOnIdle { assertTrue(restoreRequested) }
    }

    @Test fun largeEnglishFontKeepsCardActionsReachable() {
        render(fontScale = 1.8f)
        expandSubscriptionDetails()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Edit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete").performScrollTo().assertIsDisplayed()
        screenshot("subscriptions-en-large")
    }

    @Test fun saturatedUsageMarksQuotaReachedInsteadOfOverflowing() {
        val overflow = subscription.copy(
            id = "overflow",
            name = "Overflow plan",
            nodeCount = 2,
            uploadBytes = Long.MAX_VALUE,
            downloadBytes = Long.MAX_VALUE,
            totalBytes = 1_000L,
            lastUpdatedAt = 1_700_000_000_000L,
            lastStatus = "Updated"
        )
        render(subscriptions = listOf(overflow))
        // An unguarded upload+download sum wraps negative, which quietly loses Quota reached.
        compose.onNodeWithText("Quota reached").assertIsDisplayed()
    }

    @Test fun detailsToggleKeepsMinimumTouchTarget() {
        render()
        compose.onNodeWithTag("subscription_details_plan")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
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
