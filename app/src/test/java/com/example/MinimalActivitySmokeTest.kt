package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
            compose.onAllNodesWithText("۱ پیکربندی در دسترس").fetchSemanticsNodes().isNotEmpty()
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
}
