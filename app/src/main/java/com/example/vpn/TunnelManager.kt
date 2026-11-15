package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.core.EncryptedConfigVault
import com.example.core.NetworkProbeEngine
import com.example.core.ProtocolGuardrails
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.ProxyProtocol
import com.example.data.model.TunnelTelemetry
import com.example.data.repository.LuxyVpnRepository
import com.example.xraycore.AdaptiveRoutingEngine
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.XrayBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TunnelManager(
    private val context: Context,
    private val repository: LuxyVpnRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val adaptiveRoutingEngine = AdaptiveRoutingEngine(repository)

    private val tunnelMutex = Mutex()
    private val stageReloadMutex = Mutex()
    @Volatile
    private var pendingLatencyMs: Int? = null
    private val lastRxSample = AtomicLong(0L)
    private val lastTxSample = AtomicLong(0L)

    private val core = LuxyVpnCore(XrayBackend(context))

    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var tickerJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null
    private val consecutiveFailures = AtomicInteger(0)

    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null

    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    fun connect(profile: ServerProfileEntity) {
        scope.launch {
            tunnelMutex.withLock {
                connectLocked(profile)
            }
        }
    }

    private suspend fun connectLocked(profile: ServerProfileEntity) {
        if (EncryptedConfigVault.isVaultExpired(profile)) {
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.ERROR,
                lastError = "Vault profile has expired"
            )
            repository.logEvent(
                LogLevel.ERROR,
                LogCategory.TUNNEL,
                "Rejected expired vault profile: ${profile.name}"
            )
            return
        }

        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.CONNECTING,
            activeNode = profile,
            lastError = null
        )

        val settings = repository.getSettings()
        val parsedProtocol = ProxyProtocol.parse(profile.protocol)
        if (!settings.experimentalModeEnabled && ProtocolGuardrails.isExperimentalProtocol(parsedProtocol)) {
            val msg = "Enable Experimental Mode in Settings to use ${profile.protocol}"
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.ERROR,
                lastError = msg
            )
            repository.logEvent(LogLevel.WARN, LogCategory.TUNNEL, msg)
            return
        }

        val rules = repository.getAllRoutingRules()
        val allProfiles = repository.getAllProfiles()
        val relayNode = if (settings.experimentalModeEnabled) {
            settings.relayNodeId?.let { id -> allProfiles.firstOrNull { it.id == id } }
        } else {
            null
        }
        val report = ProtocolGuardrails.evaluateAndSanitizeSettings(settings, profile)

        val coreResult = core.startCore(profile, report.effectiveSettings, rules, relayNode)
        if (coreResult.isFailure) {
            val msg = coreResult.exceptionOrNull()?.message ?: "Core failed to compile the profile"
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.ERROR,
                lastError = msg
            )
            repository.logEvent(LogLevel.ERROR, LogCategory.XRAY_CORE, msg)
            return
        }
        val configJson = coreResult.getOrThrow()

        val ping = NetworkProbeEngine.measureNodePing(profile)
        if (ping.success) {
            repository.updateLatency(profile.id, ping.rttMs, ping.jitterMs)
        }
        pendingLatencyMs = if (ping.success) ping.rttMs else profile.latencyMs

        // The native core can only start once the TUN descriptor exists; the service owns that
        // step and reports back through onTunnelEstablished()/onTunnelFailed().
        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.CONNECTING,
            activeNode = profile,
            lastError = null
        )

        val startIntent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_START
            putExtra(LuxyVpnService.EXTRA_MTU, report.effectiveSettings.mtu)
            putExtra(LuxyVpnService.EXTRA_NODE_NAME, profile.name)
            putExtra(LuxyVpnService.EXTRA_CONFIG_JSON, configJson)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }

    /**
     * Quick-settings tile / widget entry point: connect to the active node, or disconnect.
     * It refuses instead of guessing when no node or no VPN permission is available, and logs
     * the reason so the tile's failure is visible in the event log rather than silent.
     */
    fun toggleQuickly() {
        scope.launch {
            val state = _telemetry.value.connectionState
            if (state == ConnectionState.CONNECTED ||
                state == ConnectionState.CONNECTING ||
                state == ConnectionState.RECONNECTING ||
                state == ConnectionState.FAILOVER
            ) {
                disconnect("Quick toggle")
                return@launch
            }
            if (!isVpnPermissionGranted()) {
                repository.logEvent(
                    LogLevel.WARN,
                    LogCategory.TUNNEL,
                    "Quick toggle ignored: VPN permission not granted"
                )
                _telemetry.value = _telemetry.value.copy(
                    lastError = "Open LuxyVPN to grant VPN permission"
                )
                return@launch
            }
            val active = resolveActiveProfile()
            if (active == null) {
                repository.logEvent(
                    LogLevel.WARN,
                    LogCategory.TUNNEL,
                    "Quick toggle ignored: no active profile"
                )
                _telemetry.value = _telemetry.value.copy(lastError = "Add or select a server node first")
                return@launch
            }
            tunnelMutex.withLock { connectLocked(active) }
        }
    }

    /**
     * Boot / package-replaced hook. Auto-connect is opt-in and only fires when the VPN
     * permission was already granted — a receiver cannot show the system consent dialog, and
     * silently failing is worse than logging the reason.
     */
    fun autoConnectOnBoot() {
        scope.launch {
            val settings = repository.getSettings()
            if (!settings.autoConnectOnLaunch) return@launch
            if (!isVpnPermissionGranted()) {
                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    "Auto-connect skipped after boot: VPN permission not granted"
                )
                return@launch
            }
            val active = resolveActiveProfile()
            if (active == null) {
                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    "Auto-connect skipped after boot: no active profile"
                )
                return@launch
            }
            repository.logEvent(
                LogLevel.INFO,
                LogCategory.TUNNEL,
                "Auto-connect after boot: ${active.name}"
            )
            tunnelMutex.withLock { connectLocked(active) }
        }
    }

    private suspend fun resolveActiveProfile(): ServerProfileEntity? {
        val settings = repository.getSettings()
        val id = settings.activeProfileId ?: return null
        return repository.getAllProfiles().firstOrNull { it.id == id }
    }

    fun disconnect(reason: String? = null) {
        scope.launch {
            tunnelMutex.withLock {
                tickerJob?.cancel()
                tickerJob = null
                watchdogJob?.cancel()
                watchdogJob = null
                core.stopCore()

                val stopIntent = Intent(context, LuxyVpnService::class.java).apply {
                    action = LuxyVpnService.ACTION_STOP
                }
                try {
                    context.startService(stopIntent)
                } catch (_: Exception) {
                }

                val current = _telemetry.value
                _telemetry.value = TunnelTelemetry(
                    connectionState = ConnectionState.DISCONNECTED,
                    activeNode = current.activeNode,
                    networkTransport = current.networkTransport,
                    activeAdaptiveStage = current.activeAdaptiveStage,
                    adaptiveStageLabel = current.adaptiveStageLabel,
                    networkFingerprint = current.networkFingerprint
                )

                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    reason ?: "VPN Tunnel disconnected"
                )
            }
        }
    }

    fun refreshLiveRulesAndSettings() {
        scope.launch {
            stageReloadMutex.withLock {
                if (!core.isRunning) return@withLock
                val settings = repository.getSettings()
                val rules = repository.getAllRoutingRules()
                val allProfiles = repository.getAllProfiles()
                val active = _telemetry.value.activeNode
                val relayNode = if (settings.experimentalModeEnabled) {
                    settings.relayNodeId?.let { id -> allProfiles.firstOrNull { it.id == id } }
                } else {
                    null
                }
                val report = ProtocolGuardrails.evaluateAndSanitizeSettings(settings, active)
                core.updateRulesAndSettings(active, report.effectiveSettings, rules, relayNode)
                reloadService()
            }
        }
    }

    suspend fun setExperimentalModeEnabled(enabled: Boolean): Boolean = stageReloadMutex.withLock {
        repository.updateSettings { it.copy(experimentalModeEnabled = enabled) }
        val updated = repository.getSettings()
        adaptiveRoutingEngine.syncWithExperimentalMode(enabled)
        val active = _telemetry.value.activeNode

        if (core.isRunning) {
            if (!enabled && active != null && ProtocolGuardrails.isExperimentalProtocol(ProxyProtocol.parse(active.protocol))) {
                disconnect("Experimental Mode disabled — ${active.protocol} requires Experimental Mode")
            } else {
                val rules = repository.getAllRoutingRules()
                val allProfiles = repository.getAllProfiles()
                val relayNode = if (enabled) {
                    updated.relayNodeId?.let { id -> allProfiles.firstOrNull { it.id == id } }
                } else {
                    null
                }
                val report = ProtocolGuardrails.evaluateAndSanitizeSettings(updated, active)
                core.updateRulesAndSettings(active, report.effectiveSettings, rules, relayNode)
                reloadService()
            }
        }

        val stageState = adaptiveRoutingEngine.state.value
        _telemetry.value = _telemetry.value.copy(
            activeAdaptiveStage = if (enabled) stageState.currentStage else 0,
            adaptiveStageLabel = if (enabled) stageState.stageLabel else "Standard Xray",
            isAdaptingRoute = false
        )
        repository.logEvent(
            LogLevel.INFO,
            LogCategory.ANTI_DPI,
            if (enabled) "Experimental Mode enabled" else "Experimental Mode disabled — running Standard Xray baseline"
        )
        enabled
    }

    suspend fun hotReloadAdaptiveStage(stage: Int): Int = stageReloadMutex.withLock {
        val current = repository.getSettings()
        if (!current.experimentalModeEnabled) {
            adaptiveRoutingEngine.syncWithExperimentalMode(false)
            _telemetry.value = _telemetry.value.copy(
                activeAdaptiveStage = 0,
                adaptiveStageLabel = "Standard Xray",
                isAdaptingRoute = false
            )
            return@withLock 0
        }
        val active = _telemetry.value.activeNode
        val (nextStage, stageSettings) = adaptiveRoutingEngine.setStage(stage, current)
        val report = ProtocolGuardrails.evaluateAndSanitizeSettings(stageSettings, active)
        repository.updateSettings { report.effectiveSettings.copy(experimentalModeEnabled = true) }
        if (core.isRunning) {
            val rules = repository.getAllRoutingRules()
            val allProfiles = repository.getAllProfiles()
            val relayNode = report.effectiveSettings.relayNodeId?.let { id -> allProfiles.firstOrNull { it.id == id } }
            core.updateRulesAndSettings(active, report.effectiveSettings, rules, relayNode)
                reloadService()
        }
        val stageState = adaptiveRoutingEngine.state.value
        _telemetry.value = _telemetry.value.copy(
            activeAdaptiveStage = nextStage,
            adaptiveStageLabel = stageState.stageLabel,
            isAdaptingRoute = false
        )
        repository.logEvent(
            LogLevel.INFO,
            LogCategory.ANTI_DPI,
            "Adaptive stage switched to ${stageState.stageLabel}"
        )
        nextStage
    }

    fun onTransportChanged(newTransport: String) {
        scope.launch {
            val prev = _telemetry.value.networkTransport
            val settings = repository.getSettings()
            adaptiveRoutingEngine.onNetworkChanged(
                transport = newTransport,
                experimentalModeEnabled = settings.experimentalModeEnabled
            )
            val stageState = adaptiveRoutingEngine.state.value
            _telemetry.value = _telemetry.value.copy(
                networkTransport = newTransport,
                activeAdaptiveStage = if (settings.experimentalModeEnabled) stageState.currentStage else 0,
                adaptiveStageLabel = if (settings.experimentalModeEnabled) stageState.stageLabel else "Standard Xray",
                networkFingerprint = stageState.networkFingerprint
            )
            if (prev != newTransport && _telemetry.value.connectionState == ConnectionState.CONNECTED) {
                val settings = repository.getSettings()
                if (settings.autoReconnect) {
                    repository.logEvent(
                        LogLevel.RECOVERY,
                        LogCategory.HANDOFF,
                        "Network switched ($prev → $newTransport); tunnel re-bound seamlessly"
                    )
                }
            }
        }
    }

    /** Starts the native core against the established TUN descriptor. */
    fun attachTun(tunFd: Int): Result<Unit> = core.attachTun(tunFd)

    fun stopCore() = core.stopCore()

    /** Applies a freshly compiled config to the running core (called by the service on reload). */
    fun reloadCore(configJson: String, tunFd: Int) {
        scope.launch {
            stageReloadMutex.withLock {
                core.stopCore()
                core.applyConfig(configJson)
                core.attachTun(tunFd)
                    .onSuccess {
                        repository.logEvent(
                            LogLevel.INFO,
                            LogCategory.XRAY_CORE,
                            "Core config reloaded (${core.backendLabel})"
                        )
                    }
                    .onFailure { onTunnelFailed(it.message ?: "Core reload failed") }
            }
        }
    }

    private fun reloadService() {
        if (!core.isRealCore) return
        val intent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_RELOAD
            putExtra(LuxyVpnService.EXTRA_CONFIG_JSON, core.lastCompiledConfigJson)
        }
        runCatching { context.startService(intent) }
    }

    /** Called by [LuxyVpnService] once the core is running behind the TUN device. */
    fun onTunnelEstablished() {
        scope.launch {
            tunnelMutex.withLock {
                val current = _telemetry.value
                if (current.connectionState != ConnectionState.CONNECTING) return@withLock
                val profile = current.activeNode ?: return@withLock
                lastRxSample.set(0L)
                lastTxSample.set(0L)
                consecutiveFailures.set(0)
                val settings = repository.getSettings()
                adaptiveRoutingEngine.syncWithExperimentalMode(settings.experimentalModeEnabled)
                val stageState = adaptiveRoutingEngine.state.value
                _telemetry.value = current.copy(
                    connectionState = ConnectionState.CONNECTED,
                    connectedSinceMs = System.currentTimeMillis(),
                    rxBytes = 0L,
                    txBytes = 0L,
                    rxBytesPerSec = 0L,
                    txBytesPerSec = 0L,
                    activeLatencyMs = pendingLatencyMs ?: profile.latencyMs ?: 48,
                    isFailoverInProgress = false,
                    lastError = null,
                    activeAdaptiveStage = if (settings.experimentalModeEnabled) stageState.currentStage else 0,
                    adaptiveStageLabel = if (settings.experimentalModeEnabled) stageState.stageLabel else "Standard Xray",
                    networkFingerprint = stageState.networkFingerprint,
                    isAdaptingRoute = false
                )
                adaptiveRoutingEngine.recordHealthyConnection(profile)
                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    "Connected to ${profile.name} (${EncryptedConfigVault.redactEndpointLabel(profile)}) via ${core.backendLabel}",
                    latencyMs = pendingLatencyMs
                )
                startTelemetryTicker()
                startHealthWatchdog()
            }
        }
    }

    /** Called by [LuxyVpnService] when the TUN or the core could not be started. */
    fun onTunnelFailed(message: String) {
        scope.launch {
            tickerJob?.cancel()
            tickerJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            core.stopCore()
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.ERROR,
                lastError = message
            )
            repository.logEvent(LogLevel.ERROR, LogCategory.XRAY_CORE, "Tunnel failed: $message")
        }
    }

    private fun startTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(1000L)
                if (_telemetry.value.connectionState != ConnectionState.CONNECTED) continue
                val (totalRx, totalTx) = core.trafficTotals()
                val prevRx = lastRxSample.getAndSet(totalRx)
                val prevTx = lastTxSample.getAndSet(totalTx)
                val rxRate = (totalRx - prevRx).coerceAtLeast(0L)
                val txRate = (totalTx - prevTx).coerceAtLeast(0L)
                _telemetry.value = _telemetry.value.copy(
                    rxBytes = totalRx,
                    txBytes = totalTx,
                    rxBytesPerSec = rxRate,
                    txBytesPerSec = txRate
                )
            }
        }
    }

    private fun startHealthWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                val settings = repository.getSettings()
                delay(settings.probeIntervalSec.coerceIn(5, 120) * 1000L)
                val active = _telemetry.value.activeNode ?: continue
                if (_telemetry.value.connectionState != ConnectionState.CONNECTED) continue
                val probe = NetworkProbeEngine.measureNodePing(active)
                if (probe.success) {
                    consecutiveFailures.set(0)
                    repository.updateLatency(active.id, probe.rttMs, probe.jitterMs)
                    _telemetry.value = _telemetry.value.copy(activeLatencyMs = probe.rttMs)
                } else {
                    val failures = consecutiveFailures.incrementAndGet()
                    val effective = settings.asEffectiveBaseline()
                    if (effective.experimentalModeEnabled && effective.adaptiveFallbackEnabled && failures >= 2) {
                        stageReloadMutex.withLock {
                            val latestSettings = repository.getSettings()
                            if (!latestSettings.experimentalModeEnabled) return@withLock
                            val (nextStage, adjusted) = adaptiveRoutingEngine.escalateStage(latestSettings)
                            val report = ProtocolGuardrails.evaluateAndSanitizeSettings(adjusted, active)
                            repository.updateSettings { report.effectiveSettings.copy(experimentalModeEnabled = true) }
                            if (core.isRunning) {
                                val relayNode = report.effectiveSettings.relayNodeId?.let { id ->
                                    repository.getAllProfiles().firstOrNull { it.id == id }
                                }
                                core.updateRulesAndSettings(active, report.effectiveSettings, repository.getAllRoutingRules(), relayNode)
                            }
                            _telemetry.value = _telemetry.value.copy(
                                activeAdaptiveStage = nextStage,
                                adaptiveStageLabel = adaptiveRoutingEngine.state.value.stageLabel
                            )
                            repository.logEvent(
                                LogLevel.WARN,
                                LogCategory.ANTI_DPI,
                                "Health watchdog escalated to ${adaptiveRoutingEngine.state.value.stageLabel}"
                            )
                        }
                    }
                    if (settings.smartFailover && failures >= 3) {
                        attemptFailover(active)
                    }
                }
            }
        }
    }

    private suspend fun attemptFailover(failedNode: ServerProfileEntity) {
        tunnelMutex.withLock {
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.FAILOVER,
                isFailoverInProgress = true
            )
            val candidates = repository.getAllProfiles()
                .filter { it.id != failedNode.id && !EncryptedConfigVault.isVaultExpired(it) }
                .sortedBy { it.latencyMs ?: Int.MAX_VALUE }
            val next = candidates.firstOrNull()
            if (next != null) {
                repository.setActiveProfile(next.id)
                repository.logEvent(
                    LogLevel.RECOVERY,
                    LogCategory.FAILOVER,
                    "Smart Auto-Failover switching active node to ${next.name}"
                )
                connectLocked(next)
            } else {
                _telemetry.value = _telemetry.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    isFailoverInProgress = false
                )
            }
        }
    }
}
