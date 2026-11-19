package com.example

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.data.model.TrashEntry
import com.example.data.model.TunnelTelemetry
import com.example.data.model.VerificationState
import com.example.ui.ConnectionDetailsCard
import com.example.ui.ConnectionErrorBanner
import com.example.ui.ExportBackupDialog
import com.example.ui.RestoreBackupDialog
import com.example.ui.SmartCleanupSheet
import com.example.ui.theme.LuxyVpnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionDetailsAndCleanupUiTest {
    @get:Rule val compose = createComposeRule()

    private fun connectedTelemetry(
        state: VerificationState = VerificationState.VERIFIED,
        exitIp: String? = "203.0.113.7",
        countryCode: String? = "NL",
        confidence: CountryConfidence = CountryConfidence.VERIFIED,
        lockdownEnabled: Boolean = false,
        exitVerified: Boolean = false
    ) = TunnelTelemetry(
        connectionState = ConnectionState.CONNECTED,
        connectedAtElapsedMs = SystemClock.elapsedRealtime() - 3_723_000L,
        rxBytes = 12_345_678L,
        txBytes = 1_234_567L,
        rxBytesPerSec = 2_048L,
        txBytesPerSec = 512L,
        exitIp = exitIp,
        exitCountryCode = countryCode,
        exitCountryConfidence = confidence,
        dnsLeakProtected = true,
        ipv6LeakProtected = true,
        lockdownEnabled = lockdownEnabled,
        exitVerified = exitVerified,
        verificationState = state
    )

    private fun profile(id: String, name: String) = ServerProfileEntity(
        id = id,
        name = name,
        protocol = "VLESS",
        address = "$id.example.com",
        port = 443,
        uuidOrPassword = "11111111-2222-3333-4444-555555555555"
    )

    private fun cleanupPlan() = SmartCleanupPlan(
        groups = listOf(
            SmartCleanupGroup(
                category = SmartCleanupCategory.DUPLICATES,
                items = listOf(
                    SmartCleanupItem(
                        profile = profile("d1", "dup-node-a"),
                        category = SmartCleanupCategory.DUPLICATES,
                        reason = "Same host and credentials"
                    ),
                    SmartCleanupItem(
                        profile = profile("d2", "dup-node-b"),
                        category = SmartCleanupCategory.DUPLICATES,
                        reason = "Same host and credentials"
                    )
                ),
                defaultSelected = false
            )
        ),
        totalSuggested = 2
    )

    private fun trashEntry() = TrashEntry(
        id = "t1",
        batchId = "batch-1",
        deletedAt = 1_700_000_000_000L,
        expiresAt = 1_702_592_000_000L,
        label = "Manual cleanup",
        category = SmartCleanupCategory.INVALID.name,
        reason = "Missing credentials",
        profileId = "p9",
        profileName = "trash-node",
        sourceType = SourceType.MANUAL.name,
        subscriptionId = null,
        profile = null
    )

    @Test
    fun connectionDetailsCardStartsCollapsedThenExpandsToEveryRow() {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                var expanded by remember { mutableStateOf(false) }
                ConnectionDetailsCard(
                    telemetry = connectedTelemetry(),
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    language = "en",
                    locale = Locale.US
                )
            }
        }

        compose.onNodeWithText("Connection Details").assertIsDisplayed()
        compose.onNodeWithText("Exit IP").assertDoesNotExist()
        compose.onNodeWithText("Status").assertDoesNotExist()

        compose.onNodeWithText("Connection Details").performClick()

        listOf("Exit IP", "Exit Country", "Uptime", "Download", "Upload", "Total", "Status")
            .forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
        compose.onNodeWithText("203.0.113.7").assertIsDisplayed()
        compose.onNodeWithText("Netherlands (Verified)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2.0 KB/s").assertIsDisplayed()
        compose.onNodeWithText("512 B/s").assertIsDisplayed()
        compose.onNodeWithText("↓ 11.8 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("↑ 1.2 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Exit verified · Tunnel DNS & IPv6 active").assertIsDisplayed()
    }
    private fun renderDetails(telemetry: TunnelTelemetry) {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionDetailsCard(
                    telemetry = telemetry,
                    expanded = true,
                    onToggleExpanded = {},
                    language = "en",
                    locale = Locale.US
                )
            }
        }
    }

    @Test
    fun connectionDetailsPartialStateNeverClaimsNetworkProtection() {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionDetailsCard(
                    telemetry = connectedTelemetry(
                        state = VerificationState.PARTIAL,
                        exitIp = null,
                        countryCode = null,
                        confidence = CountryConfidence.UNKNOWN
                    ),
                    expanded = true,
                    onToggleExpanded = {},
                    language = "en",
                    locale = Locale.US
                )
            }
        }

        compose.onNodeWithText("Connected · Leak check incomplete").assertIsDisplayed()
        compose.onNodeWithText("Network protected", substring = true).assertDoesNotExist()
        // Both Exit IP and Exit Country fall back to an explicit unknown label, never a fake IP.
        compose.onAllNodesWithText("Unknown").assertCountEquals(2)
    }

    @Test
    fun connectionDetailsIdleNeverClaimsNetworkProtection() {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionDetailsCard(
                    telemetry = connectedTelemetry(
                        state = VerificationState.IDLE,
                        exitIp = null,
                        countryCode = null,
                        confidence = CountryConfidence.UNKNOWN
                    ),
                    expanded = true,
                    onToggleExpanded = {},
                    language = "en",
                    locale = Locale.US
                )
            }
        }

        compose.onNodeWithText("Tunnel active · Exit unverified").assertIsDisplayed()
        compose.onNodeWithText("Network protected", substring = true).assertDoesNotExist()
    }
    @Test
    fun connectionDetailsUsesExplicitConnectionStateAndHidesStaleValues() {
        val cases = listOf(
            ConnectionState.DISCONNECTED to "Disconnected",
            ConnectionState.CONNECTING to "Connecting…",
            ConnectionState.DISCONNECTING to "Disconnecting…",
            ConnectionState.RECONNECTING to "Reconnecting…",
            ConnectionState.FAILOVER to "Switching server…",
            ConnectionState.ERROR to "Connection failed"
        )
        var currentTelemetry by mutableStateOf(
            connectedTelemetry(lockdownEnabled = true).copy(connectionState = cases.first().first)
        )
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionDetailsCard(
                    telemetry = currentTelemetry,
                    expanded = true,
                    onToggleExpanded = {},
                    language = "en",
                    locale = Locale.US
                )
            }
        }
        cases.forEach { (connectionState, expectedStatus) ->
            compose.runOnIdle {
                currentTelemetry = connectedTelemetry(lockdownEnabled = true).copy(connectionState = connectionState)
            }
            compose.onNodeWithText(expectedStatus).assertIsDisplayed()
            compose.onAllNodesWithText("Tunnel active", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Network protected", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("203.0.113.7").assertCountEquals(0)
            compose.onAllNodesWithText("↓ 11.8 MB", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("—").assertCountEquals(6)
        }
    }

    @Test
    fun connectedCheckingAndFailedVerificationDoNotClaimAnActiveVerifiedTunnel() {
        val cases = listOf(
            VerificationState.CHECKING to "Checking…",
            VerificationState.FAILED to "Verification failed"
        )
        var currentTelemetry by mutableStateOf(connectedTelemetry(state = cases.first().first))
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionDetailsCard(
                    telemetry = currentTelemetry,
                    expanded = true,
                    onToggleExpanded = {},
                    language = "en",
                    locale = Locale.US
                )
            }
        }
        cases.forEach { (verification, expectedStatus) ->
            compose.runOnIdle {
                currentTelemetry = connectedTelemetry(state = verification)
            }
            compose.onNodeWithText(expectedStatus).assertIsDisplayed()
            compose.onAllNodesWithText("Tunnel active", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Network protected", substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun partialExitVerificationUsesExitOnlyStatus() {
        renderDetails(
            connectedTelemetry(
                state = VerificationState.PARTIAL,
                exitVerified = true
            )
        )
        compose.onNodeWithText("Exit verified · Leak check incomplete").assertIsDisplayed()
        compose.onAllNodesWithText("Network protected", substring = true).assertCountEquals(0)
    }

    @Test
    fun verifiedLockdownDetailsNameSystemLockdown() {
        renderDetails(connectedTelemetry(lockdownEnabled = true))
        compose.onNodeWithText("Exit verified · System lockdown active").assertIsDisplayed()
    }

    @Config(qualifiers = "fa-w360dp-h720dp")
    @Test
    fun connectionErrorBannerTranslatesKnownErrorsToPersian() {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionErrorBanner(
                    errorMessage = "Connection failed",
                    technicalDetail = null
                )
            }
        }
        compose.onNodeWithText("اتصال برقرار نشد").assertIsDisplayed()
        compose.onAllNodesWithText("Connection failed").assertCountEquals(0)
    }

    @Test
    fun connectionErrorBannerHidesTechnicalDetailUntilTapped() {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ConnectionErrorBanner(
                    errorMessage = "Could not reach the server.",
                    technicalDetail = "tcp: dial 10.0.0.5:443 timeout"
                )
            }
        }

        compose.onNodeWithText("Could not reach the server.").assertIsDisplayed()
        compose.onNodeWithText("tcp: dial 10.0.0.5:443 timeout").assertDoesNotExist()

        compose.onNodeWithText("Technical details").performClick()
        compose.onNodeWithText("tcp: dial 10.0.0.5:443 timeout").assertIsDisplayed()

        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithText("tcp: dial 10.0.0.5:443 timeout").assertDoesNotExist()
    }

    @Test
    fun smartCleanupSheetPreviewsAndMovesSelectedItemsToTrash() {
        var moved: List<SmartCleanupItem>? = null
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SmartCleanupSheet(
                    plan = cleanupPlan(),
                    trashEntries = listOf(trashEntry()),
                    activeNodeId = null,
                    language = "en",
                    locale = Locale.US,
                    onMoveToTrash = { moved = it },
                    onRestoreTrash = {},
                    onPermanentlyDeleteTrash = {},
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText("Smart Cleanup").assertIsDisplayed()
        compose.onNodeWithText("Review suggested configurations before moving to Trash.")
            .assertIsDisplayed()
        compose.onNodeWithText("Genuine duplicates").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Move to Trash (0)").assertIsDisplayed()
        compose.onNodeWithText("dup-node-a").assertDoesNotExist()

        compose.onNodeWithText("Preview").performClick()
        compose.onNodeWithText("dup-node-a").assertIsDisplayed()
        compose.onNodeWithText("dup-node-b").assertIsDisplayed()
        compose.onNodeWithText("Hide").assertIsDisplayed()

        compose.onNodeWithText("dup-node-a").performClick()
        compose.onNodeWithText("dup-node-b").performClick()
        compose.onNodeWithText("Move to Trash (2)").performClick()

        assertEquals(2, moved?.size)
        assertEquals(setOf("d1", "d2"), moved?.map { it.profile.id }?.toSet())
    }

    @Test
    fun smartCleanupTrashRequiresExplicitConfirmationBeforePermanentDeletion() {
        var restored: List<String>? = null
        var deleted: List<String>? = null
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SmartCleanupSheet(
                    plan = cleanupPlan(),
                    trashEntries = listOf(trashEntry()),
                    activeNodeId = null,
                    language = "en",
                    locale = Locale.US,
                    onMoveToTrash = {},
                    onRestoreTrash = { restored = it },
                    onPermanentlyDeleteTrash = { deleted = it },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText("Trash (1)").performClick()
        compose.onNodeWithText(
            "Items in Trash are kept for 30 days before automatic removal."
        ).assertIsDisplayed()
        compose.onNodeWithText("trash-node").assertIsDisplayed()
        compose.onNodeWithText("Structurally invalid", substring = true).assertIsDisplayed()

        compose.onNodeWithText("trash-node").performClick()
        compose.onNodeWithText("Restore (1)").performClick()
        assertEquals(listOf("t1"), restored)

        compose.onNodeWithText("trash-node").performClick()
        compose.onNodeWithText("Delete permanently (1)").performClick()

        compose.onNodeWithText("Permanently delete configurations?").assertIsDisplayed()
        assertNull(deleted)

        compose.onNodeWithText("Delete permanently").performClick()
        assertEquals(listOf("t1"), deleted)
    }

    @Test
    fun exportBackupDialogRejectsShortAndMismatchedPassphrases() {
        var exported: String? = null
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                ExportBackupDialog(onConfirm = { exported = it }, onDismiss = {})
            }
        }

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("abc")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("abc")
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Enter at least 6 characters").assertIsDisplayed()
        assertNull(exported)

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("secret1")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("secret2")
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Passphrases do not match").assertIsDisplayed()
        assertNull(exported)

        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("secret1")
        compose.onNodeWithText("Export").performClick()
        assertEquals("secret1", exported)
    }

    @Test
    fun restoreBackupDialogRequiresSixCharacterPassphrase() {
        var restored: String? = null
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                RestoreBackupDialog(onConfirm = { restored = it }, onDismiss = {})
            }
        }

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("abc")
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithText("Enter at least 6 characters").assertIsDisplayed()
        assertNull(restored)

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("secret1")
        compose.onNodeWithText("Restore").performClick()
        assertEquals("secret1", restored)
    }
}
