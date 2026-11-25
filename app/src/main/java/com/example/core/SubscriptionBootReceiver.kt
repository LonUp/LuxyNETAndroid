package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Re-arms autoUpdateHours refresh alarms after a reboot (alarms do not survive one). */
class SubscriptionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = if (appContext is com.example.LuxyVpnApplication) {
                    appContext.repository
                } else {
                    LuxyVpnRepository(
                        LuxyVpnDatabase.getInstance(appContext),
                        SettingsDataStore(appContext)
                    )
                }
                SubscriptionRefreshScheduler.scheduleAll(
                    appContext,
                    repository.getAllSubscriptions(),
                    System.currentTimeMillis()
                )
            } catch (_: Exception) {
                // The next app start reschedules anyway.
            } finally {
                result.finish()
            }
        }
    }
}
