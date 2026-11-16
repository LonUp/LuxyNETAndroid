package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.annotation.StringRes
import com.example.data.model.ConnectionState

/**
 * Home-screen widget: the connection state and a tap-through to the app. It has no controls of
 * its own — a widget cannot request VPN permission, so a one-tap connect there would be a lie.
 */
class LuxyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = (context.applicationContext as? LuxyVpnApplication)
            ?.tunnelManager?.telemetry?.value?.connectionState
            ?: ConnectionState.DISCONNECTED
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id, state) }
    }

    companion object {
        /** Pushes the state into every placed widget; a no-op when none exists. */
        fun updateAll(context: Context, state: ConnectionState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, LuxyWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> render(context, manager, id, state) }
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            state: ConnectionState
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_luxy)
            val color = widgetStatusColor(state)
            views.setTextViewText(R.id.widget_status, context.getString(widgetLabelRes(state)))
            views.setTextColor(R.id.widget_status, color)
            views.setInt(R.id.widget_dot, "setBackgroundColor", color)
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

@StringRes
internal fun widgetLabelRes(state: ConnectionState): Int = when (state) {
    ConnectionState.CONNECTED -> R.string.orb_connected
    ConnectionState.CONNECTING -> R.string.orb_connecting
    ConnectionState.DISCONNECTING -> R.string.orb_disconnecting
    ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> R.string.orb_reconnecting
    ConnectionState.ERROR -> R.string.orb_error
    ConnectionState.DISCONNECTED -> R.string.widget_status_idle
}

/** Fixed status palette: widgets render outside Compose, so they use the dark-theme tokens. */
internal fun widgetStatusColor(state: ConnectionState): Int = when (state) {
    ConnectionState.CONNECTED -> 0xFF3DD68C.toInt()
    ConnectionState.CONNECTING, ConnectionState.DISCONNECTING, ConnectionState.RECONNECTING, ConnectionState.FAILOVER ->
        0xFFF5C542.toInt()
    ConnectionState.ERROR -> 0xFFF2555A.toInt()
    ConnectionState.DISCONNECTED -> 0xFFA79C8B.toInt()
}
