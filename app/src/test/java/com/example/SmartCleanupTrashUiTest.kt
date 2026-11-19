package com.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.data.model.TrashEntry
import com.example.ui.SmartCleanupSheet
import com.example.ui.theme.LuxyVpnTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartCleanupTrashUiTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(id: String, name: String) = TrashEntry(
        id = id,
        batchId = "batch",
        deletedAt = 1_700_000_000_000L,
        expiresAt = 1_702_592_000_000L,
        label = "Manual cleanup",
        category = SmartCleanupCategory.INVALID.name,
        reason = "Missing credentials",
        profileId = id,
        profileName = name,
        sourceType = SourceType.MANUAL.name,
        subscriptionId = null,
        profile = null
    )

    private fun render(entries: List<TrashEntry>) {
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SmartCleanupSheet(
                    plan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
                    trashEntries = entries,
                    activeNodeId = null,
                    language = "en",
                    locale = Locale.US,
                    onMoveToTrash = {},
                    onRestoreTrash = {},
                    onPermanentlyDeleteTrash = {},
                    onDismiss = {}
                )
            }
        }
    }

    private fun openTrash(count: Int) {
        compose.onNodeWithText("Trash ($count)").performClick()
    }

    @Test
    fun largeTrashUsesScrollableLazyRowsAndSupportsBulkSelection() {
        val entries = (0 until 909).map { index -> entry("trash-$index", "Trash config $index") }
        render(entries)
        openTrash(entries.size)

        val trashList = compose.onNodeWithTag("smart_cleanup_trash_list")
        trashList.assertExists()
        trashList.performScrollToNode(hasText("Trash config 908"))
        compose.onNodeWithText("Trash config 908").assertIsDisplayed()

        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Clear (909)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Clear (0)").assertIsDisplayed()
    }

    @Test
    fun tappingLongTrashNameOpensFullConfigurationDetails() {
        val longName = "long-vless-configuration-" + "north-europe-".repeat(8)
        render(listOf(entry("long-entry", longName)))
        openTrash(1)

        compose.onNodeWithText(longName).performClick()
        compose.onNodeWithText("Configuration details").assertIsDisplayed()
        compose.onAllNodesWithText(longName).assertCountEquals(2)
        compose.onAllNodesWithText(longName).onLast().assertIsDisplayed()
        compose.onNodeWithText("Source: Manual").assertIsDisplayed()
        compose.onNodeWithText("Category: Structurally invalid").assertIsDisplayed()
        compose.onNodeWithText("Reason: Missing credentials").assertIsDisplayed()
        compose.onNodeWithText("Deleted:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Expires:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restore (1)").assertIsDisplayed()
        compose.onNodeWithText("Delete permanently (1)").assertIsDisplayed()
    }
}
