package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.SubscriptionRefreshScheduler
import com.example.data.import.ProfileImporter
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Refreshes one subscription when its autoUpdateHours alarm fires, then reschedules. */
class SubscriptionRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SubscriptionRefreshScheduler.ACTION_REFRESH_DUE) return
        val subscriptionId = intent.getStringExtra(SubscriptionRefreshScheduler.EXTRA_SUBSCRIPTION_ID)
            ?: return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = resolveRepository(appContext)
                val subscription = repository.getAllSubscriptions().firstOrNull { it.id == subscriptionId }
                if (subscription != null && subscription.autoUpdateHours > 0) {
                    ProfileImporter(repository).refreshSubscription(subscriptionId)
                    SubscriptionRefreshScheduler.scheduleAll(
                        appContext,
                        repository.getAllSubscriptions(),
                        System.currentTimeMillis()
                    )
                }
            } catch (_: Exception) {
                // A failed background refresh keeps the stored snapshot; the next alarm retries.
            } finally {
                result.finish()
            }
        }
    }

    internal fun resolveRepository(context: Context): com.example.data.repository.LuxyVpnRepository {
        val application = context as? com.example.LuxyVpnApplication
        if (application != null) return application.repository
        return LuxyVpnRepository(
            LuxyVpnDatabase.getInstance(context),
            SettingsDataStore(context)
        )
    }
}
