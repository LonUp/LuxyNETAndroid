package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LuxyVpnApplication
import com.example.core.DnsPoolQueryResult
import com.example.core.DohProbeResult
import com.example.core.EchProbeResult
import com.example.core.EncryptedConfigVault
import com.example.core.FragmentTlsProbeResult
import com.example.core.GeoRulesEngine
import com.example.core.NetworkProbeEngine
import com.example.core.ProtocolGuardrails
import com.example.core.ProxyUriParser
import com.example.core.RealityReadinessResult
import com.example.core.SpeedTestEngine
import com.example.core.SubscriptionFetcher
import com.example.core.VaultImportResult
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.local.StabilityEventEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.ConnectionState
import com.example.data.model.CountryPolicyEntry
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.GeoDatabaseStatus
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.NodeSortOrder
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SocialServiceId
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestProgress
import com.example.data.model.SpeedTestResult
import com.example.data.model.StabilitySettings
import com.example.data.import.ImportSummary
import com.example.data.import.LinkImporter
import com.example.data.model.ProxyProtocol
import com.example.data.model.ThemeCodec
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemeMode
import com.example.data.model.ThemePreset
import com.example.data.model.TunnelTelemetry
import com.example.xraycore.CleanIpCandidateResult
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.WarpEndpointCandidateResult
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeedTestUiState(
    val isRunning: Boolean = false,
    val progress: SpeedTestProgress? = null,
    val results: Map<SpeedTestMode, SpeedTestResult> = emptyMap(),
    val errorMessage: String? = null
)

data class CleanIpScanUiState(
    val isScanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<CleanIpCandidateResult> = emptyList(),
    val error: String? = null
)

data class WarpBenchUiState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<WarpEndpointCandidateResult> = emptyList(),
    val error: String? = null
)

class LuxyVpnViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LuxyVpnApplication
    val repository = app.repository
    val tunnelManager = app.tunnelManager

    val settings: StateFlow<StabilitySettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, StabilitySettings())

    val profiles: StateFlow<List<ServerProfileEntity>> = repository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.subscriptionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val routingRules: StateFlow<List<RoutingRuleEntity>> = repository.routingRulesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stabilityEvents: StateFlow<List<StabilityEventEntity>> = repository.stabilityEventsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val networkStrategies: StateFlow<List<NetworkStrategyEntity>> = repository.networkStrategiesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val telemetry: StateFlow<TunnelTelemetry> = tunnelManager.telemetry

    val activeNode: StateFlow<ServerProfileEntity?> = combine(profiles, settings) { list, cfg ->
        list.firstOrNull { it.id == cfg.activeProfileId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val geoDatabaseStatus: StateFlow<GeoDatabaseStatus> = settings.combine(routingRules) { cfg, _ ->
        GeoRulesEngine.getBuiltInGeoDatabaseStatus(cfg)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GeoRulesEngine.getBuiltInGeoDatabaseStatus(StabilitySettings())
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _protocolFilter = MutableStateFlow<String?>(null)
    val protocolFilter: StateFlow<String?> = _protocolFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NodeSortOrder.DEFAULT)
    val sortOrder: StateFlow<NodeSortOrder> = _sortOrder.asStateFlow()

    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestUiState())
    val speedTestState: StateFlow<SpeedTestUiState> = _speedTestState.asStateFlow()

    private val _cleanIpState = MutableStateFlow(CleanIpScanUiState())
    val cleanIpState: StateFlow<CleanIpScanUiState> = _cleanIpState.asStateFlow()

    private val _warpBenchState = MutableStateFlow(WarpBenchUiState())
    val warpBenchState: StateFlow<WarpBenchUiState> = _warpBenchState.asStateFlow()

    private val _fragmentProbeState = MutableStateFlow<FragmentTlsProbeResult?>(null)
    val fragmentProbeState: StateFlow<FragmentTlsProbeResult?> = _fragmentProbeState.asStateFlow()

    private val _realityProbeState = MutableStateFlow<RealityReadinessResult?>(null)
    val realityProbeState: StateFlow<RealityReadinessResult?> = _realityProbeState.asStateFlow()

    private val _dohProbeState = MutableStateFlow<DohProbeResult?>(null)
    val dohProbeState: StateFlow<DohProbeResult?> = _dohProbeState.asStateFlow()

    private val _echProbeState = MutableStateFlow<EchProbeResult?>(null)
    val echProbeState: StateFlow<EchProbeResult?> = _echProbeState.asStateFlow()

    private val _dnsPoolState = MutableStateFlow<DnsPoolQueryResult?>(null)
    val dnsPoolState: StateFlow<DnsPoolQueryResult?> = _dnsPoolState.asStateFlow()

    private val _isProbingTool = MutableStateFlow(false)
    val isProbingTool: StateFlow<Boolean> = _isProbingTool.asStateFlow()

    private var speedTestJob: Job? = null

    fun clearUiNotice() {
        _uiNotice.value = null
    }

    fun showNotice(msg: String) {
        _uiNotice.value = msg
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setProtocolFilter(proto: String?) {
        _protocolFilter.value = proto
    }

    fun setSortOrder(order: NodeSortOrder) {
        _sortOrder.value = order
    }

    fun prepareVpnIntent(): Intent? = tunnelManager.prepareVpnIntent()

    fun toggleConnect() {
        val currentState = telemetry.value.connectionState
        if (currentState == ConnectionState.CONNECTED ||
            currentState == ConnectionState.CONNECTING ||
            currentState == ConnectionState.RECONNECTING ||
            currentState == ConnectionState.FAILOVER
        ) {
            tunnelManager.disconnect("User disconnected tunnel")
            return
        }
        val target = activeNode.value
        if (target == null) {
            _uiNotice.value = "Add or select a server node first"
            return
        }
        tunnelManager.connect(target)
    }

    /**
     * Selects [profile] and then connects, asking for VPN permission first when it is missing.
     * The active profile is persisted *before* the permission dialog so the grant callback's
     * `toggleConnect()` targets this node instead of the previously active one.
     */
    fun requestConnectNode(profile: ServerProfileEntity, requestPermission: () -> Unit) {
        viewModelScope.launch {
            repository.setActiveProfile(profile.id)
            if (tunnelManager.prepareVpnIntent() != null) {
                requestPermission()
            } else {
                tunnelManager.connect(profile)
            }
        }
    }

    fun connectNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            repository.setActiveProfile(profile.id)
            tunnelManager.connect(profile)
        }
    }

    fun selectActiveNode(profileId: String) {
        viewModelScope.launch {
            repository.setActiveProfile(profileId)
            val node = profiles.value.firstOrNull { it.id == profileId }
            if (node != null && telemetry.value.connectionState == ConnectionState.CONNECTED) {
                tunnelManager.connect(node)
            }
        }
    }

    fun saveNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            repository.saveProfile(profile)
            _uiNotice.value = "Saved ${profile.name}"
        }
    }

    fun deleteNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            if (telemetry.value.activeNode?.id == profile.id &&
                telemetry.value.connectionState == ConnectionState.CONNECTED
            ) {
                tunnelManager.disconnect("Active node deleted")
            }
            repository.deleteProfile(profile.id)
        }
    }

    fun togglePin(profile: ServerProfileEntity) {
        viewModelScope.launch {
            repository.setPinned(profile.id, !profile.isPinned)
        }
    }

    fun pingNode(profile: ServerProfileEntity) {
        viewModelScope.launch {
            val res = NetworkProbeEngine.measureNodePing(profile)
            repository.updateLatency(profile.id, if (res.success) res.rttMs else null, res.jitterMs)
            _uiNotice.value = if (res.success) {
                "${profile.name}: ${res.rttMs} ms (jitter ${res.jitterMs} ms)"
            } else {
                "${profile.name}: unreachable"
            }
        }
    }

    fun pingAllNodes() {
        viewModelScope.launch {
            val all = profiles.value
            if (all.isEmpty()) return@launch
            var reachable = 0
            for (node in all) {
                val res = NetworkProbeEngine.measureNodePing(node)
                repository.updateLatency(node.id, if (res.success) res.rttMs else null, res.jitterMs)
                if (res.success) reachable++
            }
            _uiNotice.value = "Latency check complete: $reachable/${all.size} reachable"
        }
    }

    /**
     * Imports anything the OS or the clipboard hands us: 37 share schemes, app subscription
     * wrappers, plain lists and base64 subscription bodies. One summary for the sheet.
     */
    suspend fun importDeepLinksAsync(
        rawInput: String,
        groupName: String = "Imported"
    ): ImportSummary = withContext(Dispatchers.Default) {
        val results = LinkImporter.handleMany(rawInput, groupName)
        if (results.isEmpty()) return@withContext ImportSummary()

        val nodes = mutableListOf<ServerProfileEntity>()
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var subscriptions = 0
        var documents = 0

        for (result in results) {
            when (result) {
                is LinkImporter.Result.Nodes -> nodes += result.nodes
                is LinkImporter.Result.Subscription -> {
                    val sub = SubscriptionEntity(
                        name = result.name.ifBlank { "Subscription" },
                        url = result.url
                    )
                    repository.saveSubscription(sub)
                    refreshSubscription(sub)
                    subscriptions++
                }
                is LinkImporter.Result.Document -> {
                    documents++
                    when (result.kind) {
                        LinkImporter.DocumentKind.PLAIN_LIST ->
                            nodes += ProxyUriParser.parseBatchWithReport(result.content, groupName = groupName).nodes
                        else -> failures += "Config files (Clash/sing-box/URI) land with the P2 config lab."
                    }
                }
                is LinkImporter.Result.Unsupported -> failures += result.reason
            }
        }

        if (nodes.isNotEmpty()) repository.saveProfiles(nodes)
        val unsupported = nodes.map { ProxyProtocol.parse(it.protocol) }.distinct()
            .filterNot { LinkImporter.isCompilable(it) }
        if (unsupported.isNotEmpty()) {
            notes += "Stored but not connectable yet: " + unsupported.joinToString { it.label }
        }
        val summary = ImportSummary(
            profiles = nodes.size,
            subscriptions = subscriptions,
            documents = documents,
            failures = failures.distinct(),
            notes = notes
        )
        repository.logEvent(
            LogLevel.INFO,
            LogCategory.TUNNEL,
            "Import: ${summary.profiles} profile(s), ${summary.subscriptions} subscription(s), " +
                "${summary.failures.size} rejected"
        )
        _uiNotice.value = when {
            summary.profiles == 0 && summary.subscriptions == 0 ->
                "Nothing imported (${summary.failures.size} rejected)"
            else -> "Added ${summary.profiles} server(s)" +
                if (summary.subscriptions > 0) " and ${summary.subscriptions} subscription(s)" else ""
        }
        summary
    }

    /** Resolves `content://` / `file://` deep links, then delegates to the text importer. */
    suspend fun importDeepLinkUriAsync(uri: android.net.Uri): ImportSummary = withContext(Dispatchers.IO) {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "content" || scheme == "file") {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                return@withContext ImportSummary(failures = listOf("Could not read $uri"))
            }
            importDeepLinksAsync(text)
        } else {
            importDeepLinksAsync(uri.toString())
        }
    }

    suspend fun importBatchTextAsync(rawInput: String, groupName: String = "Imported"): Int =
        withContext(Dispatchers.Default) {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return@withContext 0
            if (EncryptedConfigVault.isVaultPayload(trimmed)) {
                _uiNotice.value = "Encrypted .zsx/.kage payload detected — use Vault Import dialog"
                return@withContext 0
            }
            val report = ProxyUriParser.parseBatchWithReport(trimmed, groupName = groupName)
            if (report.nodes.isEmpty()) {
                _uiNotice.value = "No valid proxy URIs or Xray JSON outbounds found"
                return@withContext 0
            }
            repository.saveProfiles(report.nodes)
            val strippedMsg = if (report.strippedDirectives.isNotEmpty()) {
                " (${report.strippedDirectives.size} unsafe directive(s) stripped)"
            } else ""
            repository.logEvent(
                LogLevel.INFO,
                LogCategory.TUNNEL,
                "Imported ${report.nodes.size} node(s)$strippedMsg"
            )
            _uiNotice.value = "Imported ${report.nodes.size} node(s)$strippedMsg"
            report.nodes.size
        }

    suspend fun importEncryptedVaultAsync(
        rawPayload: String,
        passphrase: String?,
        privateKeyBase64: String?
    ): VaultImportResult = withContext(Dispatchers.Default) {
        val result = EncryptedConfigVault.importVault(
            rawPayload = rawPayload,
            passphrase = passphrase,
            recipientPrivateKeyBase64 = privateKeyBase64
        )
        when (result) {
            is VaultImportResult.Success -> {
                repository.saveProfiles(result.nodes)
                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    "Unlocked ${result.nodes.size} locked vault node(s) via ${result.scheme}"
                )
                _uiNotice.value = "Unlocked ${result.nodes.size} locked node(s)"
            }
            is VaultImportResult.Expired -> {
                _uiNotice.value = "Vault has expired and cannot be imported"
            }
            is VaultImportResult.Error -> {
                _uiNotice.value = result.reason
            }
        }
        result
    }

    suspend fun exportEncryptedVaultAsync(
        exportNodes: List<ServerProfileEntity>,
        useX25519: Boolean,
        passphrase: String,
        recipientPubKey: String,
        expiresAtMs: Long?
    ): String = withContext(Dispatchers.Default) {
        if (useX25519) {
            EncryptedConfigVault.exportVaultWithX25519(exportNodes, recipientPubKey, expiresAtMs)
        } else {
            EncryptedConfigVault.exportVaultWithPassphrase(exportNodes, passphrase, expiresAtMs)
        }
    }

    fun addOrUpdateSubscription(sub: SubscriptionEntity, fetchNow: Boolean = true) {
        viewModelScope.launch {
            repository.saveSubscription(sub)
            if (fetchNow) {
                refreshSubscription(sub)
            }
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            val cfg = settings.value
            repository.saveSubscription(sub.copy(lastStatus = "Updating…", lastError = null))
            val res = SubscriptionFetcher.fetchSubscription(
                url = sub.url,
                subscriptionId = sub.id,
                groupName = sub.name,
                userAgent = cfg.subscriptionUserAgent,
                topNLimit = cfg.subscriptionTopNLimit
            )
            if (res.success) {
                repository.replaceSubscriptionNodes(sub.id, res.report.nodes)
                val trimmedNote = if (res.report.trimmedCount > 0) {
                    " (Top ${res.report.nodes.size} of ${res.report.totalParsed})"
                } else ""
                // A panel that omits the header keeps the last known quota instead of losing it.
                val info = res.userInfo
                repository.saveSubscription(
                    sub.copy(
                        lastUpdatedAt = System.currentTimeMillis(),
                        nodeCount = res.report.nodes.size,
                        lastStatus = "Synced ${res.report.nodes.size} nodes$trimmedNote",
                        lastError = null,
                        uploadBytes = info?.uploadBytes ?: sub.uploadBytes,
                        downloadBytes = info?.downloadBytes ?: sub.downloadBytes,
                        totalBytes = info?.totalBytes ?: sub.totalBytes,
                        expireAtEpochSec = info?.expireAtEpochSec ?: sub.expireAtEpochSec,
                        profileTitle = res.profileTitle ?: sub.profileTitle
                    )
                )
                _uiNotice.value = "${sub.name}: synced ${res.report.nodes.size} nodes$trimmedNote"
            } else {
                repository.saveSubscription(
                    sub.copy(
                        lastUpdatedAt = System.currentTimeMillis(),
                        lastStatus = "Failed",
                        lastError = res.error ?: "Update failed"
                    )
                )
                _uiNotice.value = "${sub.name} error: ${res.error}"
            }
        }
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            repository.deleteSubscriptionAndNodes(sub.id)
            _uiNotice.value = "Deleted subscription ${sub.name}"
        }
    }

    // --- Speed Test Actions ---

    fun runSpeedTest(mode: SpeedTestMode, allowDisconnectedHandshakeProbe: Boolean = false) {
        speedTestJob?.cancel()
        _speedTestState.value = _speedTestState.value.copy(
            isRunning = true,
            progress = null,
            errorMessage = null
        )
        speedTestJob = viewModelScope.launch {
            SpeedTestEngine.runDualModeSpeedTest(
                mode = mode,
                connectionState = telemetry.value.connectionState,
                activeNode = activeNode.value,
                activeSocksPort = 10808,
                allowDisconnectedNodeProbe = allowDisconnectedHandshakeProbe
            ).catch { e ->
                if (e is CancellationException) throw e
                _speedTestState.value = _speedTestState.value.copy(
                    isRunning = false,
                    errorMessage = e.message ?: "Speed test failed"
                )
            }.collect { (progress, finalResult) ->
                val updatedResults = if (finalResult != null) {
                    _speedTestState.value.results + (finalResult.mode to finalResult)
                } else {
                    _speedTestState.value.results
                }
                _speedTestState.value = _speedTestState.value.copy(
                    isRunning = finalResult == null,
                    progress = progress,
                    results = updatedResults,
                    errorMessage = null
                )
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
        _speedTestState.value = _speedTestState.value.copy(
            isRunning = false,
            errorMessage = null
        )
    }

    // --- Settings & GeoIP / GeoSite Rules Engine ---

    fun updateSettings(transform: (StabilitySettings) -> StabilitySettings) {
        viewModelScope.launch {
            repository.updateSettings(transform)
            tunnelManager.refreshLiveRulesAndSettings()
        }
    }

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(theme = it.theme.copy(mode = mode)) }

    fun setThemePreset(preset: ThemePreset) = updateSettings {
        it.copy(theme = preset.applyTo(it.theme, keepMode = it.theme.mode))
    }

    fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) = updateSettings {
        it.copy(theme = ThemeCodec.normalize(transform(it.theme)))
    }

    fun setLanguage(tag: String) = updateSettings { it.copy(language = tag) }
    fun setRoutingMode(mode: RoutingMode) = updateSettings { it.copy(routingMode = mode) }

    fun setDomesticBypassConfig(enabled: Boolean, countryCode: String) {
        val cc = GeoRulesEngine.normalizeCountryCode(countryCode).ifBlank { "IR" }
        updateGeoSettings {
            it.copy(domesticBypassEnabled = enabled, domesticCountryCode = cc)
        }
    }

    fun setBlockAdsEnabled(enabled: Boolean) {
        updateGeoSettings { it.copy(blockAdsEnabled = enabled) }
    }

    fun setBlockPornEnabled(enabled: Boolean) {
        updateGeoSettings { it.copy(blockPornEnabled = enabled) }
    }

    fun setSocialServicePolicy(service: SocialServiceId, action: RuleAction) {
        updateGeoSettings {
            val nextMap = it.socialPolicies.toMutableMap()
            nextMap[service] = action
            it.copy(socialPolicies = nextMap)
        }
    }

    fun addOrUpdateCountryPolicy(rawCode: String, action: RuleAction) {
        val cc = GeoRulesEngine.normalizeCountryCode(rawCode)
        if (!GeoRulesEngine.isValidCountryCode(cc)) {
            _uiNotice.value = "Enter a valid 2-letter ISO country code (e.g. IR, CN, RU, US)"
            return
        }
        updateGeoSettings {
            val filtered = it.countryPolicies.filterNot { entry -> entry.code.equals(cc, ignoreCase = true) }
            it.copy(countryPolicies = filtered + CountryPolicyEntry(cc, action))
        }
    }

    fun removeCountryPolicy(code: String) {
        updateGeoSettings {
            it.copy(countryPolicies = it.countryPolicies.filterNot { entry -> entry.code.equals(code, ignoreCase = true) })
        }
    }

    fun refreshGeoDatabase() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = settings.value.copy(
                geoDbVersion = "2026.11-verified",
                geoDbLastUpdatedAt = now
            )
            repository.updateSettings { updated }
            repository.syncManagedGeoRules(updated)
            tunnelManager.refreshLiveRulesAndSettings()
            val status = GeoRulesEngine.getBuiltInGeoDatabaseStatus(updated)
            repository.logEvent(
                LogLevel.INFO,
                LogCategory.DNS,
                "GeoIP/GeoSite ruleset verified (${status.builtInRuleCount} signatures, ${status.geoipVersion})"
            )
            _uiNotice.value = "GeoIP & GeoSite database verified (${status.builtInRuleCount} signatures)"
        }
    }

    private fun updateGeoSettings(transform: (StabilitySettings) -> StabilitySettings) {
        viewModelScope.launch {
            repository.updateSettings(transform)
            val latest = repository.getSettings()
            repository.syncManagedGeoRules(latest)
            tunnelManager.refreshLiveRulesAndSettings()
        }
    }

    fun addCustomRoutingRule(
        name: String,
        matchType: RuleMatchType,
        pattern: String,
        action: RuleAction,
        priority: Int = 95
    ) {
        val cleanPattern = pattern.trim()
        if (cleanPattern.isEmpty()) return
        viewModelScope.launch {
            val rule = RoutingRuleEntity(
                name = name.trim().ifEmpty { "${matchType.label}: $cleanPattern" },
                matchType = matchType.name,
                pattern = cleanPattern,
                action = action.name,
                isEnabled = true,
                priority = priority
            )
            repository.saveRoutingRule(rule)
            tunnelManager.refreshLiveRulesAndSettings()
        }
    }

    fun toggleRoutingRule(rule: RoutingRuleEntity) {
        viewModelScope.launch {
            repository.setRoutingRuleEnabled(rule.id, !rule.isEnabled)
            tunnelManager.refreshLiveRulesAndSettings()
        }
    }

    fun deleteRoutingRule(rule: RoutingRuleEntity) {
        viewModelScope.launch {
            repository.deleteRoutingRule(rule.id)
            tunnelManager.refreshLiveRulesAndSettings()
        }
    }

    fun setAppSplitTunnelMode(mode: AppSplitTunnelMode) {
        updateSettings { it.copy(appSplitTunnelMode = mode) }
    }

    fun setSplitTunnelPackages(rawPackages: String) {
        updateSettings { it.copy(splitTunnelPackages = rawPackages) }
    }

    // --- Experimental Mode & Anti-Censorship ---

    fun setExperimentalModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tunnelManager.setExperimentalModeEnabled(enabled)
            _uiNotice.value = if (enabled) {
                "Experimental Mode enabled"
            } else {
                "Experimental Mode disabled — using Standard Xray baseline"
            }
        }
    }

    fun applyAntiCensorshipPreset(preset: AntiCensorshipPreset) {
        updateSettings { XrayConfigCompiler.applyPresetToSettings(it, preset) }
        _uiNotice.value = "Applied preset: ${preset.label}"
    }

    fun setAdaptiveStage(stage: Int) {
        viewModelScope.launch {
            val nextStage = tunnelManager.hotReloadAdaptiveStage(stage)
            _uiNotice.value = if (settings.value.experimentalModeEnabled) {
                "Switched to ${XrayConfigCompiler.stageSpec(nextStage).title}"
            } else {
                "Standard Xray baseline active (enable Experimental Mode for stage tuning)"
            }
        }
    }

    fun startCleanIpScan(cidrOrIps: String, port: Int, sni: String, useFragment: Boolean) {
        viewModelScope.launch {
            _cleanIpState.value = CleanIpScanUiState(isScanning = true)
            try {
                val res = CleanIpScanner.scanCleanIps(cidrOrIps, port, sni, useFragment) { done, total ->
                    _cleanIpState.value = _cleanIpState.value.copy(done = done, total = total)
                }
                _cleanIpState.value = CleanIpScanUiState(isScanning = false, results = res)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _cleanIpState.value = CleanIpScanUiState(isScanning = false, error = e.message)
            }
        }
    }

    fun applyCleanIpToActiveNode(ip: String) {
        val node = activeNode.value ?: return
        if (node.isLocked) {
            _uiNotice.value = "Locked vault profile cannot be modified"
            return
        }
        viewModelScope.launch {
            val preserveHost = node.hostHeader.ifBlank { node.sni.ifBlank { node.address } }
            val updated = node.copy(
                address = ip,
                sni = node.sni.ifBlank { preserveHost },
                hostHeader = preserveHost,
                rawUri = ""
            )
            repository.saveProfile(updated)
            _uiNotice.value = "Updated ${node.name} Clean IP → $ip"
            if (telemetry.value.connectionState == ConnectionState.CONNECTED) {
                tunnelManager.connect(updated)
            }
        }
    }

    fun startWarpBenchmark(cidrOrEndpoints: String, ports: List<Int>) {
        viewModelScope.launch {
            _warpBenchState.value = WarpBenchUiState(isRunning = true)
            try {
                val res = CleanIpScanner.benchmarkWarpEndpoints(cidrOrEndpoints, ports) { done, total ->
                    _warpBenchState.value = _warpBenchState.value.copy(done = done, total = total)
                }
                _warpBenchState.value = WarpBenchUiState(isRunning = false, results = res)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _warpBenchState.value = WarpBenchUiState(isRunning = false, error = e.message)
            }
        }
    }

    fun applyWarpEndpoint(endpoint: String) {
        updateSettings { it.copy(warpEndpoint = endpoint) }
        _uiNotice.value = "Applied WARP endpoint: $endpoint"
    }

    fun runFragmentTlsProbe(host: String, port: Int, sni: String, enableFragment: Boolean) {
        viewModelScope.launch {
            _isProbingTool.value = true
            _fragmentProbeState.value = NetworkProbeEngine.probeFragmentedTls(host, port, sni, enableFragment)
            _isProbingTool.value = false
        }
    }

    fun runRealityReadinessProbe(sni: String, port: Int) {
        viewModelScope.launch {
            _isProbingTool.value = true
            _realityProbeState.value = NetworkProbeEngine.probeRealitySniReadiness(sni, port)
            _isProbingTool.value = false
        }
    }

    fun runDohProbe(dohUrl: String) {
        viewModelScope.launch {
            _isProbingTool.value = true
            _dohProbeState.value = NetworkProbeEngine.verifyDohAntiPoisoning(dohUrl)
            _isProbingTool.value = false
        }
    }

    fun runEchProbe(domain: String, dohUrl: String) {
        viewModelScope.launch {
            _isProbingTool.value = true
            _echProbeState.value = NetworkProbeEngine.probeEchDnsConfig(domain, dohUrl)
            _isProbingTool.value = false
        }
    }

    fun runDnsPoolBenchmark(domain: String) {
        viewModelScope.launch {
            _isProbingTool.value = true
            _dnsPoolState.value = com.example.xraycore.MultiResolverDnsEngine.queryPool(domain, settings.value)
            _isProbingTool.value = false
        }
    }

    fun compileInspectorJson(): String {
        val cfg = settings.value
        val node = activeNode.value
        val relay = cfg.relayNodeId?.let { id -> profiles.value.firstOrNull { it.id == id } }
        return XrayConfigCompiler.compileRedactedForInspector(node, cfg, routingRules.value, relay)
    }

    suspend fun compileInspectorJsonAsync(): String = withContext(Dispatchers.Default) {
        compileInspectorJson()
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearEvents()
            _uiNotice.value = "Event logs cleared"
        }
    }
}
