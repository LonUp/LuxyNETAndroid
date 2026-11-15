package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.LuxyVpnApplication
import com.example.MainActivity
import com.example.R
import com.example.xraycore.VpnSocketProtector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the TUN device and the native core lifetime.
 *
 * Order matters: the core is started only after `Builder.establish()` returns a descriptor,
 * because the Xray `tun` inbound consumes that fd. On stop the core is stopped first, then
 * the descriptor is closed — closing it first would make the core read from a dead fd.
 */
class LuxyVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private val networkCallbackLock = Any()
    private val isNetworkCallbackRegistered = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val tunnelManager: TunnelManager?
        get() = (application as? LuxyVpnApplication)?.tunnelManager

    override fun onCreate() {
        super.onCreate()
        VpnSocketProtector.attach(this)
        createNotificationChannel()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400).coerceIn(1280, 1500)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME) ?: "LuxyVPN Tunnel"
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification(nodeName))
                startTunnel(mtu, configJson)
            }

            ACTION_RELOAD -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                reloadCore(configJson)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        unregisterNetworkMonitor()
        VpnSocketProtector.attach(null)
        super.onDestroy()
    }

    override fun onRevoke() {
        tunnelManager?.disconnect("VPN permission revoked by system")
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    private fun startTunnel(mtu: Int, configJson: String) {
        val manager = tunnelManager
        if (manager == null) {
            stopSelf()
            return
        }
        if (configJson.isBlank()) {
            manager.onTunnelFailed("No compiled core config was supplied")
            stopTunnel()
            stopSelf()
            return
        }
        val fd = establishTunInterface(mtu)
        if (fd == null) {
            manager.onTunnelFailed("Unable to establish the TUN interface")
            stopTunnel()
            stopSelf()
            return
        }
        manager.attachTun(fd.fd)
            .onSuccess { manager.onTunnelEstablished() }
            .onFailure {
                manager.onTunnelFailed(it.message ?: "Core rejected the tunnel config")
                stopTunnel()
                stopSelf()
            }
    }

    private fun reloadCore(configJson: String) {
        val fd = tunInterface ?: return
        if (configJson.isBlank()) return
        val manager = tunnelManager ?: return
        manager.reloadCore(configJson, fd.fd)
    }

    private fun establishTunInterface(mtu: Int): ParcelFileDescriptor? {
        try {
            runCatching { tunInterface?.close() }
            tunInterface = null
            val builder = Builder()
                .setSession("LuxyVPN")
                .setMtu(mtu)
                .addAddress("10.89.0.1", 24)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            return builder.establish().also { tunInterface = it }
        } catch (e: Exception) {
            tunInterface = null
            return null
        }
    }

    private fun stopTunnel() {
        tunnelManager?.stopCore()
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        synchronized(networkCallbackLock) {
            if (isNetworkCallbackRegistered.get()) return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "5G / LTE"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        else -> "Network"
                    }
                    tunnelManager?.onTransportChanged(transport)
                }
            }
            try {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, callback)
                networkCallback = callback
                isNetworkCallbackRegistered.set(true)
            } catch (_: Exception) {
                networkCallback = null
                isNetworkCallbackRegistered.set(false)
            }
        }
    }

    private fun unregisterNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        synchronized(networkCallbackLock) {
            val cb = networkCallback
            if (cb != null && isNetworkCallbackRegistered.compareAndSet(true, false)) {
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Exception) {
                }
            }
            networkCallback = null
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
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText("Connected • $nodeName")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        const val ACTION_RELOAD = "com.example.vpn.RELOAD"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_NODE_NAME = "extra_node_name"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        private const val CHANNEL_ID = "luxyvpn_tunnel_channel"
        private const val NOTIFICATION_ID = 10808
    }
}
