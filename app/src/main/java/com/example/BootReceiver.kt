package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-connect after boot / package update. The decision stays in `TunnelManager` so the
 * receiver cannot drift from the in-app behaviour: opt-in setting, granted permission and an
 * active profile are all required, and every skip is written to the event log.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext as? LuxyVpnApplication ?: return
        app.tunnelManager.autoConnectOnBoot()
    }
}
