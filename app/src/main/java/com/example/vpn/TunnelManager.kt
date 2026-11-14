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
    val splitTunnelEngine = SplitTunnelEngine(context)
    val adaptiveRoutingEngine = AdaptiveRoutingEngine(repository)

    private val tunnelMutex = Mutex()
    private val stageReloadMutex = Mutex()
    private val sessionRxBytes = AtomicLong(0L)
    private val sessionTxBytes = AtomicLong(0L)
    private val lastRxSample = AtomicLong(0L)
    private val lastTxSample = AtomicLong(0L)

    private val core = LuxyVpnCore(
        splitTunnelEngine = splitTunnelEngine,
        onTrafficBytes = { rx, tx ->
            sessionRxBytes.addAndGet(rx)
            sessionTxBytes.addAndGet(tx)
        },
        onBlockedEvent = { dest, ruleName ->
            scope.launch {
                val active = _telemetry.value.activeNode
                val safeDest = EncryptedConfigVault.sanitizeLogText(dest, active)
                repository.logEvent(
                    LogLevel.INFO,
                    LogCategory.TUNNEL,
                    "BLOCK [$ruleName] dropped connection to $safeDest"
                )
            }
        }
    )

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
            val msg = coreResult.exceptionOrNull()?.message ?: "Core failed to start"
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.ERROR,
                lastError = msg
            )
            repository.logEvent(LogLevel.ERROR, LogCategory.XRAY_CORE, msg)
            return
        }

        val ping = NetworkProbeEngine.measureNodePing(profile)
        if (ping.success) {
            repository.updateLatency(profile.id, ping.rttMs, ping.jitterMs)
        }

        val startIntent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_START
            putExtra(LuxyVpnService.EXTRA_MTU, report.effectiveSettings.mtu)
            putExtra(LuxyVpnService.EXTRA_NODE_NAME, profile.name)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }

        sessionRxBytes.set(0L)
        sessionTxBytes.set(0L)
        lastRxSample.set(0L)
        lastTxSample.set(0L)
        consecutiveFailures.set(0)

        adaptiveRoutingEngine.syncWithExperimentalMode(settings.experimentalModeEnabled)
        val stageState = adaptiveRoutingEngine.state.value
        _telemetry.value = TunnelTelemetry(
            connectionState = ConnectionState.CONNECTED,
            activeNode = profile,
            connectedSinceMs = System.currentTimeMillis(),
            rxBytes = 0L,
            txBytes = 0L,
            rxBytesPerSec = 0L,
            txBytesPerSec = 0L,
            activeLatencyMs = if (ping.success) ping.rttMs else (profile.latencyMs ?: 48),
            networkTransport = _telemetry.value.networkTransport,
            isFailoverInProgress = false,
            lastError = null,
            activeAdaptiveStage = if (settings.experimentalModeEnabled) stageState.currentStage else 0,
            adaptiveStageLabel = if (settings.experimentalModeEnabled) stageState.stageLabel else "Standard Xray",
            networkFingerprint = stageState.networkFingerprint,
            isAdaptingRoute = false
        )

        adaptiveRoutingEngine.recordHealthyConnection(profile)
        val endpointDesc = EncryptedConfigVault.redactEndpointLabel(profile)
        repository.logEvent(
            LogLevel.INFO,
            LogCategory.TUNNEL,
            "Connected to ${profile.name} ($endpointDesc) via 127.0.0.1:10808",
            latencyMs = if (ping.success) ping.rttMs else null
        )

        startTelemetryTicker()
        startHealthWatchdog()
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

    private fun startTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(1000L)
                if (_telemetry.value.connectionState != ConnectionState.CONNECTED) continue
                val totalRx = sessionRxBytes.get()
                val totalTx = sessionTxBytes.get()
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
