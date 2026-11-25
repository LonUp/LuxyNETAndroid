package com.example.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
/**
 * Lightweight network fingerprint used to invalidate stale health results when the device
 * switches between Wi-Fi, cellular, or different network interfaces.
 */
object NetworkFingerprintProvider {
    internal fun activePhysicalNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork ?: return null
        val activeCaps = cm.getNetworkCapabilities(active)
        if (activeCaps == null || !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return active
        }
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.asSequence()
            .mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                net to caps
            }
            .toList()
        val validated = candidates.firstOrNull { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first
        return validated ?: candidates.firstOrNull()?.first ?: active
    }

    fun current(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "default"
        val network = activePhysicalNetwork(cm) ?: return@runCatching "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching "default"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val link = cm.getLinkProperties(network)
        val iface = link?.interfaceName?.takeIf { it.isNotBlank() && !it.startsWith("tun") }
        val dnsHash = link?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }?.hashCode() ?: 0
        if (iface != null) "$transport:$iface:$dnsHash" else "$transport:$dnsHash"
    }.getOrDefault("default")
}
