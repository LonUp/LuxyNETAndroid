package com.example.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.SubscriptionEntity

/** Schedules per-subscription background refreshes on [SubscriptionEntity.autoUpdateHours].
 *
 *  Uses [AlarmManager.setAndAllowWhileIdle] (inexact, no SCHEDULE_EXACT_ALARM permission):
 *  subscription refresh is a background convenience, not a time-critical contract. Each
 *  subscription gets one alarm at `lastUpdatedAt + autoUpdateHours` (clamped to the
 *  future); the receiver refreshes and reschedules itself. `autoUpdateHours <= 0`
 *  disables auto-refresh for that subscription.
 */
object SubscriptionRefreshScheduler {
    const val ACTION_REFRESH_DUE = "com.example.core.SUBSCRIPTION_REFRESH_DUE"
    const val EXTRA_SUBSCRIPTION_ID = "extra_subscription_id"

    fun scheduleAll(context: Context, subscriptions: List<SubscriptionEntity>, nowMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // One PendingIntent request code per subscription keeps independent alarms.
        subscriptions.forEach { subscription ->
            cancel(context, alarmManager, subscription.id)
            val intervalMs = subscription.autoUpdateHours * 3_600_000L
            if (intervalMs <= 0L) return@forEach
            val dueAt = (subscription.lastUpdatedAt ?: subscription.createdAt) + intervalMs
            val triggerAt = maxOf(dueAt, nowMs + 60_000L)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + (triggerAt - nowMs),
                pendingIntent(context, subscription.id)
            )
        }
    }

    fun cancelAll(context: Context, subscriptions: List<SubscriptionEntity>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        subscriptions.forEach { cancel(context, alarmManager, it.id) }
    }

    fun refreshIntent(context: Context, subscriptionId: String): Intent =
        Intent(context, SubscriptionRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH_DUE
            putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }

    private fun pendingIntent(context: Context, subscriptionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            subscriptionId.hashCode(),
            refreshIntent(context, subscriptionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancel(context: Context, alarmManager: AlarmManager, subscriptionId: String) {
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                subscriptionId.hashCode(),
                refreshIntent(context, subscriptionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
