package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.HealthStatus
import com.example.data.model.ManualConfigCounts
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
    fun subscriptionsScreenShowsIncludedExcludedConnectedAndAllowsSourceToggleWhileConnected() {
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
        compose.onNodeWithText("Changes apply to the next connection.").assertIsDisplayed()
        screenshot("subscriptions-sources-en")

        // Source switches remain enabled even while connected
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Backup Asia"))
        compose.onNodeWithText("Excluded").assertIsDisplayed()
        compose.onNodeWithContentDescription("Backup Asia: Use for connection").performClick()
        assertEquals("s2" to true, toggledSub)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manual Configurations"))
        compose.onNodeWithText("Included").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manual Configurations: Use for connection").performClick()
        assertEquals(false, toggledManual)

        // Tapping Manual Configurations card opens the overlay
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
        compose.onNodeWithText("✓ Verified").assertIsDisplayed()
        compose.onNodeWithText("≈ Estimated").assertIsDisplayed()
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

        // Select matching -> bulk bar appears -> Retest & Delete with confirmation
        compose.onNodeWithText("Select matching").performClick()
        compose.onNodeWithText("Retest").assertIsDisplayed().performClick()
        assertEquals(listOf("m3"), retestedIds)

        compose.onNodeWithText("Delete selected").performClick()
        compose.onNodeWithText("Delete selected configurations?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("m3"), deletedIds)

        // Restore backup sheet
        compose.onNodeWithText("Restore (2)").performClick()
        compose.onNodeWithText("Deleted Backups").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        assertEquals("b1", restoredBatch)
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
