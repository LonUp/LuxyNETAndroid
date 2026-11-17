package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.import.LinkImporter
import com.example.data.model.ConnectionState
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemePalette
import com.example.i18n.LocaleManager
import com.example.ui.LuxyVpnApp
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LuxyVpnTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LuxyVpnViewModel by viewModels()
    private var permissionPending by mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        permissionPending = false
        if (result.resultCode == RESULT_OK) viewModel.toggleConnect()
        else viewModel.showNotice(getString(R.string.minimal_permission_denied))
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_VIEW -> {
                val uri = incoming.data ?: return
                val scheme = uri.scheme?.lowercase()
                if (scheme !in LinkImporter.supportedSchemes && scheme != "content" && scheme != "file") return
                incoming.data = null
                viewModel.importUri(uri)
            }
            Intent.ACTION_SEND -> {
                val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
                incoming.removeExtra(Intent.EXTRA_TEXT)
                incoming.removeExtra(Intent.EXTRA_STREAM)
                if (text.isNotEmpty()) viewModel.importText(text)
                else if (uri != null) viewModel.importUri(uri)
            }
        }
    }

    private fun requestConnection() {
        if (permissionPending || viewModel.isImporting.value) return
        val state = viewModel.telemetry.value.connectionState
        val idle = state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
        if (!idle || viewModel.isSelectingFastestServer.value) {
            viewModel.toggleConnect()
            return
        }
        if (viewModel.profiles.value.isEmpty()) return
        val permission = viewModel.prepareVpnIntent()
        if (permission == null) viewModel.toggleConnect()
        else {
            permissionPending = true
            vpnPermissionLauncher.launch(permission)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("vpnPermissionPending", permissionPending)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionPending = savedInstanceState?.getBoolean("vpnPermissionPending") ?: false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            LuxyVpnTheme(theme = MinimalTheme) {
                LuxyVpnApp(
                    viewModel = viewModel,
                    onRequestConnect = ::requestConnection,
                    onImportFile = {
                        importFileLauncher.launch(
                            arrayOf("text/*", "application/json", "application/octet-stream")
                        )
                    },
                    permissionPending = permissionPending
                )
            }
        }
    }
}

internal val MinimalTheme = ThemeConfig(
    dark = ThemePalette(background = "121314", card = "1D1E20", accent = "D6A15F"),
    motionEnabled = false
)
