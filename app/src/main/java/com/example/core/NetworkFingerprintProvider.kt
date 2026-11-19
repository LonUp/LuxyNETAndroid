package com.example.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight network fingerprint used to invalidate stale health results when the device
 * switches between Wi-Fi, cellular, or different network interfaces.
 */
object NetworkFingerprintProvider {
    fun current(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "default"
        val network = cm.activeNetwork ?: return@runCatching "offline"
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
