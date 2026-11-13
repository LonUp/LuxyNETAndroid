package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.TunnelTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

object TunnelManager {

    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    private val isForeground = AtomicBoolean(true)

    fun setAppForeground(inForeground: Boolean) {
        isForeground.set(inForeground)
    }

    fun isAppInForeground(): Boolean = isForeground.get()

    fun updateTelemetry(transform: (TunnelTelemetry) -> TunnelTelemetry) {
        _telemetry.update(transform)
    }

    fun prepareVpnIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startTunnel(context: Context, profile: ServerProfileEntity) {
        _telemetry.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                activeNode = profile,
                lastError = null
            )
        }
        val intent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_START
            putExtra(LuxyVpnService.EXTRA_PROFILE_ID, profile.id)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun switchNode(context: Context, profile: ServerProfileEntity) {
        val state = _telemetry.value.connectionState
        if (state == ConnectionState.CONNECTED ||
            state == ConnectionState.CONNECTING ||
            state == ConnectionState.RECONNECTING ||
            state == ConnectionState.FAILOVER
        ) {
            val intent = Intent(context, LuxyVpnService::class.java).apply {
                action = LuxyVpnService.ACTION_SWITCH_NODE
                putExtra(LuxyVpnService.EXTRA_PROFILE_ID, profile.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stopTunnel(context: Context) {
        val intent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun reprobeOrResetAdaptiveStage(context: Context) {
        val state = _telemetry.value.connectionState
        if (state == ConnectionState.CONNECTED ||
            state == ConnectionState.RECONNECTING ||
            state == ConnectionState.FAILOVER
        ) {
            val intent = Intent(context, LuxyVpnService::class.java).apply {
                action = LuxyVpnService.ACTION_REPROBE_ADAPTIVE
            }
            ContextCompat.startForegroundService(context, intent)
        } else {
            _telemetry.update {
                it.copy(
                    isAdaptingRoute = false,
                    adaptiveStage = 0,
                    adaptiveStageLabel = "Stage 0 • Baseline",
                    lastError = null
                )
            }
        }
    }
}
