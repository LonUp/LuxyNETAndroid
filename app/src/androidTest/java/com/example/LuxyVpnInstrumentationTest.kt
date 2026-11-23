package com.example

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.LuxyBackupManager
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ConnectionState
import com.example.data.model.HealthStatus
import com.example.data.model.StabilitySettings
import com.example.i18n.Digits
import com.example.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class LuxyVpnInstrumentationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: LuxyVpnApplication

    @Before
    fun setUp() = runBlocking {
        app = compose.activity.application as LuxyVpnApplication
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("instrumentation setup")
            app.repository.ensureMigrated(app.countryDetector)
            clearAllData()
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.IO) {
            app.tunnelManager.disconnectAndAwait("instrumentation teardown")
            clearAllData()
        }
    }

    private suspend fun clearAllData() {
        app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
        app.repository.getAllSubscriptions().forEach { app.repository.deleteSubscriptionAndNodes(it.id) }
        val trashIds = app.repository.trashEntriesFlow.first().map { it.id }
        if (trashIds.isNotEmpty()) {
            app.repository.permanentlyDeleteTrashEntries(trashIds)
        }
        app.repository.updateSettings { StabilitySettings() }
        LocaleManager.setTag(app, LocaleManager.DEFAULT_TAG)
    }

    private fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun lang(): String = LocaleManager.currentTag(compose.activity)

    private fun localizedCount(n: Int): String = Digits.localize(n.toString(), lang())

    private fun sampleProfile(
        id: String = "inst-node-1",
        name: String = "Instrumentation Node",
        healthStatus: HealthStatus = HealthStatus.UNTESTED
    ) = ServerProfileEntity(
        id = id,
        name = name,
        address = "198.51.100.42",
        port = 443,
        protocol = "VLESS",
        security = "TLS",
        transport = "TCP",
        uuidOrPassword = "11111111-2222-3333-4444-555555555555",
        healthStatus = healthStatus.name
    )

    @Test
    fun importFromClipboardPersistsProfileAndEnablesConnect() {
        Espresso.onView(withId(android.R.id.content)).check(matches(isDisplayed()))
        compose.onNodeWithContentDescription(str(R.string.minimal_connect)).assertIsNotEnabled()

        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "config",
                    "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?security=tls&type=tcp#Espresso%20Server"
                )
            )
        }

        compose.onNodeWithText(str(R.string.sub_title)).performClick()
        compose.onNodeWithContentDescription(str(R.string.sub_add_cd)).performClick()
        compose.onNodeWithText(str(R.string.sub_add_clipboard_title)).performClick()

        val oneManual = str(R.string.manual_configs_count_one, localizedCount(1))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(oneManual).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(str(R.string.manual_title)).performClick()
        compose.onNodeWithText("Espresso Server").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.manual_back_subscriptions)).performClick()

        Espresso.pressBack()

        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(str(R.string.minimal_connect)) and isEnabled())
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription(str(R.string.minimal_connect)).assertIsEnabled()
        val stored = runBlocking(Dispatchers.IO) { app.repository.getAllProfiles() }
        assertEquals(1, stored.size)
        assertEquals("Espresso Server", stored.single().name)
    }

    @Test
    fun connectAndDisconnectTransitionsTunnelStateAndReturnsIdle() {
        Espresso.onView(withId(android.R.id.content)).check(matches(isDisplayed()))
        val node = sampleProfile(id = "connect-node", name = "Connect Test Node")
        runBlocking(Dispatchers.IO) { app.repository.saveProfiles(listOf(node)) }

        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(str(R.string.minimal_connect)) and isEnabled())
                .fetchSemanticsNodes().size == 1
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (VpnService.prepare(app) != null) {
            runCatching {
                val pfd = instrumentation.uiAutomation.executeShellCommand(
                    "appops set ${app.packageName} ACTIVATE_VPN allow"
                )
                FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                pfd.close()
            }
        }

        if (VpnService.prepare(app) == null) {
            compose.onNodeWithContentDescription(str(R.string.minimal_connect)).performClick()
            compose.waitUntil(15_000) {
                app.tunnelManager.telemetry.value.connectionState != ConnectionState.DISCONNECTED
            }

            when (app.tunnelManager.telemetry.value.connectionState) {
                ConnectionState.CONNECTED ->
                    runCatching { compose.onNodeWithContentDescription(str(R.string.minimal_disconnect)).performClick() }
                ConnectionState.ERROR ->
                    runBlocking(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("reset error") }
                else ->
                    runCatching { compose.onNodeWithContentDescription(str(R.string.minimal_cancel)).performClick() }
            }
            when (app.tunnelManager.telemetry.value.connectionState) {
                ConnectionState.CONNECTED ->
                    compose.onNodeWithContentDescription(str(R.string.minimal_disconnect)).performClick()
                ConnectionState.ERROR ->
                    runBlocking(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("reset error") }
                else -> Unit
            }
            compose.waitUntil(15_000) {
                app.tunnelManager.telemetry.value.connectionState == ConnectionState.DISCONNECTED &&
                    compose.onAllNodes(hasContentDescription(str(R.string.minimal_connect)) and isEnabled())
                        .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(str(R.string.minimal_disconnected)).assertIsDisplayed()
        } else {
            // Device policy blocked shell appops VPN grant; verify permission denial and tunnel failure recovery
            // without hanging on the external system VPN confirmation dialog.
            val prepareIntent = VpnService.prepare(app)
            val action = prepareIntent?.action
            if (!action.isNullOrBlank()) {
                val monitor = instrumentation.addMonitor(
                    IntentFilter(action),
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
                    true
                )
                try {
                    compose.onNodeWithContentDescription(str(R.string.minimal_connect)).performClick()
                    compose.waitUntil(10_000) { monitor.hits > 0 }
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
            app.tunnelManager.connectCandidates(listOf(node))
            compose.waitUntil(15_000) {
                app.tunnelManager.telemetry.value.connectionState == ConnectionState.ERROR
            }
            runBlocking(Dispatchers.IO) { app.tunnelManager.disconnectAndAwait("cleanup after denied vpn") }
            compose.waitUntil(10_000) {
                app.tunnelManager.telemetry.value.connectionState == ConnectionState.DISCONNECTED
            }
            compose.onNodeWithContentDescription(str(R.string.minimal_connect)).assertIsEnabled()
        }
    }

    @Test
    fun encryptedBackupExportAndRestoreRoundTripsThroughDocumentPicker() {
        Espresso.onView(withId(android.R.id.content)).check(matches(isDisplayed()))
        val node = sampleProfile(id = "backup-node", name = "Backup Secret Node")
        val sub = SubscriptionEntity(
            id = "backup-sub",
            name = "Backup Plan",
            url = "https://example.test/private-backup-feed"
        )
        runBlocking(Dispatchers.IO) {
            app.repository.saveProfiles(listOf(node))
            app.repository.saveSubscription(sub)
        }

        val backupUri = TestBackupDocumentProvider.uriFor("audit-backup.json")
        app.contentResolver.delete(backupUri, null, null)
        val passphrase = "audit-backup-passphrase"
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        compose.onNodeWithText(str(R.string.sub_title)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Backup Plan").fetchSemanticsNodes().isNotEmpty()
        }

        val createFilter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addDataType("*/*") }
        val createMonitor = instrumentation.addMonitor(
            createFilter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(backupUri)),
            true
        )
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.backup_sheet_title)))
            compose.onNodeWithText(str(R.string.backup_sheet_title)).performClick()
            compose.onNodeWithText(str(R.string.backup_export_title)).performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(passphrase)
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(passphrase)
            compose.onNodeWithText(str(R.string.backup_export_action)).performClick()

            compose.waitUntil(15_000) {
                runCatching {
                    app.contentResolver.openInputStream(backupUri)?.use { it.readBytes().isNotEmpty() } == true
                }.getOrDefault(false)
            }
        } finally {
            instrumentation.removeMonitor(createMonitor)
        }

        val rawBackup = app.contentResolver.openInputStream(backupUri)!!.bufferedReader().use { it.readText() }
        assertFalse(rawBackup.contains("Backup Secret Node"))
        assertFalse(rawBackup.contains("private-backup-feed"))
        assertTrue(LuxyBackupManager.importEncryptedBackup(rawBackup, passphrase).isSuccess)

        runBlocking(Dispatchers.IO) {
            app.repository.deleteProfile(node.id)
            app.repository.deleteSubscriptionAndNodes(sub.id)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Backup Plan").fetchSemanticsNodes().isEmpty()
        }

        val openFilter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addDataType("*/*") }
        val openMonitor = instrumentation.addMonitor(
            openFilter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(backupUri)),
            true
        )
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.backup_sheet_title)))
            compose.onNodeWithText(str(R.string.backup_sheet_title)).performClick()
            compose.onNodeWithText(str(R.string.backup_restore_title)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(passphrase)
            compose.onNodeWithText(str(R.string.backup_restore_action)).performClick()

            compose.waitUntil(15_000) {
                runBlocking(Dispatchers.IO) {
                    app.repository.getAllProfiles().any { it.name == "Backup Secret Node" } &&
                        app.repository.getAllSubscriptions().any { it.name == "Backup Plan" }
                }
            }
        } finally {
            instrumentation.removeMonitor(openMonitor)
            app.contentResolver.delete(backupUri, null, null)
        }

        compose.onNodeWithText("Backup Plan").assertIsDisplayed()
    }

    @Test
    fun smartCleanupMovesToTrashRestoresAndPermanentlyDeletes() {
        Espresso.onView(withId(android.R.id.content)).check(matches(isDisplayed()))
        val invalid = sampleProfile(
            id = "trash-node-1",
            name = "Broken Trash Node",
            healthStatus = HealthStatus.INVALID
        )
        runBlocking(Dispatchers.IO) { app.repository.saveProfiles(listOf(invalid)) }

        compose.onNodeWithText(str(R.string.sub_title)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.manual_configs_count_one, localizedCount(1)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription(str(R.string.cleanup_title)).performClick()
        val moveOne = str(R.string.cleanup_move_to_trash, localizedCount(1))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(moveOne).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(moveOne).performClick()

        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.IO) {
                app.repository.getAllProfiles().isEmpty() &&
                    app.repository.trashEntriesFlow.first().size == 1
            }
        }

        compose.onNodeWithText(str(R.string.cleanup_tab_trash, localizedCount(1))).performClick()
        compose.onNodeWithContentDescription("Broken Trash Node").performClick()
        compose.onNodeWithText(str(R.string.cleanup_restore_selected, localizedCount(1))).performClick()

        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.IO) {
                app.repository.getAllProfiles().size == 1 &&
                    app.repository.trashEntriesFlow.first().isEmpty()
            }
        }

        compose.onNodeWithText(str(R.string.cleanup_tab_suggestions)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.cleanup_cat_invalid)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.cleanup_cat_invalid)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(moveOne).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(moveOne).performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.cleanup_tab_trash, localizedCount(1)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.cleanup_tab_trash, localizedCount(1))).performClick()
        compose.onNodeWithContentDescription("Broken Trash Node").performClick()
        compose.onNodeWithText(str(R.string.cleanup_delete_permanently, localizedCount(1))).performClick()
        compose.onNodeWithText(str(R.string.cleanup_perm_delete_confirm)).performClick()

        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.IO) {
                app.repository.getAllProfiles().isEmpty() &&
                    app.repository.trashEntriesFlow.first().isEmpty()
            }
        }
        compose.onNodeWithText(str(R.string.cleanup_trash_empty)).assertIsDisplayed()
    }
}
