package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.core.NetworkFingerprintProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class NetworkSnapshot(
    val available: Boolean,
    val transport: String,
    val fingerprint: String
)

/** Monitors the default route and debounces the burst of callbacks emitted during handoffs. */
internal class NetworkMonitor(
    private val context: Context,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val snapshotProvider: (() -> NetworkSnapshot)? = null
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshots = MutableStateFlow(NetworkSnapshot(false, "None", "offline"))
    val snapshots: StateFlow<NetworkSnapshot> = _snapshots.asStateFlow()
    fun currentSnapshot(): NetworkSnapshot = _snapshots.value


    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false
    private var started = false
    private var pendingUpdate: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (snapshotProvider != null) {
            publishSnapshot(readSnapshot())
            return
        }
        val manager = connectivityManager ?: return

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleSnapshot()
            override fun onLost(network: Network) = scheduleSnapshot()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleSnapshot()
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = scheduleSnapshot()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val registered = runCatching {
                manager.registerDefaultNetworkCallback(networkCallback)
                true
            }.getOrDefault(false)
            if (registered) callback = networkCallback else registerFallback(manager, networkCallback)
        } else {
            registerFallback(manager, networkCallback)
        }
        publishSnapshot(readSnapshot())
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        pendingUpdate?.cancel()
        pendingUpdate = null
        callback?.let { current -> runCatching { connectivityManager?.unregisterNetworkCallback(current) } }
        callback = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(connectivityReceiver) }
            receiverRegistered = false
        }
        scope.coroutineContext.cancelChildren()
    }

    /** Internal seam for deterministic lifecycle tests and the callback fallback. */
    internal fun publishSnapshot(snapshot: NetworkSnapshot) {
        if (_snapshots.value != snapshot) _snapshots.value = snapshot
    }

    private fun registerFallback(manager: ConnectivityManager, networkCallback: ConnectivityManager.NetworkCallback) {
        val registered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback
            )
            true
        }.getOrDefault(false)
        if (registered) {
            callback = networkCallback
            return
        }
        receiverRegistered = runCatching {
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(connectivityReceiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = scheduleSnapshot()
    }

    @Synchronized
    private fun scheduleSnapshot() {
        if (!started) return
        pendingUpdate?.cancel()
        pendingUpdate = scope.launch {
            delay(debounceMs)
            if (started) publishSnapshot(readSnapshot())
        }
    }

    private fun readSnapshot(): NetworkSnapshot =
        snapshotProvider?.invoke() ?: readPlatformSnapshot()

    private fun readPlatformSnapshot(): NetworkSnapshot = runCatching {
        val manager = connectivityManager ?: return@runCatching NetworkSnapshot(false, "None", "offline")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val info = manager.activeNetworkInfo
            if (info?.isConnected != true) return@runCatching NetworkSnapshot(false, "None", "offline")
            val transport = when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                ConnectivityManager.TYPE_MOBILE -> "Cellular"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                else -> "Other"
            }
            return@runCatching NetworkSnapshot(true, transport, NetworkFingerprintProvider.current(context))
        }
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNetwork
        } else {
            @Suppress("DEPRECATION")
            if (manager.activeNetworkInfo?.isConnected == true) manager.allNetworks.firstOrNull() else null
        } ?: return@runCatching NetworkSnapshot(false, "None", "offline")
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return@runCatching NetworkSnapshot(false, "None", "offline")
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        NetworkSnapshot(true, transport, NetworkFingerprintProvider.current(context))
    }.getOrDefault(NetworkSnapshot(false, "None", "offline"))

    companion object {
        private const val DEFAULT_DEBOUNCE_MS = 250L
    }
}
