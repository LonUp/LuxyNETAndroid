package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.Role
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.HealthStatus
import com.example.data.model.ManualConfigCounts
import com.example.data.model.MeasurementType
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.ui.ManualConfigurationsOverlay
import com.example.ui.SubscriptionsPage
import com.example.ui.theme.LuxyVpnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceAndManualUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View

    private fun sampleManualProfiles(): List<ServerProfileEntity> = listOf(
        ServerProfileEntity(
            id = "m1",
            name = "vless-ams-1",
            protocol = "VLESS",
            address = "ams.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            healthStatus = HealthStatus.WORKING.name,
            lastSuccessAt = System.currentTimeMillis(),
            latencyMs = 42,
            countryCode = "NL",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countryVerifiedAt = System.currentTimeMillis(),
            countrySource = CountrySource.EXIT_VERIFIED.name
        ),
        ServerProfileEntity(
            id = "m2",
            name = "cf-relay-07",
            protocol = "TROJAN",
            address = "de.example.com",
            port = 443,
            uuidOrPassword = "secret",
            healthStatus = HealthStatus.UNTESTED.name,
            countryCode = "DE",
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.METADATA.name
        ),
        ServerProfileEntity(
            id = "m3",
            name = "node-raw-24",
            protocol = "VMESS",
            address = "raw.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            healthStatus = HealthStatus.FAILED.name,
            countryConfidence = CountryConfidence.UNKNOWN.name
        ),
        ServerProfileEntity(
            id = "m4",
            name = "disabled-node",
            protocol = "VLESS",
            address = "off.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            isEnabled = false,
            healthStatus = HealthStatus.UNTESTED.name
        )
    )

    @Test
    fun subscriptionsScreenShowsConnectedAndAllowsSourceToggleWhileConnected() {
        var toggledSub: Pair<String, Boolean>? = null
        var toggledManual: Boolean? = null
        val sub1 = SubscriptionEntity(
            id = "s1", name = "Premium Europe", url = "https://a.example",
            nodeCount = 220, isEnabledForConnection = true, lastUpdatedAt = 1_795_000_000_000L, lastStatus = "Updated"
        )
        val sub2 = SubscriptionEntity(
            id = "s2", name = "Backup Asia", url = "https://b.example",
            nodeCount = 140, isEnabledForConnection = false, lastUpdatedAt = 1_795_000_000_000L, lastStatus = "Updated"
        )
        compose.setContent {
            hostView = LocalView.current
            LuxyVpnTheme(MinimalTheme) {
                SubscriptionsPage(
                    subscriptions = listOf(sub1, sub2),
                    managementEnabled = false, // Connected!
                    busy = false,
                    snackbar = remember { SnackbarHostState() },
                    onHome = {},
                    onRefresh = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    standaloneServerCount = 4,
                    manualConfigsEnabled = true,
                    manualProfiles = sampleManualProfiles(),
                    manualCounts = ManualConfigCounts(total = 4, working = 1, failed = 1, untested = 1, disabled = 1),
                    connectedSubscriptionId = "s1",
                    sourceChangePendingReconnect = true,
                    onToggleSubscriptionSource = { id, enabled -> toggledSub = id to enabled },
                    onToggleManualSource = { toggledManual = it }
                )
            }
        }

        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Manage your connection sources.").assertIsDisplayed()
        compose.onAllNodesWithText("Included").assertCountEquals(0)
        compose.onAllNodesWithText("Excluded").assertCountEquals(0)
        compose.onNodeWithContentDescription("Smart Cleanup").assertIsDisplayed()
        screenshot("subscriptions-sources-en")

        // Source switches remain enabled even while connected.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Backup Asia"))
        compose.onNodeWithContentDescription("Backup Asia: Use for connection").performClick()
        assertEquals("s2" to true, toggledSub)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithContentDescription("Manual Configurations: Use for connection").performClick()
        assertEquals(false, toggledManual)

        // Tapping Manual Configurations opens the overlay.
        compose.onNodeWithText("Manual Configurations").performClick()
        compose.onNodeWithText("Manage imported local configs.").assertIsDisplayed()
    }

    @Test
    fun manualConfigurationsOverlaySupportsSearchFilterBulkActionsAndLargeCollections() {
        val largeList = sampleManualProfiles() + (5..500).map { i ->
            ServerProfileEntity(
                id = "bulk-$i",
                name = "Bulk Node $i",
                protocol = "VLESS",
                address = "bulk$i.example.com",
                port = 443,
                uuidOrPassword = "11111111-2222-3333-4444-555555555555",
                sourceType = SourceType.MANUAL.name
            )
        }
        var retestedIds: List<String>? = null
        var deletedIds: List<String>? = null
        var restoredBatch: String? = null

        compose.setContent {
            hostView = LocalView.current
            LuxyVpnTheme(MinimalTheme) {
                ManualConfigurationsOverlay(
                    profiles = largeList,
                    counts = ManualConfigCounts(total = largeList.size, working = 1, failed = 1, untested = largeList.size - 3, disabled = 1),
                    deletedBackups = listOf(DeletedBackupBatch("b1", System.currentTimeMillis(), "Manual deletion", 2)),
                    lastDeletedBatch = null,
                    isRetesting = false,
                    retestProgress = null,
                    snackbar = remember { SnackbarHostState() },
                    onClose = {},
                    onToggleProfileEnabled = { _, _ -> },
                    onBulkSetEnabled = { _, _ -> },
                    onBulkDelete = { deletedIds = it },
                    onBulkRetest = { retestedIds = it },
                    onCancelRetest = {},
                    onRemoveDuplicates = {},
                    onRestoreBackup = { restoredBatch = it },
                    onClearLastDeletedBatch = {},
                    onReclassifyToManual = {},
                    onSetManualCountry = { _, _ -> },
                    onDetectCountries = {}
                )
            }
        }

        compose.onNodeWithText("Manual Configurations").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vless-ams-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("vless-ams-1").assertIsDisplayed()
        compose.onAllNodesWithText("✓ Verified").onFirst().assertIsDisplayed()
        compose.onNodeWithText("≈ Estimated").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox), useUnmergedTree = true).assertCountEquals(0)
        screenshot("manual-overlay-en")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("node-raw-24"))
        compose.onAllNodesWithText("? Unknown").onFirst().assertIsDisplayed()

        // Search filters down cleanly
        compose.onNodeWithText("Search name, address, country, protocol").performTextReplacement("node-raw-24")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vless-ams-1").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasText("node-raw-24") and !hasSetTextAction()).assertIsDisplayed()

        // Enter selection mode from More actions, then select the filtered match.
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Select configurations").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Select matching").performClick()
        compose.onNodeWithText("Retest").assertIsDisplayed().performClick()
        assertEquals(listOf("m3"), retestedIds)

        compose.onNodeWithText("Delete selected").performClick()
        compose.onNodeWithText("Delete selected configurations?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("m3"), deletedIds)

        // Recovery is only offered as Undo for the most recent deletion, not via a backup list.
        compose.onAllNodesWithText("Restore (2)").assertCountEquals(0)
        compose.onAllNodesWithText("Deleted Backups").assertCountEquals(0)
        assertEquals(null, restoredBatch)
    }

    private fun preferredProfiles(): List<ServerProfileEntity> = listOf(
        ServerProfileEntity(
            id = "p1",
            name = "ams-preferred",
            protocol = "VLESS",
            address = "ams.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            sourceType = SourceType.MANUAL.name,
            isPinned = true,
            healthStatus = HealthStatus.WORKING.name,
            lastSuccessAt = System.currentTimeMillis(),
            latencyMs = 42,
            lastMeasurementType = MeasurementType.TCP_CONNECT.name,
            countryCode = "NL",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countryVerifiedAt = System.currentTimeMillis(),
            countrySource = CountrySource.EXIT_VERIFIED.name
        ),
        ServerProfileEntity(
            id = "p2",
            name = "de-fallback",
            protocol = "TROJAN",
            address = "de.example.com",
            port = 443,
            uuidOrPassword = "secret",
            sourceType = SourceType.MANUAL.name,
            healthStatus = HealthStatus.WORKING.name,
            lastSuccessAt = System.currentTimeMillis() - 26L * 60L * 60L * 1000L,
            latencyMs = 90,
            lastMeasurementType = MeasurementType.TCP_CONNECT.name,
            countryCode = "DE",
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.METADATA.name
        )
    )

    private fun renderOverlay(
        profiles: List<ServerProfileEntity>,
        counts: ManualConfigCounts,
        plan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
        onSetProfilePreferred: (String, Boolean) -> Unit = { _, _ -> },
        onMoveCleanupToTrash: (List<SmartCleanupItem>) -> Unit = {},
        restoration: StateRestorationTester? = null
    ) {
        val content: @Composable () -> Unit = {
            hostView = LocalView.current
            LuxyVpnTheme(MinimalTheme) {
                ManualConfigurationsOverlay(
                    profiles = profiles,
                    counts = counts,
                    deletedBackups = emptyList(),
                    lastDeletedBatch = null,
                    isRetesting = false,
                    retestProgress = null,
                    snackbar = remember { SnackbarHostState() },
                    onClose = {},
                    onToggleProfileEnabled = { _, _ -> },
                    onBulkSetEnabled = { _, _ -> },
                    onBulkDelete = {},
                    onBulkRetest = {},
                    onCancelRetest = {},
                    onRemoveDuplicates = {},
                    onRestoreBackup = {},
                    onClearLastDeletedBatch = {},
                    onReclassifyToManual = {},
                    onSetManualCountry = { _, _ -> },
                    onDetectCountries = {},
                    smartCleanupPlan = plan,
                    onSetProfilePreferred = onSetProfilePreferred,
                    onMoveCleanupToTrash = onMoveCleanupToTrash
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    @Test
    fun manualRowsSeparateConfigNameFromVerifiedExitAndNameTheMeasurement() {
        renderOverlay(
            profiles = preferredProfiles(),
            counts = ManualConfigCounts(total = 2, working = 2, preferred = 1)
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("ams-preferred").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("ams-preferred").assertIsDisplayed()
        // The configured name stays distinct from the detected exit country.
        compose.onNodeWithText("Exit:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Est.", substring = true).assertIsDisplayed()
        // Fresh health includes its measurement; stale working health is untested, not "Stale".
        compose.onNodeWithText("Working · TCP 42 ms").assertIsDisplayed()
        compose.onAllNodesWithText("Untested").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Stale").assertCountEquals(0)
        compose.onAllNodesWithText("ping", substring = true).assertCountEquals(0)
    }

    @Test
    fun preferredFilterAndStarToggleKeepPinnedConfigurations() {
        var toggled: Pair<String, Boolean>? = null
        renderOverlay(
            profiles = preferredProfiles(),
            counts = ManualConfigCounts(total = 2, working = 2, preferred = 1),
            onSetProfilePreferred = { id, preferred -> toggled = id to preferred }
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("ams-preferred").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Toggle preferred for ams-preferred").performClick()
        compose.runOnIdle { assertEquals("p1" to false, toggled) }

        compose.onNodeWithText("★ Preferred").performClick()
        compose.onNodeWithText("★ Preferred").assertIsSelected()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("de-fallback").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("ams-preferred").assertIsDisplayed()
    }

    @Test
    fun manualHealthAndSecondaryFiltersComposeOrthogonally() {
        val now = System.currentTimeMillis()
        val verifiedPreferred = ServerProfileEntity(
            id = "filter-preferred-verified",
            name = "preferred-verified-untested",
            protocol = "VLESS",
            address = "verified.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            sourceType = SourceType.MANUAL.name,
            isPinned = true,
            healthStatus = HealthStatus.UNTESTED.name,
            countryCode = "NL",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countryVerifiedAt = now,
            countrySource = CountrySource.EXIT_VERIFIED.name
        )
        val unverified = ServerProfileEntity(
            id = "filter-unverified",
            name = "unverified-untested",
            protocol = "TROJAN",
            address = "unverified.example.com",
            port = 443,
            uuidOrPassword = "secret",
            sourceType = SourceType.MANUAL.name,
            healthStatus = HealthStatus.UNTESTED.name,
            countryCode = "DE",
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.METADATA.name
        )
        val working = ServerProfileEntity(
            id = "filter-working",
            name = "working-now-filter",
            protocol = "VMESS",
            address = "working.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            sourceType = SourceType.MANUAL.name,
            healthStatus = HealthStatus.WORKING.name,
            lastSuccessAt = now,
            countryCode = "US",
            countryConfidence = CountryConfidence.VERIFIED.name,
            countryVerifiedAt = now,
            countrySource = CountrySource.EXIT_VERIFIED.name
        )
        renderOverlay(
            profiles = listOf(verifiedPreferred, unverified, working),
            counts = ManualConfigCounts(total = 3, working = 1, untested = 2, preferred = 1)
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("preferred-verified-untested").fetchSemanticsNodes().isNotEmpty()
        }
        listOf("All", "Working", "Failed", "Untested", "Disabled").forEach {
            compose.onAllNodesWithText(it).onFirst().assertIsDisplayed()
        }
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onAllNodesWithText("0").assertCountEquals(2)

        // Health, preferred, and country filters remain independently composable.
        compose.onAllNodesWithText("Untested").onFirst().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("working-now-filter").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("working-now-filter").assertDoesNotExist()
        compose.onNodeWithText("★ Preferred").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("unverified-untested").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("preferred-verified-untested").assertIsDisplayed()
        compose.onNodeWithText("unverified-untested").assertDoesNotExist()
        compose.onAllNodesWithText("✓ Verified").onFirst().performClick()
        compose.onNodeWithText("preferred-verified-untested").assertIsDisplayed()
        compose.onNodeWithText("? Unverified").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("preferred-verified-untested").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("preferred-verified-untested").assertDoesNotExist()
        compose.onNodeWithText("★ Preferred").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("unverified-untested").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("unverified-untested").assertIsDisplayed()
        compose.onNodeWithText("working-now-filter").assertDoesNotExist()
    }

    @Test
    fun manualOverlaySmartCleanupHeaderOpensSmartCleanupSheet() {
        val duplicate = ServerProfileEntity(
            id = "dup-1",
            name = "dup-node",
            protocol = "VLESS",
            address = "dup.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            sourceType = SourceType.MANUAL.name
        )
        renderOverlay(
            profiles = listOf(duplicate),
            counts = ManualConfigCounts(total = 1, untested = 1),
            plan = SmartCleanupPlan(
                groups = listOf(
                    SmartCleanupGroup(
                        category = SmartCleanupCategory.DUPLICATES,
                        items = listOf(
                            SmartCleanupItem(
                                profile = duplicate,
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
        compose.onNodeWithContentDescription("Smart Cleanup").assertIsDisplayed().performClick()
        compose.onNodeWithText("Smart Cleanup").assertIsDisplayed()
        compose.onNodeWithText("Suggestions").assertIsDisplayed()
    }

    @Config(qualifiers = "fa-w360dp-h720dp")
    @Test
    fun largePersianManualOverlayKeepsFiltersAndSwitchesUnclipped() {
        val profiles = sampleManualProfiles()
        compose.setContent {
            hostView = LocalView.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density,
                    1.6f
                )
            ) {
                LuxyVpnTheme(MinimalTheme) {
                    ManualConfigurationsOverlay(
                        profiles = profiles,
                        counts = ManualConfigCounts(total = 4, working = 1, failed = 1, untested = 1, disabled = 1),
                        deletedBackups = emptyList(),
                        lastDeletedBatch = null,
                        isRetesting = false,
                        retestProgress = null,
                        snackbar = remember { SnackbarHostState() },
                        onClose = {},
                        onToggleProfileEnabled = { _, _ -> },
                        onBulkSetEnabled = { _, _ -> },
                        onBulkDelete = {},
                        onBulkRetest = {},
                        onCancelRetest = {},
                        onRemoveDuplicates = {},
                        onRestoreBackup = {},
                        onClearLastDeletedBatch = {},
                        onReclassifyToManual = {},
                        onSetManualCountry = { _, _ -> },
                        onDetectCountries = {}
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vless-ams-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("پیکربندی‌های دستی").assertIsDisplayed()
        compose.onNodeWithText("vless-ams-1").assertIsDisplayed()
        screenshot("manual-overlay-fa-large")
    }

    @Test
    fun manualSelectionSurvivesSavedStateRestore() {
        val profiles = sampleManualProfiles()
        val restoration = StateRestorationTester(compose)
        renderOverlay(
            profiles = profiles,
            counts = ManualConfigCounts(total = profiles.size, working = 1, failed = 1, untested = 1, disabled = 1),
            restoration = restoration
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vless-ams-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Select configurations").performClick()
        compose.onNodeWithText("vless-ams-1").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vless-ams-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
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
