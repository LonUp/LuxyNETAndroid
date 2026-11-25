package com.example.core

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.LuxyVpnApplication
import com.example.core.SubscriptionRefreshScheduler
import com.example.data.local.SubscriptionEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** autoUpdateHours background refresh: alarms per subscription, receiver refresh, reschedule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionRefreshSchedulingTest {
    private lateinit var app: LuxyVpnApplication
    private lateinit var server: MockWebServer
    private val node = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls"

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.repository.getAllProfiles().forEach { app.repository.deleteProfile(it.id) }
            app.repository.getAllSubscriptions().forEach { app.repository.deleteSubscriptionAndNodes(it.id) }
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.repository.getAllSubscriptions().forEach { app.repository.deleteSubscriptionAndNodes(it.id) }
        }
        SubscriptionRefreshScheduler.cancelAll(app, app.repository.getAllSubscriptions())
        server.shutdown()
    }

    private fun alarmCount(): Int =
        Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size

    @Test
    fun scheduleAllArmsOneAlarmPerTimedSubscriptionAndSkipsDisabledOnes() {
        val now = System.currentTimeMillis()
        val timed = SubscriptionEntity(id = "timed", name = "Timed", url = "https://a.example",
            autoUpdateHours = 12, lastUpdatedAt = now - 3_600_000L)
        val off = SubscriptionEntity(id = "off", name = "Off", url = "https://b.example", autoUpdateHours = 0)
        val never = SubscriptionEntity(id = "never", name = "Never", url = "https://c.example", autoUpdateHours = -1)

        SubscriptionRefreshScheduler.scheduleAll(app, listOf(timed, off, never), now)
        assertEquals(1, alarmCount())

        // Re-scheduling must not duplicate alarms for the same subscription.
        SubscriptionRefreshScheduler.scheduleAll(app, listOf(timed, off, never), now)
        assertEquals(1, alarmCount())
    }

    @Test
    fun alarmIsScheduledEvenWhenTheIntervalIsAlreadyOverdue() {
        val now = System.currentTimeMillis()
        val overdue = SubscriptionEntity(id = "due", name = "Due", url = "https://a.example",
            autoUpdateHours = 12, lastUpdatedAt = now - 48 * 3_600_000L)
        SubscriptionRefreshScheduler.scheduleAll(app, listOf(overdue), now)
        val scheduled = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        // Robolectric records trigger times; an overdue alarm fires immediately but is never negative.
        assertTrue(scheduled!!.triggerAtTime >= 0)
    }

    @Test
    fun receiverRefreshesTheDueSubscriptionAndReschedules() = runBlocking {
        val url = server.url("/sub").toString()
        server.enqueue(MockResponse().setBody(node).setHeader("profile-title", "Auto"))
        val subscription = SubscriptionEntity(id = "receiver-test", name = "R", url = url, autoUpdateHours = 6)
        app.repository.saveSubscription(subscription)
        SubscriptionRefreshScheduler.scheduleAll(app, listOf(subscription), System.currentTimeMillis())
        val alarmsBefore = alarmCount()

        val receiver = SubscriptionRefreshReceiver()
        receiver.onReceive(app, SubscriptionRefreshScheduler.refreshIntent(app, subscription.id))
        withTimeout(20_000) {
            while (app.repository.getAllSubscriptions().single().nodeCount == 0) delay(50)
        }

        assertEquals(1, app.repository.getAllSubscriptions().single().nodeCount)
        assertEquals(alarmsBefore, alarmCount())
    }

    @Test
    fun receiverIgnoresUnknownSubscriptions() = runBlocking {
        val receiver = SubscriptionRefreshReceiver()
        receiver.onReceive(app, SubscriptionRefreshScheduler.refreshIntent(app, "missing-id"))
        delay(200)
        assertTrue(app.repository.getAllSubscriptions().isEmpty())
    }

    @Test
    fun bootReceiverReArmsAlarmsForStoredSubscriptions() = runBlocking {
        app.repository.saveSubscription(
            SubscriptionEntity(id = "boot-test", name = "B", url = "https://a.example", autoUpdateHours = 3)
        )
        assertEquals(0, alarmCount())
        val receiver = SubscriptionBootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        withTimeout(20_000) {
            while (alarmCount() == 0) delay(50)
        }
        assertEquals(1, alarmCount())
    }
}
