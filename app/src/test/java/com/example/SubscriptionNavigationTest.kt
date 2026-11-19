package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.data.local.SubscriptionEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedSubscriptionIsVisibleAndBackReturnsToTheConnectionPage() {
        val repository = (compose.activity.application as LuxyVpnApplication).repository
        runBlocking {
            repository.saveSubscription(SubscriptionEntity(
                name = "My subscription", url = "https://example.com/sub/private",
                nodeCount = 10, uploadBytes = 1024L * 1024 * 1024,
                downloadBytes = 4L * 1024 * 1024 * 1024,
                totalBytes = 20L * 1024 * 1024 * 1024,
                expireAtEpochSec = System.currentTimeMillis() / 1000 + 86400 * 12,
                lastUpdatedAt = System.currentTimeMillis()
            ))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sub_title)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("My subscription").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("My subscription").assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.sources_included)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.sub_status_active)).assertCountEquals(0)
        System.getenv("LUXY_SCREENSHOTS_DIR")?.let { directory ->
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(directory, "actual-subscriptions.png").apply { parentFile.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sub_edit)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sub_name)).performTextReplacement("Renamed subscription")
        compose.onNodeWithText(compose.activity.getString(R.string.sub_url)).performTextReplacement("ftp://example.com/invalid")
        compose.onNodeWithText(compose.activity.getString(R.string.sub_save)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sub_invalid)).assertIsDisplayed()
        assertEquals("My subscription", runBlocking { repository.getAllSubscriptions().single().name })
        compose.onNodeWithText(compose.activity.getString(R.string.sub_url))
            .performTextReplacement("https://example.com/sub/private")
        compose.onNodeWithText(compose.activity.getString(R.string.sub_save)).performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.getAllSubscriptions().single().name == "Renamed subscription" }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.minimal_connect))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sub_title)).assertIsEnabled()
        compose.onAllNodesWithText(compose.activity.getString(R.string.sub_add_clipboard_title)).assertCountEquals(0)
    }
}

