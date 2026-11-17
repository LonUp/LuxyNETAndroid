package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.model.ConnectionState
import com.example.ui.MinimalHome
import com.example.ui.theme.LuxyVpnTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimalHomeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View

    private fun render(
        state: ConnectionState = ConnectionState.DISCONNECTED,
        servers: Int = 3,
        importing: Boolean = false,
        selecting: Boolean = false,
        language: String = "en",
        fontScale: Float = 1f
    ) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            hostView = LocalView.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                LuxyVpnTheme(MinimalTheme) {
                    MinimalHome(
                        state = state,
                        serverCount = servers,
                        importing = importing,
                        selecting = selecting,
                        permissionPending = false,
                        snackbar = remember { SnackbarHostState() },
                        onConnect = {},
                        onImportClipboard = {},
                        onImportFile = {}
                    )
                }
            }
        }
    }

    @Test fun importingCannotConnectToAnIncompleteListOrOverwriteIt() {
        render(importing = true)
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Import from clipboard").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Import configuration file").assertIsNotEnabled()
    }

    @Test fun automaticSelectionCanBeCancelledButNotOverwrittenByImport() {
        render(selecting = true, servers = 0)
        compose.onNodeWithContentDescription("Cancel").assertIsEnabled()
        compose.onNodeWithText("Import from clipboard").assertIsNotEnabled()
    }

    @Test fun failedConnectionCanBeRetriedWithoutSelectingAServer() {
        render(state = ConnectionState.ERROR)
        compose.onNodeWithContentDescription("Connect").assertIsEnabled()
        compose.onNodeWithText("Import from clipboard").assertIsEnabled()
    }

    @Test fun emptyLibraryDoesNotOfferAnUnusableConnection() {
        render(servers = 0)
        compose.onNodeWithContentDescription("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Import from clipboard").assertIsEnabled()
        screenshot("empty-en")
    }

    @Test fun largePersianTextKeepsBothEssentialActionsReachable() {
        render(language = "fa", fontScale = 1.8f)
        compose.onNodeWithContentDescription("اتصال").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("ready-fa-large")
        compose.onNodeWithText("افزودن از کلیپ‌بورد").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("افزودن فایل پیکربندی").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun connectedStateKeepsDisconnectAvailableAndLocksImports() {
        render(state = ConnectionState.CONNECTED, language = "fa")
        compose.onNodeWithContentDescription("قطع اتصال").assertIsEnabled()
        compose.onNodeWithText("افزودن از کلیپ‌بورد").assertIsNotEnabled()
        screenshot("connected-fa")
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
