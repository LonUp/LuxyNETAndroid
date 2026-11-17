package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.LuxyVpnApplication
import com.example.MainActivity
import com.example.R
import com.example.xraycore.VpnSocketProtector
import com.example.xraycore.BuiltInNetworkDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the TUN device and the native core lifetime.
 *
 * Order matters: the core is started only after `Builder.establish()` returns a descriptor,
 * because the Xray `tun` inbound consumes that fd. On stop the core is stopped first, then
 * the descriptor is closed — closing it first would make the core read from a dead fd.
 */
class LuxyVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunMtu: Int = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var tunAttempt = 0L
    @Volatile private var destroyed = false

    private val tunnelManager: TunnelManager?
        get() = (application as? LuxyVpnApplication)?.tunnelManager

    override fun onCreate() {
        super.onCreate()
        VpnSocketProtector.attach(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val request = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        if (tunnelManager?.isCurrentRequest(request) != true) return@withLock
                        stopTunnel()
                        stopSelf(startId)
                    }
                }
            }
            ACTION_START -> {
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400).coerceIn(1280, 1500)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME) ?: "LuxyVPN"
                val attempt = intent.getLongExtra(EXTRA_ATTEMPT_ID, -1L)
                startForeground(NOTIFICATION_ID, buildNotification(nodeName))
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        val manager = tunnelManager
                        if (destroyed) {
                            if (tunAttempt == 0L || manager?.isCurrentAttempt(tunAttempt) != true) {
                                stopTunnel()
                            }
                            manager?.onServiceDestroyed(attempt)
                            stopSelf(startId)
                            return@withLock
                        }
                        if (manager?.isCurrentAttempt(attempt) != true) {
                            if (tunAttempt == 0L || manager?.isCurrentAttempt(tunAttempt) != true) {
                                stopTunnel()
                                stopSelf(startId)
                            }
                            return@withLock
                        }
                        startTunnel(mtu, attempt)
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        VpnSocketProtector.detach(this)
        serviceScope.launch {
            lifecycleMutex.withLock {
                val attempt = tunAttempt
                stopTunnel()
                tunnelManager?.onServiceDestroyed(attempt)
            }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        tunnelManager?.disconnect("VPN permission revoked by system")
        serviceScope.launch {
            lifecycleMutex.withLock {
                stopTunnel()
                stopSelf()
            }
        }
        super.onRevoke()
    }

    private fun startTunnel(mtu: Int, attempt: Long) {
        val manager = tunnelManager
        if (manager == null) {
            stopTunnel()
            stopSelf()
            return
        }
        val prevAttempt = tunAttempt
        tunAttempt = 0L
        if (prevAttempt > 0L) manager.stopCore(prevAttempt)
        val fd = if (tunInterface != null && tunMtu == mtu) {
            tunInterface
        } else {
            closeTunFd()
            establishTunInterface(mtu)
        }
        if (fd == null) {
            manager.onTunnelFailed(attempt, "Unable to establish the VPN interface")
            return
        }
        tunAttempt = attempt
        manager.attachTun(attempt, fd.fd)
            .onSuccess {
                if (!destroyed && manager.isCurrentAttempt(attempt)) manager.onTunnelEstablished(attempt)
                else {
                    stopTunnel()
                    if (destroyed) manager.onServiceDestroyed(attempt)
                }
            }
            .onFailure {
                closeTunnel()
                manager.onTunnelFailed(attempt, "The VPN core could not start this server")
            }
    }


    private fun establishTunInterface(mtu: Int): ParcelFileDescriptor? {
        try {
            val builder = Builder()
                .setSession("LuxyVPN")
                .setMtu(mtu)
                .addAddress("10.89.0.1", 24)
                .addAddress("fd89::1", 64)
                .addDnsServer(BuiltInNetworkDefaults.VPN_DNS)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
            builder.addDisallowedApplication(packageName)
            return builder.establish().also {
                tunInterface = it
                tunMtu = if (it != null) mtu else 0
            }
        } catch (e: Exception) {
            tunInterface = null
            tunMtu = 0
            return null
        }
    }

    private fun closeTunFd() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        tunMtu = 0
    }

    private fun closeTunnel() {
        val attempt = tunAttempt
        tunAttempt = 0L
        if (attempt > 0L) tunnelManager?.stopCore(attempt)
        closeTunFd()
    }

    private fun stopTunnel() {
        closeTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(nodeName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(nodeName)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_NODE_NAME = "extra_node_name"
        const val EXTRA_ATTEMPT_ID = "extra_attempt_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        private const val CHANNEL_ID = "luxyvpn_tunnel_channel"
        private const val NOTIFICATION_ID = 10808
    }
}
