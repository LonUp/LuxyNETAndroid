package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.import.LinkImporter
import com.example.i18n.LocaleManager
import com.example.ui.LuxyVpnApp
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LuxyVpnTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: LuxyVpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.toggleConnect()
        } else {
            viewModel.showNotice("VPN permission declined")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Deep links (`vless://…`, `v2rayng://…`, …) and "share to LuxyVPN" land here. The intent is
     * consumed after dispatch so an activity recreation cannot import the same link twice.
     */
    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_VIEW -> {
                val uri: Uri = incoming.data ?: return
                val scheme = uri.scheme?.lowercase()
                if (scheme == null || scheme !in LinkImporter.supportedSchemes) return
                incoming.data = null
                lifecycleScope.launch { viewModel.importDeepLinkUriAsync(uri) }
            }
            Intent.ACTION_SEND -> {
                val shared = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (shared.isEmpty()) return
                incoming.removeExtra(Intent.EXTRA_TEXT)
                lifecycleScope.launch { viewModel.importBatchTextAsync(shared) }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()

            LaunchedEffect(settings.language) {
                if (LocaleManager.currentTag(this@MainActivity) != settings.language) {
                    LocaleManager.setTag(this@MainActivity, settings.language)
                    recreate()
                }
            }
            var isAppUnlocked by remember(settings.appLockEnabled) {
                mutableStateOf(!settings.appLockEnabled)
            }

            LaunchedEffect(settings.flagSecureEnabled) {
                if (settings.flagSecureEnabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(settings.excludeFromRecents) {
                val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                am?.appTasks?.forEach { task ->
                    task.setExcludeFromRecents(settings.excludeFromRecents)
                }
            }

            LuxyVpnTheme(theme = settings.theme) {
                val colors = LocalLuxyColors.current
                if (settings.appLockEnabled && !isAppUnlocked) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = colors.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "LuxyVPN Locked",
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap below to unlock your encrypted proxy workspace.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { isAppUnlocked = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Unlock LuxyVPN")
                            }
                        }
                    }
                } else {
                    LuxyVpnApp(
                        viewModel = viewModel,
                        onRequestConnect = {
                            val prepareIntent: Intent? = viewModel.prepareVpnIntent()
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                viewModel.toggleConnect()
                            }
                        }
                    )
                }
            }
        }
    }
}
