package com.example.vpn

import android.app.Notification
import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import com.example.LuxyVpnApplication
import com.example.R
import com.example.data.model.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The always-on start of an idle tunnel must show Connecting, never the stale
 *  pre-boot Disconnected state in the foreground notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlwaysOnNotificationTest {

    @Test
    fun alwaysOnStartShowsConnectingInsteadOfStaleDisconnectedState() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LuxyVpnApplication>()
        app.tunnelManager.disconnectAndAwait("always-on test setup")
        assertEquals(ConnectionState.DISCONNECTED, app.tunnelManager.telemetry.value.connectionState)

        val controller = Robolectric.buildService(LuxyVpnService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(
                Intent(app, LuxyVpnService::class.java).setAction(VpnService.SERVICE_INTERFACE),
                0, 100
            )
            val posted: Notification = Shadows.shadowOf(service).lastForegroundNotification
            assertEquals(
                "Always-on start on an idle tunnel must show Connecting, not the stale Disconnected state",
                service.getString(R.string.details_status_connecting),
                posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            )
        } finally {
            controller.destroy()
        }
        app.tunnelManager.disconnectAndAwait("always-on test cleanup")
    }
}
