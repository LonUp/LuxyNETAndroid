package com.example.xraycore

import com.example.core.DnsPoolQueryResult
import com.example.core.NetworkProbeEngine
import com.example.data.model.StabilitySettings

object MultiResolverDnsEngine {
    suspend fun queryPool(domain: String, settings: StabilitySettings): DnsPoolQueryResult {
        val raw = NetworkProbeEngine.benchmarkMultiResolverPool(domain, settings)
        if (!settings.dnsIntegrityFilterEnabled) return raw
        val rejected = raw.rejectedForPoisoning.toMutableList()
        val filteredEntries = raw.entries.map { entry ->
            val cleanIps = entry.ips.filter { ip ->
                val bogon = NetworkProbeEngine.isBogonOrPrivateIp(ip)
                if (bogon) rejected += "${entry.label}: filtered bogon/poisoned IP $ip"
                !bogon
            }
            entry.copy(ips = cleanIps, success = entry.success && cleanIps.isNotEmpty())
        }
        val winner = filteredEntries.filter { it.success }.minByOrNull { it.rttMs }
        return raw.copy(
            success = winner != null,
            winnerLabel = if (winner != null) "${winner.label} (${settings.dnsPoolStrategy.label})" else "",
            rttMs = winner?.rttMs ?: -1,
            resolvedIps = winner?.ips ?: emptyList(),
            entries = filteredEntries,
            rejectedForPoisoning = rejected
        )
    }
}
