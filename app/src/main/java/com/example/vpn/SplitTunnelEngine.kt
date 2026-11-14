package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.example.core.GeoRulesEngine
import com.example.core.SplitTunnelDecision
import com.example.data.local.RoutingRuleEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.StabilitySettings
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class SplitTunnelEngine(
    private val context: Context? = null
) {
    // Maps synthetic FakeDNS 198.18.x.x IPv4 back to original domain so rules evaluate pre-FakeDNS
    private val fakeDnsDomainMap = ConcurrentHashMap<String, String>()

    fun registerFakeDnsMapping(fakeIp: String, domain: String) {
        if (GeoRulesEngine.isFakeDnsIpv4(fakeIp) && domain.isNotBlank()) {
            fakeDnsDomainMap[fakeIp.trim()] = domain.trim().lowercase()
        }
    }

    fun resolveOriginalDomain(domain: String?, destIp: String?): String? {
        val cleanDomain = domain?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanDomain != null) return cleanDomain
        val cleanIp = destIp?.trim().orEmpty()
        if (GeoRulesEngine.isFakeDnsIpv4(cleanIp)) {
            return fakeDnsDomainMap[cleanIp]
        }
        return null
    }

    /**
     * Evaluates GeoSite/GeoIP/Domain/CIDR routing rules against original destinations
     * strictly BEFORE FakeDNS rewrites.
     */
    fun evaluateDestination(
        domain: String?,
        destIp: String?,
        routingMode: RoutingMode,
        rules: List<RoutingRuleEntity>
    ): SplitTunnelDecision {
        val originalDomain = resolveOriginalDomain(domain, destIp)
        return GeoRulesEngine.evaluateSplitTunnelDestination(
            domain = originalDomain,
            destIp = destIp,
            routingMode = routingMode,
            rules = rules
        )
    }

    fun parsePackageList(rawPackages: String): Set<String> {
        return rawPackages
            .split(',', ';', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun isSocketUidAllowed(
        protocol: Int,
        src: InetSocketAddress?,
        dst: InetSocketAddress?,
        settings: StabilitySettings
    ): Boolean {
        if (settings.appSplitTunnelMode == AppSplitTunnelMode.DISABLED) return true
        val ctx = context ?: return !settings.strictUidIsolationEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || src == null || dst == null) {
            return !settings.strictUidIsolationEnabled
        }
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return !settings.strictUidIsolationEnabled
            val uid = cm.getConnectionOwnerUid(protocol, src, dst)
            if (uid <= 0) {
                return !settings.strictUidIsolationEnabled
            }
            val packages = ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
            val configured = parsePackageList(settings.splitTunnelPackages)
            when (settings.appSplitTunnelMode) {
                AppSplitTunnelMode.DISABLED -> true
                AppSplitTunnelMode.ALLOWLIST -> packages.any { it in configured }
                AppSplitTunnelMode.BLOCKLIST -> packages.none { it in configured }
            }
        } catch (e: SecurityException) {
            Log.w("SplitTunnelEngine", "UID isolation check unavailable: ${e.message}")
            !settings.strictUidIsolationEnabled
        } catch (_: Throwable) {
            !settings.strictUidIsolationEnabled
        }
    }
}
