package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
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
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.LuxyBackupManager
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Clipboard via Subscriptions -> + -> actual activity -> parser -> Room -> connect readiness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimalActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun oneClipboardImportMakesConnectAvailableThroughSubscriptionsSheet() {
        compose.onNodeWithContentDescription("اتصال").assertIsNotEnabled()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "config",
                "vless://11111111-2222-3333-4444-555555555555@server.example:443?security=tls&type=tcp#My%20server"
            )
        )
        compose.onNodeWithText("اشتراک‌ها").performClick()
        compose.onNodeWithContentDescription("افزودن پیکربندی").performClick()
        compose.onNodeWithText("افزودن از کلیپ‌بورد").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("۱ پیکربندی دستی ذخیره شده است").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("خانه").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("۱ پیکربندی مجاز").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription("اتصال") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription("اتصال").assertIsEnabled()
        System.getenv("LUXY_SCREENSHOTS_DIR")?.let { directory ->
            val target = File(directory, "actual-home-imported.png").apply { parentFile.mkdirs() }
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun backupExportSurvivesRotationWithoutSavingItsPassphrase() {
        fun text(id: Int) = compose.activity.getString(id)
        val passphrase = "picker rotation test passphrase"
        val destination = Uri.parse("content://documents.test/backup.json")
        val output = ByteArrayOutputStream()
        shadowOf(compose.activity.contentResolver).registerOutputStream(destination, output)

        compose.onNodeWithText("اشتراک‌ها").performClick()
        val backup = text(R.string.backup_sheet_title)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(backup))
        compose.onNodeWithText(backup).performClick()
        compose.onNodeWithText(text(R.string.backup_export_title)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(passphrase)
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement(passphrase)
        compose.onNodeWithText(text(R.string.backup_export_action)).performClick()

        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        compose.runOnIdle {
            val state = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
            assertFalse(state.containsKey("pendingExportPassphrase"))
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertTrue(compose.activity.activityResultRegistry.dispatchResult(
                request.requestCode, Activity.RESULT_OK, Intent().setData(destination)
            ))
        }
        compose.waitUntil(15_000) { output.size() > 0 }
        assertTrue(LuxyBackupManager.importEncryptedBackup(output.toString("UTF-8"), passphrase).isSuccess)
    }
}
