package com.example.core

import com.example.xraycore.SocksProxiedDns
import com.example.xraycore.TunnelConnectivityCheck
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val dnsVerified = hasVpnDnsOnly && dnsResolutionSucceeded
        val details = buildList {
            add(if (dnsVerified) "DNS resolution succeeded with VPN DNS-only posture." else
                "DNS resolution and VPN DNS-only posture were not both verified.")
            add(if (hasIpv6TunRoute) "An IPv6 TUN route is present." else "No IPv6 TUN route is present.")
            add(if (alwaysOnEnabled) "Always-on VPN is enabled." else "Always-on VPN is disabled.")
            add(if (lockdownEnabled) "VPN lockdown is enabled." else "VPN lockdown is disabled.")
            add("This check does not assert system-wide leak protection.")
        }
        return LeakCheckResult(
            dnsVerified = dnsVerified,
            ipv6Routed = hasIpv6TunRoute,
            alwaysOnEnabled = alwaysOnEnabled,
            lockdownEnabled = lockdownEnabled,
            detail = details.joinToString(" ")
        )
    }
}
