package com.example.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LuxyVpnApplication
import com.example.core.EncryptedConfigVault
import com.example.core.ProtocolGuardrails
import com.example.core.SubscriptionFetchResult
import com.example.core.VaultImportResult
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.ColorPalette
import com.example.data.model.ConnectionState
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.NodeSortOrder
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelTelemetry
import com.example.vpn.TunnelManager
import com.example.xraycore.CleanIpCandidateResult
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.DnsPoolQueryResult
import com.example.xraycore.DohProbeResult
import com.example.xraycore.EchProbeResult
import com.example.xraycore.FragmentTlsProbeResult
import com.example.xraycore.RealityReadinessResult
import com.example.xraycore.WarpEndpointCandidateResult
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class BatchProbeProgress(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0
)

@Immutable
data class CleanIpScanState(
    val isScanning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<CleanIpCandidateResult> = emptyList()
)

@Immutable
data class WarpEndpointScanState(
    val isScanning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<WarpEndpointCandidateResult> = emptyList()
)

@Immutable
data class LuxyVpnUiState(
    val telemetry: TunnelTelemetry = TunnelTelemetry(),
    val settings: StabilitySettings = StabilitySettings(),
    val servers: List<ServerProfileEntity> = emptyList(),
    val filteredServers: List<ServerProfileEntity> = emptyList(),
    val selectedNode: ServerProfileEntity? = null,
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val routingRules: List<RoutingRuleEntity> = emptyList(),
    val stabilityEvents: List<StabilityEventEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedGroup: String = "All",
    val sortOrder: NodeSortOrder = NodeSortOrder.DEFAULT,
    val batchProbe: BatchProbeProgress = BatchProbeProgress(),
    val isSyncingSubscription: Boolean = false,
    val dnsProbeResultMs: Int? = null,
    val isTestingDns: Boolean = false,
    // Anti-Filtering & Xray Core Diagnostic States
    val cleanIpScanState: CleanIpScanState = CleanIpScanState(),
    val warpEndpointScanState: WarpEndpointScanState = WarpEndpointScanState(),
    val lastFragmentProbe: FragmentTlsProbeResult? = null,
    val isTestingFragment: Boolean = false,
    val lastRealityProbe: RealityReadinessResult? = null,
    val isTestingReality: Boolean = false,
    val lastDohProbe: DohProbeResult? = null,
    val isTestingDoh: Boolean = false,
    val lastEchProbe: EchProbeResult? = null,
    val isTestingEch: Boolean = false,
    val lastDnsPoolResult: DnsPoolQueryResult? = null,
    val isBenchmarkingDnsPool: Boolean = false,
    val networkStrategies: List<NetworkStrategyEntity> = emptyList()
)

class LuxyVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as LuxyVpnApplication).repository

    private val searchQuery = MutableStateFlow("")
    private val selectedGroup = MutableStateFlow("All")
    private val sortOrder = MutableStateFlow(NodeSortOrder.DEFAULT)
    private val batchProbe = MutableStateFlow(BatchProbeProgress())
    private val isSyncingSubscription = MutableStateFlow(false)
    private val dnsProbeResultMs = MutableStateFlow<Int?>(null)
    private val isTestingDns = MutableStateFlow(false)

    private val cleanIpScanState = MutableStateFlow(CleanIpScanState())
    private val warpEndpointScanState = MutableStateFlow(WarpEndpointScanState())
    private val lastFragmentProbe = MutableStateFlow<FragmentTlsProbeResult?>(null)
    private val isTestingFragment = MutableStateFlow(false)
    private val lastRealityProbe = MutableStateFlow<RealityReadinessResult?>(null)
    private val isTestingReality = MutableStateFlow(false)
    private val lastDohProbe = MutableStateFlow<DohProbeResult?>(null)
    private val isTestingDoh = MutableStateFlow(false)
    private val lastEchProbe = MutableStateFlow<EchProbeResult?>(null)
    private val isTestingEch = MutableStateFlow(false)
    private val lastDnsPoolResult = MutableStateFlow<DnsPoolQueryResult?>(null)
    private val isBenchmarkingDnsPool = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val filterParamsFlow = combine(
        searchQuery,
        selectedGroup,
        sortOrder
    ) { q, g, s -> Triple(q, g, s) }

    private val dnsPoolDiagFlow = combine(
        lastDnsPoolResult,
        isBenchmarkingDnsPool,
        repository.networkStrategiesFlow
    ) { res, running, strategies ->
        Triple(res, running, strategies)
    }

    private val auxStateFlow = combine(
        batchProbe,
        isSyncingSubscription,
        dnsProbeResultMs,
        isTestingDns,
        dnsPoolDiagFlow
    ) { probe, syncing, dnsMs, testingDns, poolDiag ->
        AuxUiData(probe, syncing, dnsMs, testingDns, poolDiag.first, poolDiag.second, poolDiag.third)
    }

    private val echDiagFlow = combine(lastEchProbe, isTestingEch, warpEndpointScanState) { ech, testing, warpScan ->
        Triple(ech, testing, warpScan)
    }

    private val xrayDiagFlow = combine(
        cleanIpScanState,
        lastFragmentProbe,
        isTestingFragment,
        lastRealityProbe,
        isTestingReality,
        lastDohProbe,
        isTestingDoh,
        echDiagFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val echTriple = values[7] as Triple<EchProbeResult?, Boolean, WarpEndpointScanState>
        XrayDiagUiData(
            cleanIpScan = values[0] as CleanIpScanState,
            fragmentProbe = values[1] as FragmentTlsProbeResult?,
            testingFragment = values[2] as Boolean,
            realityProbe = values[3] as RealityReadinessResult?,
            testingReality = values[4] as Boolean,
            dohProbe = values[5] as DohProbeResult?,
            testingDoh = values[6] as Boolean,
            echProbe = echTriple.first,
            testingEch = echTriple.second,
            warpEndpointScan = echTriple.third
        )
    }

    val uiState: StateFlow<LuxyVpnUiState> = combine(
        TunnelManager.telemetry,
        repository.settingsFlow,
        repository.serversFlow,
        repository.subscriptionsFlow,
        repository.routingRulesFlow,
        repository.stabilityEventsFlow,
        filterParamsFlow,
        auxStateFlow,
        xrayDiagFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[0] as TunnelTelemetry
        val settings = values[1] as StabilitySettings
        @Suppress("UNCHECKED_CAST")
        val servers = values[2] as List<ServerProfileEntity>
        @Suppress("UNCHECKED_CAST")
        val subscriptions = values[3] as List<SubscriptionEntity>
        @Suppress("UNCHECKED_CAST")
        val rules = values[4] as List<RoutingRuleEntity>
        @Suppress("UNCHECKED_CAST")
        val events = values[5] as List<StabilityEventEntity>
        @Suppress("UNCHECKED_CAST")
        val filterTriple = values[6] as Triple<String, String, NodeSortOrder>
        val (query, group, sort) = filterTriple
        val aux = values[7] as AuxUiData
        val xrayDiag = values[8] as XrayDiagUiData

        val selected = servers.firstOrNull { it.id == settings.activeProfileId }
            ?: telemetry.activeNode?.let { active -> servers.firstOrNull { it.id == active.id } }
            ?: servers.firstOrNull()

        val filtered = servers
            .filter { node ->
                val matchesGroup = group == "All" || node.groupName.equals(group, ignoreCase = true)
                val matchesQuery = query.isBlank() ||
                    node.name.contains(query, ignoreCase = true) ||
                    (!node.isLocked && node.address.contains(query, ignoreCase = true)) ||
                    node.protocol.contains(query, ignoreCase = true)
                matchesGroup && matchesQuery
            }
            .let { list ->
                when (sort) {
                    NodeSortOrder.DEFAULT -> list
                    NodeSortOrder.LATENCY -> list.sortedWith(
                        compareBy<ServerProfileEntity> {
                            when {
                                it.latencyMs == null -> Int.MAX_VALUE - 1
                                it.latencyMs < 0 -> Int.MAX_VALUE
                                else -> it.latencyMs
                            }
                        }.thenBy { it.name }
                    )
                    NodeSortOrder.NAME -> list.sortedBy { it.name.lowercase() }
                    NodeSortOrder.PROTOCOL -> list.sortedWith(compareBy({ it.protocol }, { it.name.lowercase() }))
                }
            }

        LuxyVpnUiState(
            telemetry = telemetry,
            settings = settings,
            servers = servers,
            filteredServers = filtered,
            selectedNode = selected,
            subscriptions = subscriptions,
            routingRules = rules,
            stabilityEvents = events,
            searchQuery = query,
            selectedGroup = group,
            sortOrder = sort,
            batchProbe = aux.batchProbe,
            isSyncingSubscription = aux.isSyncing,
            dnsProbeResultMs = aux.dnsProbeMs,
            isTestingDns = aux.isTestingDns,
            cleanIpScanState = xrayDiag.cleanIpScan,
            warpEndpointScanState = xrayDiag.warpEndpointScan,
            lastFragmentProbe = xrayDiag.fragmentProbe,
            isTestingFragment = xrayDiag.testingFragment,
            lastRealityProbe = xrayDiag.realityProbe,
            isTestingReality = xrayDiag.testingReality,
            lastDohProbe = xrayDiag.dohProbe,
            isTestingDoh = xrayDiag.testingDoh,
            lastEchProbe = xrayDiag.echProbe,
            isTestingEch = xrayDiag.testingEch,
            lastDnsPoolResult = aux.dnsPoolResult,
            isBenchmarkingDnsPool = aux.isBenchmarkingDnsPool,
            networkStrategies = aux.networkStrategies
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LuxyVpnUiState()
        )

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedGroup(group: String) {
        selectedGroup.value = group
    }

    fun setSortOrder(order: NodeSortOrder) {
        sortOrder.value = order
    }

    fun selectNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            repository.setActiveProfileId(profile.id)
            val currentSettings = uiState.value.settings
            val (safeMode, safeRelay) = ProtocolGuardrails.sanitizeChaining(
                currentSettings.chainingMode,
                currentSettings.relayNodeId,
                profile
            )
            if (safeMode != currentSettings.chainingMode || safeRelay != currentSettings.relayNodeId) {
                repository.setChainingConfig(safeMode, safeRelay)
            }
            val state = uiState.value.telemetry.connectionState
            if (state == ConnectionState.CONNECTED ||
                state == ConnectionState.RECONNECTING ||
                state == ConnectionState.FAILOVER
            ) {
                TunnelManager.switchNode(getApplication(), profile)
                _messages.tryEmit("Switched tunnel to ${profile.name}")
            }
        }
    }

    fun saveServer(profile: ServerProfileEntity) {
        viewModelScope.launch {
            val sanitized = ProtocolGuardrails.sanitizeProfileForSave(profile)
            repository.saveServer(sanitized)
            if (uiState.value.settings.activeProfileId == null) {
                repository.setActiveProfileId(sanitized.id)
            }
            repository.logEvent(
                level = LogLevel.INFO,
                category = LogCategory.TUNNEL,
                message = "Saved node ${sanitized.name} (${sanitized.protocol})"
            )
            _messages.tryEmit("Saved ${sanitized.name}")
        }
    }

    fun deleteServer(profile: ServerProfileEntity) {
        viewModelScope.launch {
            if (uiState.value.telemetry.activeNode?.id == profile.id &&
                uiState.value.telemetry.connectionState != ConnectionState.DISCONNECTED
            ) {
                TunnelManager.stopTunnel(getApplication())
            }
            repository.deleteServer(profile.id)
            _messages.tryEmit("Deleted ${profile.name}")
        }
    }

    fun togglePin(profile: ServerProfileEntity) {
        viewModelScope.launch {
            repository.togglePin(profile.id, !profile.isPinned)
        }
    }

    fun importFromText(rawText: String, groupName: String = "Imported") {
        viewModelScope.launch {
            if (EncryptedConfigVault.isVaultPayload(rawText)) {
                _messages.tryEmit("Detected encrypted .zsx/.kage vault — open Vault Import to unlock")
                return@launch
            }
            val report = repository.importFromTextWithReport(rawText, groupName)
            if (report.nodes.isNotEmpty()) {
                val extra = buildList {
                    if (report.trimmedCount > 0) add("Top-${report.nodes.size} kept")
                    if (report.strippedDirectives.isNotEmpty()) add("${report.strippedDirectives.size} directive(s) sanitized")
                }.joinToString(", ")
                val suffix = if (extra.isNotEmpty()) " ($extra)" else ""
                _messages.tryEmit("Imported ${report.nodes.size} node(s)$suffix")
            } else {
                _messages.tryEmit("No valid URIs or Xray JSON profiles found")
            }
        }
    }

    fun importVaultBundle(
        rawPayload: String,
        passphrase: String?,
        privateKeyBase64: String?,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val res = repository.importVaultPayload(rawPayload, passphrase, privateKeyBase64)) {
                is VaultImportResult.Success -> {
                    _messages.tryEmit("Unlocked ${res.nodes.size} locked vault profile(s)")
                    onSuccess()
                }
                is VaultImportResult.Expired -> {
                    _messages.tryEmit("Vault expired — cannot unlock expired configuration")
                }
                is VaultImportResult.Error -> {
                    _messages.tryEmit(res.reason)
                }
            }
        }
    }

    fun exportVaultBundle(
        nodes: List<ServerProfileEntity>,
        passphrase: String?,
        recipientPublicKeyBase64: String?,
        ttlHours: Int?
    ): Result<String> {
        return try {
            val expiresAt = ttlHours?.takeIf { it > 0 }?.let {
                System.currentTimeMillis() + it * 3_600_000L
            }
            val unlocked = nodes.filter { !it.isLocked }
            require(unlocked.isNotEmpty()) { "Select an unlocked node to export into a new vault" }
            val bundle = if (!recipientPublicKeyBase64.isNullOrBlank()) {
                EncryptedConfigVault.exportWithX25519(unlocked, recipientPublicKeyBase64, expiresAt)
            } else {
                EncryptedConfigVault.exportWithPassphrase(unlocked, passphrase.orEmpty(), expiresAt)
            }
            Result.success(bundle)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun probeSingleNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            val res = repository.probeServer(profile)
            if (res.success) {
                _messages.tryEmit("${profile.name}: ${res.rttMs} ms (±${res.jitterMs} ms)")
            } else {
                val err = EncryptedConfigVault.sanitizeLogText(res.error.orEmpty(), profile)
                _messages.tryEmit("${profile.name}: unreachable ($err)")
            }
        }
    }

    fun probeAllNodes() {
        val nodes = uiState.value.servers
        if (nodes.isEmpty() || batchProbe.value.isRunning) return
        viewModelScope.launch {
            batchProbe.value = BatchProbeProgress(isRunning = true, completed = 0, total = nodes.size)
            repository.probeAllServers(nodes) { completed, total ->
                batchProbe.value = BatchProbeProgress(isRunning = true, completed = completed, total = total)
            }
            batchProbe.value = BatchProbeProgress(isRunning = false, completed = nodes.size, total = nodes.size)
            _messages.tryEmit("Tested ${nodes.size} node(s)")
        }
    }

    fun selectFastestNode() {
        val servers = uiState.value.servers
        if (servers.isEmpty()) return
        viewModelScope.launch {
            if (servers.none { (it.latencyMs ?: -1) > 0 }) {
                batchProbe.value = BatchProbeProgress(isRunning = true, completed = 0, total = servers.size)
                repository.probeAllServers(servers) { completed, total ->
                    batchProbe.value = BatchProbeProgress(isRunning = true, completed = completed, total = total)
                }
                batchProbe.value = BatchProbeProgress(isRunning = false)
            }
            val updated = repository.getAllServersOnce()
            val fastest = updated
                .filter { (it.latencyMs ?: -1) > 0 }
                .minByOrNull { it.latencyMs ?: Int.MAX_VALUE }

            if (fastest != null) {
                selectNode(fastest)
                _messages.tryEmit("Selected fastest: ${fastest.name} (${fastest.latencyMs} ms)")
            } else {
                _messages.tryEmit("No reachable nodes responded to TCP probe")
            }
        }
    }

    fun addSubscription(name: String, url: String, autoUpdateHours: Int) {
        viewModelScope.launch {
            isSyncingSubscription.value = true
            when (val res = repository.addAndSyncSubscription(name, url, autoUpdateHours)) {
                is SubscriptionFetchResult.Success -> {
                    _messages.tryEmit("Synced ${res.nodes.size} nodes from $name")
                }
                is SubscriptionFetchResult.Error -> {
                    _messages.tryEmit("Sync error: ${res.message}")
                }
            }
            isSyncingSubscription.value = false
        }
    }

    fun syncSubscription(subscriptionId: String) {
        viewModelScope.launch {
            isSyncingSubscription.value = true
            when (val res = repository.syncSubscription(subscriptionId)) {
                is SubscriptionFetchResult.Success -> _messages.tryEmit("Synced ${res.nodes.size} nodes")
                is SubscriptionFetchResult.Error -> _messages.tryEmit("Sync error: ${res.message}")
            }
            isSyncingSubscription.value = false
        }
    }

    fun syncAllSubscriptions() {
        val subs = uiState.value.subscriptions
        if (subs.isEmpty()) return
        viewModelScope.launch {
            isSyncingSubscription.value = true
            var totalNodes = 0
            for (sub in subs) {
                val res = repository.syncSubscription(sub.id)
                if (res is SubscriptionFetchResult.Success) {
                    totalNodes += res.nodes.size
                }
            }
            isSyncingSubscription.value = false
            _messages.tryEmit("Synced ${subs.size} subscription(s): $totalNodes nodes")
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            repository.deleteSubscription(id)
            _messages.tryEmit("Removed subscription")
        }
    }

    fun saveRoutingRule(rule: RoutingRuleEntity) {
        viewModelScope.launch {
            repository.saveRoutingRule(rule)
            _messages.tryEmit("Saved routing rule: ${rule.name}")
        }
    }

    fun setRoutingRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setRoutingRuleEnabled(id, enabled)
        }
    }

    fun deleteRoutingRule(id: String) {
        viewModelScope.launch {
            repository.deleteRoutingRule(id)
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setColorPalette(palette: ColorPalette) = viewModelScope.launch { repository.setColorPalette(palette) }
    fun setRoutingMode(mode: RoutingMode) = viewModelScope.launch { repository.setRoutingMode(mode) }
    fun setAutoConnectOnLaunch(enabled: Boolean) = viewModelScope.launch { repository.setAutoConnectOnLaunch(enabled) }
    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch { repository.setAutoReconnect(enabled) }
    fun setSmartFailover(enabled: Boolean) = viewModelScope.launch { repository.setSmartFailover(enabled) }
    fun setProbeIntervalSec(sec: Int) = viewModelScope.launch { repository.setProbeIntervalSec(sec) }
    fun setMuxEnabled(enabled: Boolean) = viewModelScope.launch {
        val active = uiState.value.selectedNode
        if (enabled && ProtocolGuardrails.isDatagramNode(active)) {
            _messages.tryEmit("Mux is unavailable for ${active?.protocol} datagram protocol")
            return@launch
        }
        repository.setMuxEnabled(enabled)
    }
    fun setMuxConcurrency(c: Int) = viewModelScope.launch { repository.setMuxConcurrency(c) }
    fun setTlsFragmentEnabled(enabled: Boolean) = viewModelScope.launch {
        val active = uiState.value.selectedNode
        if (enabled && ProtocolGuardrails.isDatagramNode(active)) {
            _messages.tryEmit("TLS Fragmentation is unavailable for ${active?.protocol} datagram protocol")
            return@launch
        }
        val wasTfoOn = uiState.value.settings.tcpFastOpen
        repository.setTlsFragmentEnabled(enabled)
        if (enabled && wasTfoOn) {
            _messages.tryEmit("TLS Fragmentation enabled — TCP Fast Open disabled automatically")
        }
    }
    fun setTcpFastOpen(enabled: Boolean) = viewModelScope.launch {
        val active = uiState.value.selectedNode
        if (enabled && ProtocolGuardrails.isDatagramNode(active)) {
            _messages.tryEmit("TCP Fast Open is unavailable for ${active?.protocol} datagram protocol")
            return@launch
        }
        val wasFragmentOn = uiState.value.settings.tlsFragmentEnabled
        repository.setTcpFastOpen(enabled)
        if (enabled && wasFragmentOn) {
            _messages.tryEmit("TCP Fast Open enabled — TLS Fragmentation disabled automatically")
        }
    }
    fun setMtu(mtu: Int) = viewModelScope.launch { repository.setMtu(mtu) }

    // LuxyVPN Xray Core & Anti-Censorship Actions
    fun applyAntiCensorshipPreset(preset: AntiCensorshipPreset) {
        viewModelScope.launch {
            repository.applyAntiCensorshipPreset(preset)
            _messages.tryEmit("Applied ${preset.label} preset to LuxyVpnCore")
        }
    }

    fun setFragmentParams(
        packets: String,
        length: String,
        interval: String,
        host: String? = null,
        maxSplit: String? = null
    ) {
        viewModelScope.launch { repository.setFragmentParams(packets, length, interval, host, maxSplit) }
    }

    fun setNoiseConfig(
        enabled: Boolean,
        packetSize: String = uiState.value.settings.noisePacketSize,
        delayMs: String = uiState.value.settings.noiseDelayMs,
        noiseType: String? = null,
        applyTo: String? = null
    ) {
        viewModelScope.launch { repository.setNoiseConfig(enabled, packetSize, delayMs, noiseType, applyTo) }
    }

    fun setUtlsFingerprint(fp: String) = viewModelScope.launch { repository.setUtlsFingerprint(fp) }
    fun setFakeDnsEnabled(enabled: Boolean) = viewModelScope.launch { repository.setFakeDnsEnabled(enabled) }
    fun setSniffingEnabled(enabled: Boolean) = viewModelScope.launch { repository.setSniffingEnabled(enabled) }
    fun setBlockQuicUdp(enabled: Boolean) = viewModelScope.launch { repository.setBlockQuicUdp(enabled) }
    fun setXhttpConfig(mode: String, paddingBytes: String) =
        viewModelScope.launch { repository.setXhttpConfig(mode, paddingBytes) }

    fun setCipherSuites(cipherSuites: String) =
        viewModelScope.launch { repository.setCipherSuites(cipherSuites) }

    fun setFinalMaskType(finalMaskType: String) =
        viewModelScope.launch { repository.setFinalMaskType(finalMaskType) }

    fun setEchConfig(enabled: Boolean, configList: String? = null, forceQuery: String? = null) =
        viewModelScope.launch { repository.setEchConfig(enabled, configList, forceQuery) }

    fun setHappyEyeballsEnabled(enabled: Boolean, tryDelayMs: Int = 250) =
        viewModelScope.launch { repository.setHappyEyeballsEnabled(enabled, tryDelayMs) }

    fun setAllowUnencryptedVless(enabled: Boolean) =
        viewModelScope.launch { repository.setAllowUnencryptedVless(enabled) }

    fun setSubscriptionTopNLimit(limit: Int) =
        viewModelScope.launch {
            repository.setSubscriptionTopNLimit(limit)
            _messages.tryEmit(
                if (limit > 0) "Lazy subscription optimizer set to Top $limit nodes"
                else "Lazy subscription optimizer set to Keep All"
            )
        }

    fun setWarpConfig(
        endpoint: String,
        privateKey: String,
        publicKey: String,
        reserved: String,
        localAddress: String
    ) = viewModelScope.launch {
        repository.setWarpConfig(endpoint, privateKey, publicKey, reserved, localAddress)
        _messages.tryEmit("Updated WireGuard / WARP transport profile")
    }

    fun setAmneziaWgParams(
        jc: Int,
        jmin: Int,
        jmax: Int,
        s1: Int,
        s2: Int,
        h1: Long,
        h2: Long,
        h3: Long,
        h4: Long
    ) = viewModelScope.launch {
        repository.setAmneziaWgParams(jc, jmin, jmax, s1, s2, h1, h2, h3, h4)
        _messages.tryEmit("Updated AmneziaWG padding parameters (Jc=$jc, S1=$s1, S2=$s2)")
    }

    fun setWarpNoisePreset(preset: String) =
        viewModelScope.launch { repository.setWarpNoisePreset(preset) }

    fun setChainingConfig(mode: ChainingMode, relayNodeId: String?) =
        viewModelScope.launch {
            val active = uiState.value.selectedNode
            val (safeMode, safeRelay) = ProtocolGuardrails.sanitizeChaining(mode, relayNodeId, active)
            if (safeMode != mode) {
                _messages.tryEmit("${mode.label} is incompatible with ${active?.protocol ?: "active"} transport")
            } else if (relayNodeId != null && safeRelay == null &&
                (mode == ChainingMode.NODE_TO_NODE || mode == ChainingMode.PROXY_TO_WARP)
            ) {
                _messages.tryEmit("Active node cannot relay to itself — choose a distinct relay node")
            } else {
                _messages.tryEmit("Multi-hop chaining: ${safeMode.label}")
            }
            repository.setChainingConfig(safeMode, safeRelay)
        }

    fun setAdvancedTlsOptions(
        mixedCaseSni: Boolean,
        tlsPaddingEnabled: Boolean,
        realityFragmentEnabled: Boolean
    ) = viewModelScope.launch {
        repository.setAdvancedTlsOptions(mixedCaseSni, tlsPaddingEnabled, realityFragmentEnabled)
    }

    fun setSocketMaskAndTimeouts(
        finalMaskFamily: String,
        tcpTimeoutMs: Int,
        tlsTimeoutMs: Int
    ) = viewModelScope.launch {
        repository.setSocketMaskAndTimeouts(finalMaskFamily, tcpTimeoutMs, tlsTimeoutMs)
    }

    fun scanWarpEndpoints(cidrOrEndpoints: String, ports: List<Int>) {
        if (warpEndpointScanState.value.isScanning) return
        viewModelScope.launch {
            warpEndpointScanState.value = WarpEndpointScanState(isScanning = true, completed = 0, total = 24)
            val results = CleanIpScanner.benchmarkWarpEndpoints(
                cidrOrEndpoints = cidrOrEndpoints,
                ports = ports
            ) { done, total ->
                warpEndpointScanState.value = warpEndpointScanState.value.copy(completed = done, total = total)
            }
            warpEndpointScanState.value = WarpEndpointScanState(
                isScanning = false,
                completed = results.size,
                total = results.size,
                results = results
            )
            repository.logEvent(
                level = if (results.isNotEmpty()) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.PROBE,
                message = "WARP/MASQUE endpoint benchmark finished: ${results.size} candidate(s) ranked"
            )
        }
    }

    fun applyWarpEndpoint(endpoint: String) {
        val clean = endpoint.trim()
        val host = clean.substringBeforeLast(":")
        val port = clean.substringAfterLast(":").toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _messages.tryEmit("Invalid WARP endpoint format")
            return
        }
        viewModelScope.launch {
            repository.setWarpEndpoint(clean)
            val active = uiState.value.selectedNode
            val proto = active?.let { ProxyProtocol.fromString(it.protocol) }
            if (active != null && !active.isLocked &&
                (proto == ProxyProtocol.WIREGUARD || proto == ProxyProtocol.MASQUE)
            ) {
                repository.saveServer(active.copy(address = host, port = port, rawUri = ""))
            }
            repository.logEvent(
                level = LogLevel.RECOVERY,
                category = LogCategory.TUNNEL,
                message = "Applied fastest WARP/MASQUE endpoint: $clean"
            )
            _messages.tryEmit("Applied WARP endpoint $clean")
        }
    }

    fun probeEchDns(domain: String) {
        val dohUrl = uiState.value.settings.dohServerUrl
        viewModelScope.launch {
            isTestingEch.value = true
            val res = repository.probeEchDnsConfig(domain, dohUrl)
            lastEchProbe.value = res
            repository.logEvent(
                level = if (res.echSupported) LogLevel.RECOVERY else LogLevel.WARN,
                category = LogCategory.ANTI_DPI,
                message = if (res.echSupported)
                    "ECH HTTPS RR ready for '${res.domain}' (${res.rttMs} ms, records: ${res.httpsRecords.size})"
                else "ECH probe '${res.domain}': ${res.error}",
                latencyMs = if (res.rttMs > 0) res.rttMs else null
            )
            isTestingEch.value = false
        }
    }

    fun testFragmentedTlsOnActiveNode() {
        val node = uiState.value.selectedNode
        val settings = uiState.value.settings
        val targetHost = node?.address ?: "www.cloudflare.com"
        val targetPort = node?.port ?: 443
        val targetSni = settings.fragmentHost.ifBlank {
            node?.sni?.ifBlank { node.hostHeader.ifBlank { targetHost } } ?: "www.cloudflare.com"
        }
        val effectiveCiphers = node?.cipherSuites?.ifBlank { settings.cipherSuites } ?: settings.cipherSuites

        viewModelScope.launch {
            isTestingFragment.value = true
            val res = com.example.xraycore.LuxyVpnCore.probeFragmentedTls(
                host = targetHost,
                port = targetPort,
                sni = targetSni,
                lengthRangeStr = settings.fragmentLength,
                intervalRangeStr = settings.fragmentInterval,
                enableFragment = settings.tlsFragmentEnabled,
                cipherSuites = effectiveCiphers,
                maxSplitRangeStr = settings.fragmentMaxSplit,
                timeoutMs = settings.tlsHandshakeTimeoutMs,
                mixedCaseSni = settings.mixedCaseSni
            )
            lastFragmentProbe.value = res
            val displaySni = if (node?.isLocked == true) EncryptedConfigVault.REDACTED_VALUE else targetSni
            val safeError = EncryptedConfigVault.sanitizeLogText(res.error.orEmpty(), node)
            repository.logEvent(
                level = if (res.success) LogLevel.RECOVERY else LogLevel.WARN,
                category = LogCategory.ANTI_DPI,
                message = if (res.success)
                    "Fragmented TLS probe ($displaySni): ${res.rttMs} ms • ${res.fragmentsSent} segs • ${res.tlsProtocol} (${res.cipherSuite})"
                else "Fragmented TLS probe ($displaySni) failed: $safeError",
                latencyMs = if (res.success) res.rttMs else null
            )
            isTestingFragment.value = false
        }
    }

    fun testRealitySni(sni: String) {
        viewModelScope.launch {
            isTestingReality.value = true
            val res = repository.probeRealitySni(sni)
            lastRealityProbe.value = res
            repository.logEvent(
                level = if (res.isReady) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.XRAY_CORE,
                message = if (res.isReady)
                    "REALITY SNI '${res.sni}' ready (${res.tlsVersion}, ${res.rttMs} ms)"
                else "REALITY SNI '${res.sni}': ${res.error}",
                latencyMs = if (res.rttMs > 0) res.rttMs else null
            )
            isTestingReality.value = false
        }
    }

    fun scanCleanIps(cidrOrIps: String, sni: String) {
        if (cleanIpScanState.value.isScanning) return
        val settings = uiState.value.settings
        viewModelScope.launch {
            cleanIpScanState.value = CleanIpScanState(isScanning = true, completed = 0, total = 18)
            val results = CleanIpScanner.scan(
                cidrOrIps = cidrOrIps,
                port = 443,
                sni = sni.ifBlank { "www.cloudflare.com" },
                useFragment = settings.tlsFragmentEnabled,
                fragmentLength = settings.fragmentLength,
                fragmentInterval = settings.fragmentInterval
            ) { done, total ->
                cleanIpScanState.value = cleanIpScanState.value.copy(completed = done, total = total)
            }
            cleanIpScanState.value = CleanIpScanState(
                isScanning = false,
                completed = cleanIpScanState.value.total,
                total = cleanIpScanState.value.total,
                results = results
            )
            repository.logEvent(
                level = if (results.isNotEmpty()) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.ANTI_DPI,
                message = "Clean IP scan finished: ${results.size} responsive endpoint(s) found"
            )
        }
    }

    fun applyCleanIpToSelectedNode(ip: String) {
        val node = uiState.value.selectedNode
        if (node == null) {
            _messages.tryEmit("No active node selected to update")
            return
        }
        if (node.isLocked) {
            _messages.tryEmit("Cannot mutate endpoint of a locked encrypted vault node")
            return
        }
        viewModelScope.launch {
            val preservedSni = node.sni.ifBlank { node.hostHeader.ifBlank { node.address } }
            val updated = node.copy(
                address = ip,
                sni = preservedSni,
                hostHeader = node.hostHeader.ifBlank { preservedSni },
                rawUri = ""
            )
            repository.saveServer(updated)
            repository.logEvent(
                level = LogLevel.RECOVERY,
                category = LogCategory.ANTI_DPI,
                message = "Applied Clean IP $ip to ${node.name} (SNI preserved: $preservedSni)"
            )
            _messages.tryEmit("Updated ${node.name} endpoint to Clean IP $ip")
        }
    }

    fun compileActiveXrayConfig(): String {
        val state = uiState.value
        val relay = state.settings.relayNodeId?.let { id -> state.servers.firstOrNull { it.id == id } }
        return XrayConfigCompiler.compileRedactedForInspector(state.selectedNode, state.settings, state.routingRules, relay)
    }

    suspend fun compileActiveXrayConfigAsync(): String = withContext(Dispatchers.Default) {
        val state = uiState.value
        val relay = state.settings.relayNodeId?.let { id -> state.servers.firstOrNull { it.id == id } }
        XrayConfigCompiler.compileRedactedForInspector(state.selectedNode, state.settings, state.routingRules, relay)
    }

    fun setDnsConfig(
        primary: String,
        fallback: String,
        doh: Boolean,
        dohUrl: String = uiState.value.settings.dohServerUrl
    ) {
        viewModelScope.launch {
            repository.setDnsConfig(primary, fallback, doh, dohUrl)
            _messages.tryEmit("Updated DNS configuration")
        }
    }

    fun testDnsLatency() {
        val dns = uiState.value.settings.primaryDns
        viewModelScope.launch {
            isTestingDns.value = true
            val res = repository.testDnsLatency(dns)
            dnsProbeResultMs.value = if (res.success) res.rttMs else -1
            repository.logEvent(
                level = if (res.success) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.DNS,
                message = if (res.success) "DNS $dns responded in ${res.rttMs} ms" else "DNS $dns failed: ${res.error}",
                latencyMs = if (res.success) res.rttMs else null
            )
            isTestingDns.value = false
        }
    }

    fun verifyDohAntiPoisoning() {
        val dohUrl = uiState.value.settings.dohServerUrl
        viewModelScope.launch {
            isTestingDoh.value = true
            val res = repository.verifyDohAntiPoisoning(dohUrl)
            lastDohProbe.value = res
            repository.logEvent(
                level = if (res.success) LogLevel.RECOVERY else LogLevel.WARN,
                category = LogCategory.DNS,
                message = if (res.success)
                    "DoH Anti-Poisoning verified (${res.rttMs} ms, ${res.resolvedIps.take(2).joinToString()})"
                else "DoH verification failed: ${res.error}",
                latencyMs = if (res.success) res.rttMs else null
            )
            isTestingDoh.value = false
        }
    }

    fun setDnsPoolConfig(
        strategy: DnsPoolStrategy,
        integrityFilterEnabled: Boolean,
        dotServer: String,
        dotEnabled: Boolean,
        tcpEnabled: Boolean,
        udpEnabled: Boolean
    ) = viewModelScope.launch {
        repository.setDnsPoolConfig(strategy, integrityFilterEnabled, dotServer, dotEnabled, tcpEnabled, udpEnabled)
        _messages.tryEmit("Updated DNS Pool (${strategy.label})")
    }

    fun benchmarkDnsPool() {
        val currentSettings = uiState.value.settings
        if (isBenchmarkingDnsPool.value) return
        viewModelScope.launch {
            isBenchmarkingDnsPool.value = true
            val res = repository.benchmarkDnsPool(settings = currentSettings)
            lastDnsPoolResult.value = res
            val poisoningNote = if (res.rejectedForPoisoning.isNotEmpty()) {
                " • Filtered ${res.rejectedForPoisoning.size} poisoned/failed answer(s)"
            } else ""
            repository.logEvent(
                level = if (res.success) LogLevel.RECOVERY else LogLevel.WARN,
                category = LogCategory.DNS,
                message = if (res.success)
                    "DNS Pool [${currentSettings.dnsPoolStrategy.label}]: winner ${res.winnerLabel} (${res.rttMs} ms) -> ${res.resolvedIps.take(2).joinToString()}$poisoningNote"
                else "DNS Pool check failed: ${res.error}",
                latencyMs = if (res.success) res.rttMs else null
            )
            isBenchmarkingDnsPool.value = false
        }
    }

    fun setSplitTunnelConfig(
        mode: AppSplitTunnelMode,
        packagesCsv: String,
        strictUidIsolation: Boolean
    ) = viewModelScope.launch {
        repository.setSplitTunnelConfig(mode, packagesCsv, strictUidIsolation)
        _messages.tryEmit("Updated UID split-tunnel policy (${mode.label})")
    }

    fun setPrivacyOpSecConfig(
        flagSecureEnabled: Boolean,
        excludeFromRecents: Boolean,
        appLockEnabled: Boolean,
        subscriptionUserAgent: String
    ) = viewModelScope.launch {
        repository.setPrivacyOpSecConfig(flagSecureEnabled, excludeFromRecents, appLockEnabled, subscriptionUserAgent)
        _messages.tryEmit("Updated privacy & OpSec settings")
    }

    fun setAdaptiveRoutingConfig(
        adaptiveFallbackEnabled: Boolean,
        failClosedEch: Boolean,
        stallThresholdSec: Int
    ) = viewModelScope.launch {
        repository.setAdaptiveRoutingConfig(adaptiveFallbackEnabled, failClosedEch, stallThresholdSec)
        _messages.tryEmit(
            "Adaptive routing ${if (adaptiveFallbackEnabled) "ON" else "OFF"} • Fail-Closed ECH ${if (failClosedEch) "ON" else "OFF"} (${stallThresholdSec}s)"
        )
    }

    fun clearNetworkStrategies() = viewModelScope.launch {
        repository.clearNetworkStrategies()
        _messages.tryEmit("Cleared learned per-network adaptive strategies")
    }

    fun reprobeOrResetAdaptiveStage() {
        TunnelManager.reprobeOrResetAdaptiveStage(getApplication())
        viewModelScope.launch {
            repository.logEvent(
                level = LogLevel.INFO,
                category = LogCategory.ANTI_DPI,
                message = "Manual adaptive route re-probe / stage reset triggered"
            )
        }
        _messages.tryEmit("Triggered adaptive route re-probe / stage reset")
    }

    fun clearEvents() {
        viewModelScope.launch {
            repository.clearEvents()
            _messages.tryEmit("Cleared event logs")
        }
    }

    private data class AuxUiData(
        val batchProbe: BatchProbeProgress,
        val isSyncing: Boolean,
        val dnsProbeMs: Int?,
        val isTestingDns: Boolean,
        val dnsPoolResult: DnsPoolQueryResult? = null,
        val isBenchmarkingDnsPool: Boolean = false,
        val networkStrategies: List<NetworkStrategyEntity> = emptyList()
    )

    private data class XrayDiagUiData(
        val cleanIpScan: CleanIpScanState,
        val fragmentProbe: FragmentTlsProbeResult?,
        val testingFragment: Boolean,
        val realityProbe: RealityReadinessResult?,
        val testingReality: Boolean,
        val dohProbe: DohProbeResult?,
        val testingDoh: Boolean,
        val echProbe: EchProbeResult?,
        val testingEch: Boolean,
        val warpEndpointScan: WarpEndpointScanState
    )
}
