package com.example.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.example.xraycore.BuiltInNetworkDefaults
import com.example.xraycore.SocksProxiedDns
import com.example.xraycore.TunnelConnectivityCheck
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class VpnPosture(
    val hasIpv6TunRoute: Boolean,
    val hasVpnDnsOnly: Boolean
)

/** Reads only platform-observed posture for the active VPN; unavailable evidence stays unknown. */
object VpnPostureInspector {
    private const val TUN_IPV4_ADDRESS = "10.89.0.1"

    @Suppress("DEPRECATION")
    fun inspect(context: Context): VpnPosture? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching null
        val candidates = buildList {
            manager.activeNetwork?.let(::add)
            manager.allNetworks.forEach { net ->
                if (net !in this) add(net)
            }
        }
        val vpnProperties = findLuxyTunLinkProperties(
            candidates.asSequence().mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                manager.getLinkProperties(network)
            }
        ) ?: return@runCatching null

        val dnsServers = vpnProperties.dnsServers
        val dnsOnly = dnsServers.isNotEmpty() &&
            dnsServers.all { it.hostAddress == BuiltInNetworkDefaults.VPN_DNS } &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !vpnProperties.isPrivateDnsActive)
        val ipv4DefaultRoute = vpnProperties.routes.any { route ->
            val destination = route.destination
            destination.prefixLength == 0 &&
                destination.address is Inet4Address &&
                destination.address.isAnyLocalAddress
        }
        val ipv6DefaultRoute = vpnProperties.routes.any { route ->
            val destination = route.destination
            destination.prefixLength == 0 &&
                destination.address is Inet6Address &&
                destination.address.isAnyLocalAddress
        }
        VpnPosture(
            hasIpv6TunRoute = ipv4DefaultRoute && ipv6DefaultRoute,
            hasVpnDnsOnly = dnsOnly
        )
    }.getOrNull()

    internal fun findLuxyTunLinkProperties(
        candidates: Sequence<LinkProperties>
    ): LinkProperties? = candidates.firstOrNull { properties ->
        properties.linkAddresses.any { it.address?.hostAddress == TUN_IPV4_ADDRESS }
    }

}

data class LeakCheckResult(
    val dnsVerified: Boolean,
    val ipv6Routed: Boolean,
    val alwaysOnEnabled: Boolean,
    val lockdownEnabled: Boolean,
    val detail: String
)

/** Reports observed tunnel and platform posture; it does not infer system-wide protection from IP checks. */
class TunnelLeakChecker(
    private val proxy: Proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress("127.0.0.1", TunnelConnectivityCheck.SOCKS_PORT)
    ),
    private val dnsProbeHost: String = "cloudflare.com",
    private val timeoutMs: Long = 3_000L,
    private val dnsProbe: (suspend () -> Boolean)? = null
) {
    suspend fun check(
        hasIpv6TunRoute: Boolean,
        hasVpnDnsOnly: Boolean,
        alwaysOnEnabled: Boolean,
        lockdownEnabled: Boolean
    ): LeakCheckResult = checkObserved(
        posture = VpnPosture(hasIpv6TunRoute, hasVpnDnsOnly),
        alwaysOnEnabled = alwaysOnEnabled,
        lockdownEnabled = lockdownEnabled
    )

    suspend fun checkObserved(
        posture: VpnPosture?,
        alwaysOnEnabled: Boolean,
        lockdownEnabled: Boolean
    ): LeakCheckResult {
        val dnsResolutionSucceeded = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                dnsProbe?.invoke() ?: withContext(Dispatchers.IO) {
                    SocksProxiedDns(
                        proxy,
                        timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    ).lookup(dnsProbeHost).isNotEmpty()
                }
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        val dnsVerified = posture?.hasVpnDnsOnly == true && dnsResolutionSucceeded
        val ipv6Routed = posture?.hasIpv6TunRoute == true
        val details = buildList {
            add(if (dnsVerified) "DNS resolution succeeded with VPN DNS-only posture." else
                "DNS resolution and VPN DNS-only posture were not both verified.")
            when {
                posture == null -> add("VPN platform posture is unavailable.")
                posture.hasIpv6TunRoute -> add("An IPv6 default route is present on the VPN interface.")
                else -> add("No IPv6 default route is present on the VPN interface.")
            }
            add("Route presence does not verify that all IPv6 traffic is tunneled or blocked.")
            add("Local and direct traffic policy is not checked.")
            add(if (alwaysOnEnabled) "Always-on VPN is enabled." else "Always-on VPN is disabled.")
            add(if (lockdownEnabled) "VPN lockdown is enabled." else "VPN lockdown is disabled.")
            add("This check does not assert system-wide leak protection.")
        }
        return LeakCheckResult(
            dnsVerified = dnsVerified,
            ipv6Routed = ipv6Routed,
            alwaysOnEnabled = alwaysOnEnabled,
            lockdownEnabled = lockdownEnabled,
            detail = details.joinToString(" ")
        )
    }
}
