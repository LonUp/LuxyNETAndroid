package com.example

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import com.example.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick-settings tile: one tap connects to the active node or disconnects, and the tile mirrors
 * the real tunnel state instead of assuming the tap succeeded.
 *
 * Clicking without VPN permission cannot show the system consent dialog from a tile, so the tap
 * opens the app (where the dialog lives) rather than failing silently.
 */
class QuickToggleTileService : TileService() {

    private val app: LuxyVpnApplication get() = application as LuxyVpnApplication
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        render(app.tunnelManager.telemetry.value.connectionState)
        val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        stateJob = listenerScope.launch {
            app.tunnelManager.telemetry.collect { render(it.connectionState) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        if (!app.tunnelManager.isVpnPermissionGranted()) {
            openApp()
            return
        }
        app.tunnelManager.toggleQuickly()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun render(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.state = tileStateFor(state)
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(tileSubtitleRes(state))
        tile.updateTile()
    }
}

/** Connected is the only state that lights the tile; an error is shown as unavailable. */
internal fun tileStateFor(state: ConnectionState): Int = when (state) {
    ConnectionState.CONNECTED -> Tile.STATE_ACTIVE
    ConnectionState.ERROR -> Tile.STATE_UNAVAILABLE
    else -> Tile.STATE_INACTIVE
}

@StringRes
internal fun tileSubtitleRes(state: ConnectionState): Int = when (state) {
    ConnectionState.CONNECTED -> R.string.tile_subtitle_connected
    ConnectionState.CONNECTING -> R.string.tile_subtitle_connecting
    ConnectionState.DISCONNECTING -> R.string.tile_subtitle_disconnecting
    ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> R.string.tile_subtitle_switching
    ConnectionState.ERROR -> R.string.tile_subtitle_error
    ConnectionState.DISCONNECTED -> R.string.tile_subtitle_disconnected
}
