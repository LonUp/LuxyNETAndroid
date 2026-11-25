package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.import.LinkImporter
import com.example.data.model.ConnectionState
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemePalette
import com.example.i18n.LocaleManager
import com.example.ui.LuxyVpnApp
import com.example.ui.LuxyVpnViewModel
import com.example.ui.RestoreBackupDialog
import com.example.ui.theme.LuxyVpnTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LuxyVpnViewModel by viewModels()
    private var permissionPending by mutableStateOf(false)
    private var pendingRestoreUri by mutableStateOf<Uri?>(null)
    private companion object {
        const val KEY_DETAILS_EXPANDED = "detailsExpanded"
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val passphrase = viewModel.pendingExportPassphrase
        viewModel.pendingExportPassphrase = null
        if (uri != null) {
            if (passphrase != null) viewModel.exportBackupToUri(uri, passphrase)
            else viewModel.showNotice("Could not export backup")
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingRestoreUri = uri
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        permissionPending = false
        if (result.resultCode == RESULT_OK) requestNotificationPermissionThenConnect()
        else viewModel.showNotice(getString(R.string.minimal_permission_denied))
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Connect proceeds regardless: without POST_NOTIFICATIONS the service still
        // runs with startForeground; only its status notification stays silent (API 33+).
        viewModel.toggleConnect()
    }

    private fun requestNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.toggleConnect()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }

    override fun onStart() {
        super.onStart()
        viewModel.purgeExpiredTrashIfNeeded()
        viewModel.setAppForegrounded(true)
    }
    override fun onStop() {
        viewModel.setAppForegrounded(false)
        super.onStop()
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
                if (scheme !in LinkImporter.supportedSchemes && scheme != "content") return
                incoming.data = null
                viewModel.importUri(uri)
            }
            Intent.ACTION_SEND -> {
                val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
                }
                incoming.removeExtra(Intent.EXTRA_TEXT)
                incoming.removeExtra(Intent.EXTRA_STREAM)
                if (text.isNotEmpty()) viewModel.importText(text)
                else if (uri != null && uri.scheme.equals("content", ignoreCase = true)) viewModel.importUri(uri)
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
        if (viewModel.connectionSources.value.none { it.isEnabledForConnection }) {
            viewModel.showNotice("All connection sources are disabled. Enable at least one source in Subscriptions")
            return
        }
        if (viewModel.eligibleProfiles.value.isEmpty()) {
            viewModel.toggleConnect()
            return
        }
        if (!viewModel.hasEligibleServersForSelectedCountry()) {
            viewModel.showNotice("No servers available for the selected country")
            return
        }
        val permission = viewModel.prepareVpnIntent()
        if (permission == null) viewModel.toggleConnect()
        else {
            permissionPending = true
            vpnPermissionLauncher.launch(permission)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("vpnPermissionPending", permissionPending)
        outState.putString("pendingRestoreUri", pendingRestoreUri?.toString())
        outState.putBoolean(KEY_DETAILS_EXPANDED, viewModel.detailsExpanded.value)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            permissionPending = state.getBoolean("vpnPermissionPending")
            pendingRestoreUri = state.getString("pendingRestoreUri")?.let(Uri::parse)
            if (state.containsKey(KEY_DETAILS_EXPANDED)) {
                viewModel.setDetailsExpanded(state.getBoolean(KEY_DETAILS_EXPANDED))
            }
        } ?: run {
            permissionPending = false
            pendingRestoreUri = null
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            val language by viewModel.appLanguage.collectAsStateWithLifecycle()
            LaunchedEffect(language) {
                if (resources.configuration.locales[0].language != language) recreate()
            }

            LuxyVpnTheme(theme = MinimalTheme) {
                LuxyVpnApp(
                    viewModel = viewModel,
                    onRequestConnect = ::requestConnection,
                    onImportFile = {
                        importFileLauncher.launch(
                            arrayOf("text/*", "application/json", "application/octet-stream")
                        )
                    },
                    onExportBackup = { passphrase ->
                        viewModel.pendingExportPassphrase = passphrase
                        exportBackupLauncher.launch("luxynet-backup.json")
                    },
                    onRequestRestoreBackup = {
                        restoreBackupLauncher.launch(
                            arrayOf("application/json", "text/*", "application/octet-stream")
                        )
                    },
                    permissionPending = permissionPending
                )
                pendingRestoreUri?.let { uri ->
                    RestoreBackupDialog(
                        onConfirm = { passphrase ->
                            viewModel.restoreBackupFromUri(uri, passphrase)
                            pendingRestoreUri = null
                        },
                        onDismiss = { pendingRestoreUri = null }
                    )
                }
            }
        }
    }
}

internal val MinimalTheme = ThemeConfig(
    dark = ThemePalette(background = "0B0B0D", card = "161618", accent = "E2B46B"),
    motionEnabled = false
)
