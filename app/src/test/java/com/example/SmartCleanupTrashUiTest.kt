package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.local.ServerProfileEntity
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType
import com.example.data.model.TrashEntry
import com.example.ui.SmartCleanupSheet
import com.example.ui.theme.LuxyVpnTheme
import java.util.Locale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private lateinit var hostView: View

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

    private fun failedProfile(id: String, name: String) = ServerProfileEntity(
        id = id,
        name = name,
        protocol = "VLESS",
        address = "ams.example.com",
        port = 443,
        lastAttemptAt = 1_700_000_000_000L,
        lastErrorMessage = "TLS handshake timeout"
    )

    private fun failurePlan(defaultSelected: Boolean) = SmartCleanupPlan(
        groups = listOf(
            SmartCleanupGroup(
                category = SmartCleanupCategory.REPEATED_FAILURES,
                items = listOf(
                    SmartCleanupItem(
                        profile = failedProfile("fail-1", "Amsterdam edge"),
                        category = SmartCleanupCategory.REPEATED_FAILURES,
                        reason = "3 consecutive failures"
                    )
                ),
                defaultSelected = defaultSelected
            )
        ),
        totalSuggested = 1
    )

    private fun render(
        entries: List<TrashEntry>,
        plan: SmartCleanupPlan = SmartCleanupPlan(groups = emptyList(), totalSuggested = 0),
        language: String = "en",
        fontScale: Float = 1f,
        onDismissSuggestions: (List<SmartCleanupItem>) -> Unit = {}
    ) {
        compose.setContent {
            val baseConfiguration = LocalConfiguration.current
            val baseContext = LocalContext.current
            val config = androidx.compose.runtime.remember(language, baseConfiguration) {
                Configuration(baseConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
            }
            val context = androidx.compose.runtime.remember(baseContext, config) {
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
                    SmartCleanupSheet(
                        plan = plan,
                        trashEntries = entries,
                        activeNodeId = null,
                        language = language,
                        locale = if (language == "fa") Locale.forLanguageTag("fa") else Locale.US,
                        onMoveToTrash = {},
                        onRestoreTrash = {},
                        onPermanentlyDeleteTrash = {},
                        onDismiss = {},
                        onDismissSuggestions = onDismissSuggestions
                    )
                }
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

    @Test
    fun singleSuggestionSheetStaysCompactComparedToLargeTrashSheet() {
        val trash = (0 until 909).map { index -> entry("trash-$index", "Trash config $index") }
        var showTrash by mutableStateOf(false)
        compose.setContent {
            LuxyVpnTheme(MinimalTheme) {
                SmartCleanupSheet(
                    plan = if (showTrash) {
                        SmartCleanupPlan(groups = emptyList(), totalSuggested = 0)
                    } else {
                        failurePlan(defaultSelected = true)
                    },
                    trashEntries = if (showTrash) trash else emptyList(),
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

        val compactHeight = compose.onNodeWithTag("smart_cleanup_sheet_content")
            .getUnclippedBoundsInRoot().height
        assertTrue("single-suggestion sheet should be laid out", compactHeight.value > 100f)

        compose.runOnIdle { showTrash = true }
        openTrash(trash.size)
        val largeHeight = compose.onNodeWithTag("smart_cleanup_sheet_content")
            .getUnclippedBoundsInRoot().height

        assertTrue(
            "expected $largeHeight for 909 trash entries to be materially taller than $compactHeight",
            largeHeight.value > compactHeight.value * 1.4f
        )
    }

    @Test
    fun suggestionCardExplainsCategoryAndPreviewRevealsAffectedItem() {
        render(entries = listOf(entry("t1", "old-invalid-node")), plan = failurePlan(defaultSelected = true))

        compose.onNodeWithText("Configuration failed multiple recent connection attempts.")
            .assertIsDisplayed()
        compose.onNodeWithText("Amsterdam edge").assertDoesNotExist()
        screenshot("smart-cleanup-compact-en")

        compose.onNodeWithText("Preview").assertIsDisplayed().performClick()

        compose.onNodeWithText("Amsterdam edge").assertIsDisplayed()
        compose.onNodeWithText("3 consecutive failures").assertIsDisplayed()
        compose.onNodeWithText("Last attempt:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("TLS handshake timeout", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Hide").assertIsDisplayed()
        screenshot("smart-cleanup-preview-en")

        openTrash(1)
        compose.onNodeWithText("old-invalid-node").assertIsDisplayed()
        screenshot("smart-cleanup-trash-en")
    }

    @Test
    fun keepIsEnabledOnlyWithSelectionAndDismissesSelectedSuggestions() {
        val dismissed = mutableListOf<List<SmartCleanupItem>>()
        render(
            entries = emptyList(),
            plan = failurePlan(defaultSelected = false),
            onDismissSuggestions = { dismissed += it }
        )

        compose.onNodeWithText("Keep").assertIsNotEnabled()
        compose.onNodeWithText("Move to Trash (0)").assertIsNotEnabled()

        compose.onNodeWithText("Preview").performClick()
        compose.onNodeWithText("Amsterdam edge").performClick()

        compose.onNodeWithText("Keep").assertIsEnabled().performClick()

        assertEquals(1, dismissed.size)
        assertEquals(listOf("fail-1"), dismissed.single().map { it.profile.id })
        compose.onNodeWithText("Keep").assertIsNotEnabled()
    }

    @Config(qualifiers = "fa-w360dp-h720dp")
    @Test
    fun largePersianSmartCleanupRendersCleanly() {
        render(
            entries = listOf(entry("t1", "old-invalid-node")),
            plan = failurePlan(defaultSelected = true),
            language = "fa",
            fontScale = 1.6f
        )
        compose.onNodeWithText("پاک‌سازی هوشمند").assertIsDisplayed()
        compose.onNodeWithText("پیش‌نمایش").performClick()
        compose.onNodeWithText("Amsterdam edge").assertIsDisplayed()
        screenshot("smart-cleanup-fa-large")
    }

    private fun screenshot(name: String) {
        val directory = System.getenv("LUXY_SCREENSHOTS_DIR") ?: return
        val target = File(directory, "$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val windowViews = runCatching {
                val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                val instance = wmgClass.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                wmgClass.getMethod("getWindowViews").invoke(instance) as? List<View>
            }.getOrNull().orEmpty()
            val view = windowViews.lastOrNull { it.width > 0 && it.height > 0 } ?: hostView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
