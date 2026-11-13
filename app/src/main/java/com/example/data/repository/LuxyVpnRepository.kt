package com.example.data.repository

import com.example.core.BatchParseReport
import com.example.core.EncryptedConfigVault
import com.example.core.NetworkProbeEngine
import com.example.core.ProbeResult
import com.example.core.ProxyUriParser
import com.example.core.SubscriptionFetchResult
import com.example.core.SubscriptionFetcher
import com.example.core.VaultImportResult
import com.example.data.local.NetworkStrategyDao
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.RoutingRuleDao
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileDao
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.StabilityEventDao
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionDao
import com.example.data.local.SubscriptionEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.ColorPalette
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.RoutingMode
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class LuxyVpnRepository(
    private val serverDao: ServerProfileDao,
    private val subscriptionDao: SubscriptionDao,
    private val ruleDao: RoutingRuleDao,
    private val eventDao: StabilityEventDao,
    private val networkStrategyDao: NetworkStrategyDao,
    private val settingsStore: SettingsDataStore,
    private val probeEngine: NetworkProbeEngine,
    private val subscriptionFetcher: SubscriptionFetcher
) {
    val serversFlow: Flow<List<ServerProfileEntity>> = serverDao.observeAll()
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()
    val routingRulesFlow: Flow<List<RoutingRuleEntity>> = ruleDao.observeAll()
    val stabilityEventsFlow: Flow<List<StabilityEventEntity>> = eventDao.observeRecent()
    val networkStrategiesFlow: Flow<List<NetworkStrategyEntity>> = networkStrategyDao.observeAll()
    val settingsFlow: Flow<StabilitySettings> = settingsStore.settingsFlow

    suspend fun getServerById(id: String): ServerProfileEntity? = serverDao.getById(id)

    suspend fun getAllServersOnce(): List<ServerProfileEntity> = serverDao.getAllOnce()

    suspend fun saveServer(profile: ServerProfileEntity) {
        serverDao.upsert(profile)
    }

    suspend fun importFromText(rawText: String, groupName: String = "Imported"): Int =
        importFromTextWithReport(rawText, groupName).nodes.size

    suspend fun importFromTextWithReport(
        rawText: String,
        groupName: String = "Imported",
        applyTopN: Boolean = true
    ): BatchParseReport {
        val topN = if (applyTopN) settingsFlow.first().subscriptionTopNLimit else 0
        val report = ProxyUriParser.parseBatchWithReport(
            rawInput = rawText,
            groupName = groupName,
            topN = topN
        )
        if (report.nodes.isNotEmpty()) {
            serverDao.upsertAll(report.nodes)
            val details = buildList {
                add("Imported ${report.nodes.size} node(s) into $groupName")
                if (report.trimmedCount > 0) add("lazy-trimmed ${report.trimmedCount}")
                if (report.strippedDirectives.isNotEmpty()) {
                    add("stripped ${report.strippedDirectives.size} unsafe directive(s)")
                }
            }.joinToString(" • ")
            logEvent(
                level = if (report.strippedDirectives.isNotEmpty()) LogLevel.RECOVERY else LogLevel.INFO,
                category = LogCategory.TUNNEL,
                message = details
            )
        }
        return report
    }

    suspend fun importVaultPayload(
        rawPayload: String,
        passphrase: String?,
        privateKeyBase64: String?
    ): VaultImportResult {
        val res = EncryptedConfigVault.importVault(
            rawPayload = rawPayload,
            passphrase = passphrase,
            recipientPrivateKeyBase64 = privateKeyBase64
        )
        if (res is VaultImportResult.Success) {
            serverDao.upsertAll(res.nodes)
            logEvent(
                level = LogLevel.INFO,
                category = LogCategory.ANTI_DPI,
                message = "Unlocked ${res.nodes.size} locked vault profile(s) (${res.scheme})"
            )
        }
        return res
    }

    suspend fun deleteServer(id: String) {
        serverDao.deleteById(id)
    }

    suspend fun togglePin(id: String, pinned: Boolean) {
        serverDao.setPinned(id, pinned)
    }

    suspend fun setActiveProfileId(id: String?) {
        settingsStore.setActiveProfileId(id)
    }

    suspend fun probeServer(profile: ServerProfileEntity): ProbeResult {
        val result = probeEngine.measureNodePing(profile, samples = 2)
        serverDao.updateLatency(
            id = profile.id,
            latencyMs = result.rttMs,
            jitterMs = result.jitterMs,
            testedAt = System.currentTimeMillis()
        )
        return result
    }

    suspend fun probeSingleTcp(host: String, port: Int, timeoutMs: Int = 2500): ProbeResult =
        probeEngine.measureSingleTcpRtt(host, port, timeoutMs)

    suspend fun testDnsLatency(dnsServer: String): ProbeResult =
        probeEngine.testDnsLatency(dnsServer)

    suspend fun probeAllServers(
        servers: List<ServerProfileEntity>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) {
        var completed = 0
        val total = servers.size
        probeEngine.batchMeasure(servers) { profile, result ->
            serverDao.updateLatency(
                id = profile.id,
                latencyMs = result.rttMs,
                jitterMs = result.jitterMs,
                testedAt = System.currentTimeMillis()
            )
            completed++
            onProgress(completed, total)
        }
        logEvent(
            level = LogLevel.INFO,
            category = LogCategory.PROBE,
            message = "Completed batch latency check across $total node(s)"
        )
    }

    suspend fun addAndSyncSubscription(name: String, url: String, autoUpdateHours: Int): SubscriptionFetchResult {
        val sub = SubscriptionEntity(
            name = name.trim().ifBlank { "Subscription" },
            url = url.trim(),
            autoUpdateHours = autoUpdateHours
        )
        subscriptionDao.upsert(sub)
        return syncSubscription(sub.id)
    }

    suspend fun syncSubscription(subscriptionId: String): SubscriptionFetchResult {
        val sub = subscriptionDao.getById(subscriptionId)
            ?: return SubscriptionFetchResult.Error("Subscription not found")
        val settings = settingsFlow.first()
        val result = subscriptionFetcher.fetch(
            url = sub.url,
            subscriptionId = sub.id,
            groupName = sub.name,
            topN = settings.subscriptionTopNLimit,
            userAgent = settings.subscriptionUserAgent
        )
        val now = System.currentTimeMillis()
        when (result) {
            is SubscriptionFetchResult.Success -> {
                serverDao.replaceSubscriptionNodes(sub.id, result.nodes)
                val statusLabel = if (result.trimmedCount > 0) {
                    "Synced ${result.nodes.size}/${result.totalParsed} (Top-N)"
                } else {
                    "Synced (${result.nodes.size} nodes)"
                }
                subscriptionDao.upsert(
                    sub.copy(
                        lastUpdatedAt = now,
                        nodeCount = result.nodes.size,
                        lastStatus = statusLabel,
                        lastError = null
                    )
                )
                val extra = buildList {
                    if (result.trimmedCount > 0) add("kept top ${result.nodes.size} of ${result.totalParsed}")
                    if (result.strippedDirectives.isNotEmpty()) {
                        add("sanitized ${result.strippedDirectives.size} directive(s)")
                    }
                }.joinToString(", ")
                val suffix = if (extra.isNotEmpty()) " ($extra)" else ""
                logEvent(
                    level = LogLevel.INFO,
                    category = LogCategory.TUNNEL,
                    message = "Synced '${sub.name}': ${result.nodes.size} nodes$suffix"
                )
            }
            is SubscriptionFetchResult.Error -> {
                subscriptionDao.upsert(
                    sub.copy(
                        lastUpdatedAt = now,
                        lastStatus = "Sync failed",
                        lastError = result.message
                    )
                )
                logEvent(
                    level = LogLevel.WARN,
                    category = LogCategory.TUNNEL,
                    message = "Subscription '${sub.name}' error: ${result.message}"
                )
            }
        }
        return result
    }

    suspend fun deleteSubscription(id: String) {
        serverDao.deleteBySubscriptionId(id)
        subscriptionDao.deleteById(id)
    }

    suspend fun saveRoutingRule(rule: RoutingRuleEntity) {
        ruleDao.upsert(rule)
    }

    suspend fun setRoutingRuleEnabled(id: String, enabled: Boolean) {
        ruleDao.setEnabled(id, enabled)
    }

    suspend fun deleteRoutingRule(id: String) {
        ruleDao.deleteById(id)
    }

    suspend fun setThemeMode(mode: ThemeMode) = settingsStore.setThemeMode(mode)
    suspend fun setColorPalette(palette: ColorPalette) = settingsStore.setColorPalette(palette)
    suspend fun setRoutingMode(mode: RoutingMode) = settingsStore.setRoutingMode(mode)
    suspend fun setAutoConnectOnLaunch(enabled: Boolean) = settingsStore.setAutoConnectOnLaunch(enabled)
    suspend fun setAutoReconnect(enabled: Boolean) = settingsStore.setAutoReconnect(enabled)
    suspend fun setSmartFailover(enabled: Boolean) = settingsStore.setSmartFailover(enabled)
    suspend fun setProbeIntervalSec(seconds: Int) = settingsStore.setProbeIntervalSec(seconds)
    suspend fun setMuxEnabled(enabled: Boolean) = settingsStore.setMuxEnabled(enabled)
    suspend fun setMuxConcurrency(concurrency: Int) = settingsStore.setMuxConcurrency(concurrency)
    suspend fun setTlsFragmentEnabled(enabled: Boolean) = settingsStore.setTlsFragmentEnabled(enabled)
    suspend fun setTcpFastOpen(enabled: Boolean) = settingsStore.setTcpFastOpen(enabled)
    suspend fun setDnsConfig(primary: String, fallback: String, doh: Boolean, dohUrl: String = "https://cloudflare-dns.com/dns-query") =
        settingsStore.setDnsConfig(primary, fallback, doh, dohUrl)
    suspend fun setMtu(mtu: Int) = settingsStore.setMtu(mtu)

    suspend fun applyAntiCensorshipPreset(preset: com.example.data.model.AntiCensorshipPreset) {
        settingsStore.applyAntiCensorshipPreset(preset)
        logEvent(
            level = LogLevel.INFO,
            category = LogCategory.ANTI_DPI,
            message = "Applied LuxyVpnCore preset: ${preset.label}"
        )
    }

    suspend fun setFragmentParams(
        packets: String,
        length: String,
        interval: String,
        host: String? = null,
        maxSplit: String? = null
    ) = settingsStore.setFragmentParams(packets, length, interval, host, maxSplit)

    suspend fun setNoiseConfig(
        enabled: Boolean,
        packetSize: String = "10-20",
        delayMs: String = "10-20",
        noiseType: String? = null,
        applyTo: String? = null
    ) = settingsStore.setNoiseConfig(enabled, packetSize, delayMs, noiseType, applyTo)

    suspend fun setUtlsFingerprint(fp: String) = settingsStore.setUtlsFingerprint(fp)
    suspend fun setFakeDnsEnabled(enabled: Boolean) = settingsStore.setFakeDnsEnabled(enabled)
    suspend fun setSniffingEnabled(enabled: Boolean) = settingsStore.setSniffingEnabled(enabled)
    suspend fun setBlockQuicUdp(enabled: Boolean) = settingsStore.setBlockQuicUdp(enabled)
    suspend fun setXhttpConfig(mode: String, paddingBytes: String) =
        settingsStore.setXhttpConfig(mode, paddingBytes)

    suspend fun setCipherSuites(cipherSuites: String) = settingsStore.setCipherSuites(cipherSuites)
    suspend fun setFinalMaskType(finalMaskType: String) = settingsStore.setFinalMaskType(finalMaskType)
    suspend fun setEchConfig(enabled: Boolean, configList: String? = null, forceQuery: String? = null) =
        settingsStore.setEchConfig(enabled, configList, forceQuery)
    suspend fun setHappyEyeballsEnabled(enabled: Boolean, tryDelayMs: Int = 250) =
        settingsStore.setHappyEyeballsEnabled(enabled, tryDelayMs)
    suspend fun setAllowUnencryptedVless(enabled: Boolean) =
        settingsStore.setAllowUnencryptedVless(enabled)
    suspend fun setSubscriptionTopNLimit(limit: Int) =
        settingsStore.setSubscriptionTopNLimit(limit)

    suspend fun setWarpEndpoint(endpoint: String) =
        settingsStore.setWarpEndpoint(endpoint)

    suspend fun setWarpConfig(
        endpoint: String,
        privateKey: String,
        publicKey: String,
        reserved: String,
        localAddress: String
    ) = settingsStore.setWarpConfig(endpoint, privateKey, publicKey, reserved, localAddress)

    suspend fun setAmneziaWgParams(
        jc: Int,
        jmin: Int,
        jmax: Int,
        s1: Int,
        s2: Int,
        h1: Long,
        h2: Long,
        h3: Long,
        h4: Long
    ) = settingsStore.setAmneziaWgParams(jc, jmin, jmax, s1, s2, h1, h2, h3, h4)

    suspend fun setWarpNoisePreset(preset: String) =
        settingsStore.setWarpNoisePreset(preset)

    suspend fun setChainingConfig(mode: ChainingMode, relayNodeId: String?) =
        settingsStore.setChainingConfig(mode, relayNodeId)

    suspend fun setAdvancedTlsOptions(
        mixedCaseSni: Boolean,
        tlsPaddingEnabled: Boolean,
        realityFragmentEnabled: Boolean
    ) = settingsStore.setAdvancedTlsOptions(mixedCaseSni, tlsPaddingEnabled, realityFragmentEnabled)

    suspend fun setSocketMaskAndTimeouts(
        finalMaskFamily: String,
        tcpTimeoutMs: Int,
        tlsTimeoutMs: Int
    ) = settingsStore.setSocketMaskAndTimeouts(finalMaskFamily, tcpTimeoutMs, tlsTimeoutMs)

    suspend fun setDnsPoolConfig(
        strategy: DnsPoolStrategy,
        integrityFilterEnabled: Boolean,
        dotServer: String,
        dotEnabled: Boolean,
        tcpEnabled: Boolean,
        udpEnabled: Boolean
    ) = settingsStore.setDnsPoolConfig(strategy, integrityFilterEnabled, dotServer, dotEnabled, tcpEnabled, udpEnabled)

    suspend fun setSplitTunnelConfig(
        mode: AppSplitTunnelMode,
        packagesCsv: String,
        strictUidIsolation: Boolean
    ) = settingsStore.setSplitTunnelConfig(mode, packagesCsv, strictUidIsolation)

    suspend fun setPrivacyOpSecConfig(
        flagSecureEnabled: Boolean,
        excludeFromRecents: Boolean,
        appLockEnabled: Boolean,
        subscriptionUserAgent: String
    ) = settingsStore.setPrivacyOpSecConfig(flagSecureEnabled, excludeFromRecents, appLockEnabled, subscriptionUserAgent)

    suspend fun setAdaptiveRoutingConfig(
        adaptiveFallbackEnabled: Boolean,
        failClosedEch: Boolean,
        stallThresholdSec: Int
    ) = settingsStore.setAdaptiveRoutingConfig(adaptiveFallbackEnabled, failClosedEch, stallThresholdSec)

    suspend fun getNetworkStrategy(fingerprint: String): NetworkStrategyEntity? =
        networkStrategyDao.getByFingerprint(fingerprint)

    suspend fun upsertNetworkStrategy(strategy: NetworkStrategyEntity) =
        networkStrategyDao.upsert(strategy)

    suspend fun clearNetworkStrategies() {
        networkStrategyDao.clearAll()
        logEvent(
            level = LogLevel.INFO,
            category = LogCategory.ANTI_DPI,
            message = "Cleared learned per-network adaptive routing strategies"
        )
    }

    suspend fun benchmarkDnsPool(
        domain: String = "www.cloudflare.com",
        settings: StabilitySettings? = null
    ) = com.example.xraycore.LuxyVpnCore.benchmarkMultiResolverPool(
        domain = domain,
        settings = settings ?: settingsFlow.first()
    )

    suspend fun probeFragmentedTls(
        host: String,
        port: Int,
        sni: String,
        lengthRange: String,
        intervalRange: String,
        enableFragment: Boolean,
        cipherSuites: String = "",
        maxSplitRange: String = "32-64"
    ) = com.example.xraycore.LuxyVpnCore.probeFragmentedTls(
        host = host,
        port = port,
        sni = sni,
        lengthRangeStr = lengthRange,
        intervalRangeStr = intervalRange,
        enableFragment = enableFragment,
        cipherSuites = cipherSuites,
        maxSplitRangeStr = maxSplitRange
    )

    suspend fun probeRealitySni(sni: String, port: Int = 443) =
        com.example.xraycore.LuxyVpnCore.probeRealitySniReadiness(sni, port)

    suspend fun verifyDohAntiPoisoning(dohUrl: String) =
        com.example.xraycore.LuxyVpnCore.verifyDohAntiPoisoning(dohUrl)

    suspend fun probeEchDnsConfig(domain: String, dohUrl: String) =
        com.example.xraycore.LuxyVpnCore.probeEchDnsConfig(domain, dohUrl)

    suspend fun logEvent(
        level: LogLevel,
        category: LogCategory,
        message: String,
        latencyMs: Int? = null
    ) {
        eventDao.insert(
            StabilityEventEntity(
                level = level.name,
                category = category.name,
                message = message,
                latencyMs = latencyMs
            )
        )
        eventDao.trimOld()
    }

    suspend fun clearEvents() = eventDao.clearAll()
}
