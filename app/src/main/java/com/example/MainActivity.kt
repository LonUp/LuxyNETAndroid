package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.model.ColorPalette
import com.example.data.model.ThemeMode
import com.example.ui.LuxyVpnApp
import com.example.ui.theme.LuxyVpnTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private var isAppLockEnabled by mutableStateOf(false)
    private var isUnlocked by mutableStateOf(true)
    private var activeThemeMode by mutableStateOf(ThemeMode.DARK)
    private var activeColorPalette by mutableStateOf(ColorPalette.LUXY_OBSIDIAN_GOLD)
    private var promptInFlight = false
    private var wasBackgrounded = false

    private data class WindowSecurityAndTheme(
        val flagSecure: Boolean,
        val excludeFromRecents: Boolean,
        val appLock: Boolean,
        val themeMode: ThemeMode,
        val colorPalette: ColorPalette
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as LuxyVpnApplication).repository
        lifecycleScope.launch {
            repository.settingsFlow
                .map {
                    WindowSecurityAndTheme(
                        flagSecure = it.flagSecureEnabled,
                        excludeFromRecents = it.excludeFromRecents,
                        appLock = it.appLockEnabled,
                        themeMode = it.themeMode,
                        colorPalette = it.colorPalette
                    )
                }
                .distinctUntilChanged()
                .collect { cfg ->
                    activeThemeMode = cfg.themeMode
                    activeColorPalette = cfg.colorPalette
                    applyScreenSecurity(cfg.flagSecure, cfg.excludeFromRecents)
                    val lockNewlyEnabled = cfg.appLock && !isAppLockEnabled
                    isAppLockEnabled = cfg.appLock
                    if (!cfg.appLock) {
                        isUnlocked = true
                    } else if (lockNewlyEnabled && savedInstanceState == null && !wasBackgrounded) {
                        isUnlocked = false
                        launchBiometricUnlock()
                    }
                }
        }

        setContent {
            if (isAppLockEnabled && !isUnlocked) {
                LuxyVpnTheme(
                    themeMode = activeThemeMode,
                    colorPalette = activeColorPalette
                ) {
                    AppLockOverlay(
                        canAuthenticate = canUseBiometricOrCredential(),
                        onUnlockClick = { launchBiometricUnlock() },
                        onBypassWhenUnenrolled = { isUnlocked = true }
                    )
                }
            } else {
                LuxyVpnApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.vpn.TunnelManager.setAppForeground(true)
        if (wasBackgrounded && isAppLockEnabled) {
            isUnlocked = false
            launchBiometricUnlock()
        }
        wasBackgrounded = false
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            com.example.vpn.TunnelManager.setAppForeground(false)
            wasBackgrounded = true
            if (isAppLockEnabled) {
                isUnlocked = false
            }
        }
    }

    private fun applyScreenSecurity(flagSecure: Boolean, excludeFromRecents: Boolean) {
        if (flagSecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach { task ->
            try {
                task.setExcludeFromRecents(excludeFromRecents)
            } catch (_: Exception) {
            }
        }
    }

    private fun canUseBiometricOrCredential(): Boolean {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun launchBiometricUnlock() {
        if (promptInFlight) return
        if (!canUseBiometricOrCredential()) {
            return
        }
        promptInFlight = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    promptInFlight = false
                    isUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    promptInFlight = false
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock LuxyVPN")
            .setSubtitle("Authenticate to access active VPN tunnels and nodes")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            promptInFlight = false
        }
    }
}

@Composable
private fun AppLockOverlay(
    canAuthenticate: Boolean,
    onUnlockClick: () -> Unit,
    onBypassWhenUnenrolled: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Canvas(modifier = Modifier.size(64.dp)) {
                    val w = size.width
                    val h = size.height
                    val shield = Path().apply {
                        moveTo(w * 0.50f, h * 0.08f)
                        lineTo(w * 0.88f, h * 0.26f)
                        lineTo(w * 0.82f, h * 0.66f)
                        lineTo(w * 0.50f, h * 0.92f)
                        lineTo(w * 0.18f, h * 0.66f)
                        lineTo(w * 0.12f, h * 0.26f)
                        close()
                    }
                    drawPath(
                        path = shield,
                        color = primary,
                        style = Stroke(width = w * 0.06f, join = StrokeJoin.Round)
                    )
                    val apex = Path().apply {
                        moveTo(w * 0.50f, h * 0.26f)
                        lineTo(w * 0.61f, h * 0.39f)
                        lineTo(w * 0.50f, h * 0.52f)
                        lineTo(w * 0.39f, h * 0.39f)
                        close()
                    }
                    drawPath(path = apex, color = secondary)
                    drawLine(
                        color = primary,
                        start = Offset(w * 0.34f, h * 0.45f),
                        end = Offset(w * 0.50f, h * 0.72f),
                        strokeWidth = w * 0.07f
                    )
                    drawLine(
                        color = primary,
                        start = Offset(w * 0.66f, h * 0.45f),
                        end = Offset(w * 0.50f, h * 0.72f),
                        strokeWidth = w * 0.07f
                    )
                }
                Text(
                    text = "LuxyVPN Protected",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Biometric or device credential verification is required before displaying nodes or telemetry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (canAuthenticate) {
                    Button(
                        onClick = onUnlockClick,
                        modifier = Modifier.testTag("unlock_app_lock_button")
                    ) {
                        Icon(
                            Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("  Unlock with Biometrics / PIN")
                    }
                } else {
                    TextButton(
                        onClick = onBypassWhenUnenrolled,
                        modifier = Modifier.testTag("unlock_app_lock_button")
                    ) {
                        Text("Continue (No device lock configured)")
                    }
                }
            }
        }
    }
}
