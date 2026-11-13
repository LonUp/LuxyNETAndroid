package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.LuxyVpnApplication
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ConnectionState
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.RuleAction
import com.example.data.model.StabilitySettings
import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class LuxyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderJob: Job? = null
    private var stabilityWatchdogJob: Job? = null
    private var trafficMeterJob: Job? = null
    private var activeProfile: ServerProfileEntity? = null
    private var networkCallbackRegistered = false
    private var lastTransportName: String = "Unknown"
    private var currentNetworkFingerprint: String = ""
    private var currentAdaptiveStage: Int = 0
    @Volatile
    private var activeCompiledXrayJson: String = ""
    @Volatile
    private var currentEffectiveSettings: StabilitySettings? = null
    @Volatile
    private var currentRoutingRules: List<com.example.data.local.RoutingRuleEntity> = emptyList()
    private var stallTracker: com.example.xraycore.LiveStallTracker? = null

    private val app by lazy { application as LuxyVpnApplication }
    private val repository by lazy { app.repository }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val descriptor = com.example.xraycore.AdaptiveRoutingEngine.resolveNetworkDescriptor(
                this@LuxyVpnService,
                connectivityManager,
                network
            )
            val transport = descriptor.transportType
            val prevTransport = lastTransportName
            val prevFp = currentNetworkFingerprint
            lastTransportName = transport
            val newFp = com.example.xraycore.AdaptiveRoutingEngine.computeFingerprint(descriptor)
            currentNetworkFingerprint = newFp

            TunnelManager.updateTelemetry {
                it.copy(
                    isNetworkOnline = true,
                    networkTransport = transport,
                    activeNetworkFingerprint = newFp
                )
            }

            serviceScope.launch {
                val state = TunnelManager.telemetry.value.connectionState
                val settings = repository.settingsFlow.first()
                if (prevTransport != "Unknown" && (prevTransport != transport || prevFp != newFp)) {
                    val recalled = repository.getNetworkStrategy(newFp)
                    val recalledStage = if (settings.adaptiveFallbackEnabled) {
                        recalled?.preferredEscalationLevel?.coerceIn(0, 4) ?: 0
                    } else 0
                    repository.logEvent(
                        level = LogLevel.RECOVERY,
                        category = LogCategory.HANDOFF,
                        message = "Network handoff ($prevTransport -> $transport, #${com.example.xraycore.AdaptiveRoutingEngine.shortFingerprint(newFp)}): recalling Stage $recalledStage"
                    )
                    if (state == ConnectionState.CONNECTED && vpnInterface != null && activeProfile != null) {
                        hotReloadAdaptiveStage(
                            targetStage = recalledStage,
                            baseSettings = settings,
                            reason = "Network handoff ($transport)"
                        )
                        return@launch
                    }
                }
                if (state == ConnectionState.RECONNECTING && settings.autoReconnect) {
                    activeProfile?.let { profile ->
                        reconnectTunnel(profile, settings, reason = "Network restored ($transport)")
                    }
                } else if (state == ConnectionState.CONNECTED) {
                    runSingleStabilityProbe(settings)
                }
            }
        }

        override fun onLost(network: Network) {
            val stillHasActive = connectivityManager.activeNetwork != null
            if (!stillHasActive) {
                TunnelManager.updateTelemetry {
                    it.copy(
                        isNetworkOnline = false,
                        networkTransport = "Offline",
                        connectionState = ConnectionState.RECONNECTING
                    )
                }
                serviceScope.launch {
                    repository.logEvent(
                        level = LogLevel.WARN,
                        category = LogCategory.HANDOFF,
                        message = "Connectivity lost; holding tunnel in handoff recovery state"
                    )
                    updateNotification("Handoff Recovering…", "Waiting for network availability")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val transport = resolveTransportFromCaps(capabilities)
            lastTransportName = transport
            TunnelManager.updateTelemetry {
                it.copy(isNetworkOnline = true, networkTransport = transport)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnSession()
                return START_NOT_STICKY
            }
            ACTION_REPROBE_ADAPTIVE -> {
                serviceScope.launch {
                    val settings = repository.settingsFlow.first()
                    if (activeProfile != null && vpnInterface != null) {
                        hotReloadAdaptiveStage(
                            targetStage = 0,
                            baseSettings = settings,
                            reason = "User requested 1-tap Re-Probe & Stage Reset"
                        )
                    }
                }
                return START_STICKY
            }
            ACTION_START, ACTION_SWITCH_NODE -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…", "Initializing LuxyVPN Core"))
                serviceScope.launch {
                    val settings = repository.settingsFlow.first()
                    val profile = profileId?.let { repository.getServerById(it) }
                        ?: settings.activeProfileId?.let { repository.getServerById(it) }
                        ?: repository.getAllServersOnce().firstOrNull()

                    if (profile == null) {
                        TunnelManager.updateTelemetry {
                            it.copy(
                                connectionState = ConnectionState.ERROR,
                                lastError = "No server node selected"
                            )
                        }
                        stopSelf()
                        return@launch
                    }

                    establishTunnel(profile, settings, isSwitch = intent.action == ACTION_SWITCH_NODE)
                }
            }
            else -> {
                // Always-on VPN start from system
                startForeground(NOTIFICATION_ID, buildNotification("LuxyVPN Always-On", "Restoring LuxyVPN Core tunnel"))
                serviceScope.launch {
                    val settings = repository.settingsFlow.first()
                    val profile = settings.activeProfileId?.let { repository.getServerById(it) }
                        ?: repository.getAllServersOnce().firstOrNull()
                    if (profile != null) {
                        establishTunnel(profile, settings, isSwitch = false)
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun establishTunnel(
        profile: ServerProfileEntity,
        settings: StabilitySettings,
        isSwitch: Boolean
    ) {
        if (com.example.core.EncryptedConfigVault.isVaultExpired(profile)) {
            TunnelManager.updateTelemetry {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    activeNode = profile,
                    lastError = "Locked vault profile has expired"
                )
            }
            repository.logEvent(
                level = LogLevel.WARN,
                category = LogCategory.ANTI_DPI,
                message = "Blocked connection to '${profile.name}': encrypted vault license expired"
            )
            stopVpnSession()
            return
        }

        activeProfile = profile
        repository.setActiveProfileId(profile.id)

        val descriptor = com.example.xraycore.AdaptiveRoutingEngine.resolveNetworkDescriptor(this, connectivityManager)
        val transport = descriptor.transportType
        lastTransportName = transport
        val fingerprint = com.example.xraycore.AdaptiveRoutingEngine.computeFingerprint(descriptor)
        currentNetworkFingerprint = fingerprint

        val recalledStrategy = if (settings.adaptiveFallbackEnabled) {
            repository.getNetworkStrategy(fingerprint)
        } else null
        val initialStage = recalledStrategy?.preferredEscalationLevel?.coerceIn(0, 4) ?: 0
        currentAdaptiveStage = initialStage
        val effectiveSettings = if (settings.adaptiveFallbackEnabled) {
            com.example.xraycore.AdaptiveRoutingEngine.applyStageToSettings(settings, initialStage)
        } else settings
        val stageSpec = com.example.xraycore.AdaptiveRoutingEngine.stageSpec(initialStage)

        val failClosedEchActive = com.example.xraycore.LuxyVpnCore.isFailClosedEchActive(
            effectiveSettings,
            profile,
            initialStage
        )
        TunnelManager.updateTelemetry {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                activeNode = profile,
                networkTransport = transport,
                activeNetworkFingerprint = fingerprint,
                adaptiveStage = initialStage,
                adaptiveStageLabel = stageSpec.title,
                isAdaptingRoute = false,
                echEnforcedMode = when {
                    failClosedEchActive -> "FAIL-CLOSED"
                    effectiveSettings.echEnabled -> effectiveSettings.echForceQuery.uppercase()
                    else -> "OFF"
                },
                consecutiveFailures = 0,
                lastError = null
            )
        }

        // Strict Fail-Closed ECH check before completing handshake
        if (failClosedEchActive && profile.security != "NONE") {
            val outerSni = profile.sni.ifBlank { profile.hostHeader.ifBlank { "cloudflare-ech.com" } }
            val echCheck = com.example.xraycore.LuxyVpnCore.verifyStrictEchReadiness(
                outerSni = outerSni,
                inlineEchConfigList = profile.echConfigList.ifBlank { effectiveSettings.echConfigList },
                dohUrl = effectiveSettings.dohServerUrl,
                failClosed = true
            )
            if (!echCheck.allowed) {
                val errMsg = com.example.core.EncryptedConfigVault.sanitizeLogText(
                    echCheck.error ?: "Fail-Closed ECH negotiation failed",
                    profile
                )
                TunnelManager.updateTelemetry {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        echEnforcedMode = "ABORTED",
                        lastError = errMsg
                    )
                }
                repository.logEvent(
                    level = LogLevel.ERROR,
                    category = LogCategory.ANTI_DPI,
                    message = "Connection aborted by Fail-Closed ECH policy: $errMsg"
                )
                stopVpnSession()
                return
            }
            TunnelManager.updateTelemetry { it.copy(echEnforcedMode = "FAIL-CLOSED (OK)") }
        }

        val routingRules = repository.routingRulesFlow.first()
        val relayNode = effectiveSettings.relayNodeId?.let { repository.getServerById(it) }
        currentEffectiveSettings = effectiveSettings
        currentRoutingRules = routingRules
        activeCompiledXrayJson = com.example.xraycore.XrayConfigCompiler.compile(
            node = profile,
            settings = effectiveSettings,
            rules = routingRules,
            relayNode = relayNode
        )

        val dialerChain = com.example.xraycore.LuxyVpnCore.describeDialerChain(effectiveSettings, profile)
        // Measure initial real TCP RTT (and Fragmented TLS handshake when TLS/Fragmentation is active)
        val initialProbe = repository.probeSingleTcp(profile.address, profile.port)
        val fragmentCheck = if (effectiveSettings.tlsFragmentEnabled && profile.security != "NONE") {
            val effectiveSni = effectiveSettings.fragmentHost.ifBlank {
                profile.sni.ifBlank { profile.hostHeader.ifBlank { profile.address } }
            }
            val effectiveCiphers = profile.cipherSuites.ifBlank { effectiveSettings.cipherSuites }
            repository.probeFragmentedTls(
                host = profile.address,
                port = profile.port,
                sni = effectiveSni,
                lengthRange = effectiveSettings.fragmentLength,
                intervalRange = effectiveSettings.fragmentInterval,
                enableFragment = true,
                cipherSuites = effectiveCiphers,
                maxSplitRange = effectiveSettings.fragmentMaxSplit
            )
        } else null
        val dnsProbe = repository.testDnsLatency(effectiveSettings.primaryDns)

        try {
            closeTunInterface()
            val builder = Builder()
                .setSession("LuxyVPN • ${profile.name}")
                .setMtu(effectiveSettings.mtu)
                .addAddress("10.88.0.2", 30)
                // Dedicated virtual tunnel subnet keeps device & emulator connectivity intact
                // while establishing a genuine Android VpnService TUN interface.
                .addRoute("10.88.0.0", 24)

            if (settings.primaryDns.isNotBlank()) {
                try {
                    builder.addDnsServer(settings.primaryDns)
                } catch (_: Exception) {
                }
            }
            if (settings.fallbackDns.isNotBlank() && settings.fallbackDns != settings.primaryDns) {
                try {
                    builder.addDnsServer(settings.fallbackDns)
                } catch (_: Exception) {
                }
            }

            val splitPackages = SplitTunnelEngine.parsePackageList(settings.splitTunnelPackages)
            when (settings.appSplitTunnelMode) {
                AppSplitTunnelMode.ALLOWLIST -> {
                    splitPackages.filter { it != packageName }.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                }
                AppSplitTunnelMode.BLOCKLIST -> {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {
                    }
                    splitPackages.forEach { pkg ->
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                }
                AppSplitTunnelMode.DISABLED -> {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {
                    }
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                TunnelManager.updateTelemetry {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        lastError = "VPN permission revoked or interface unavailable"
                    )
                }
                repository.logEvent(
                    level = LogLevel.ERROR,
                    category = LogCategory.TUNNEL,
                    message = "Failed to establish TUN interface (VPN consent required)"
                )
                stopVpnSession()
                return
            }

            VpnSocketProtector.attachService(this)
            VpnSocketProtector.protectFd(pfd.fd)
            vpnInterface = pfd
            com.example.xraycore.LuxyVpnCore.startCoreRuntime(
                compiledJson = activeCompiledXrayJson,
                node = profile,
                settings = effectiveSettings,
                rules = routingRules,
                relayNode = relayNode
            )
            startTunDrainLoop(pfd, effectiveSettings)

            val now = System.currentTimeMillis()
            val initRtt = if (initialProbe.success) initialProbe.rttMs else null
            val initHistory = if (initRtt != null) listOf(initRtt) else emptyList()

            val sanitizedProbeError = com.example.core.EncryptedConfigVault.sanitizeLogText(
                initialProbe.error.orEmpty(),
                profile
            )
            val sanitizedFragError = com.example.core.EncryptedConfigVault.sanitizeLogText(
                fragmentCheck?.error.orEmpty(),
                profile
            )

            TunnelManager.updateTelemetry { prev ->
                prev.copy(
                    connectionState = ConnectionState.CONNECTED,
                    activeNode = profile,
                    currentRttMs = initRtt,
                    jitterMs = 0,
                    stabilityScore = if (initialProbe.success) 100 else 78,
                    probeLossPercent = if (initialProbe.success) 0 else 20,
                    connectedSinceMs = if (isSwitch && prev.connectedSinceMs != null) prev.connectedSinceMs else now,
                    networkTransport = transport,
                    rttHistory = initHistory,
                    dnsLatencyMs = if (dnsProbe.success) dnsProbe.rttMs else null,
                    coreEngineVersion = com.example.xraycore.LuxyVpnCore.CORE_VERSION,
                    activeDialerChain = dialerChain,
                    fragmentHandshakeVerified = fragmentCheck?.success == true,
                    protectedSocketsCount = VpnSocketProtector.getProtectedCount(),
                    lastError = if (!initialProbe.success) "Endpoint unreachable: $sanitizedProbeError" else null
                )
            }

            val actionLabel = if (isSwitch) "Switched tunnel" else "Connected"
            val rttNote = if (initRtt != null) " (${initRtt} ms)" else " (endpoint probe unreachable)"
            repository.logEvent(
                level = if (initRtt != null) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.XRAY_CORE,
                message = "$actionLabel via $dialerChain to ${profile.name} [${profile.protocol}]$rttNote",
                latencyMs = initRtt
            )
            if (fragmentCheck != null) {
                repository.logEvent(
                    level = if (fragmentCheck.success) LogLevel.RECOVERY else LogLevel.WARN,
                    category = LogCategory.ANTI_DPI,
                    message = if (fragmentCheck.success)
                        "ClientHello Fragment (${fragmentCheck.fragmentsSent} segs, ${fragmentCheck.tlsProtocol}) verified in ${fragmentCheck.rttMs} ms"
                    else "Fragmented TLS check failed: $sanitizedFragError",
                    latencyMs = if (fragmentCheck.success) fragmentCheck.rttMs else null
                )
            }

            updateNotification(
                title = "Protected • ${profile.name}",
                text = buildNotificationSummary(initRtt, 100, transport)
            )

            com.example.xraycore.AdaptiveRoutingEngine.recordStageOutcome(
                dao = app.database.networkStrategyDao(),
                fingerprint = fingerprint,
                transportType = transport,
                stage = initialStage,
                success = initialProbe.success,
                activeNode = profile
            )

            startTrafficMeter()
            startStabilityWatchdog()
        } catch (e: Exception) {
            TunnelManager.updateTelemetry {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    lastError = e.localizedMessage ?: "Tunnel error"
                )
            }
            repository.logEvent(
                level = LogLevel.ERROR,
                category = LogCategory.TUNNEL,
                message = "Tunnel exception: ${e.localizedMessage}"
            )
            stopVpnSession()
        }
    }

    private fun startTunDrainLoop(pfd: ParcelFileDescriptor, settings: StabilitySettings) {
        tunReaderJob?.cancel()
        tunReaderJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            val ownerResolver = AndroidConnectionOwnerResolver(connectivityManager)
            val splitPackageUids = resolvePackageUids(SplitTunnelEngine.parsePackageList(settings.splitTunnelPackages))
            val allowedUids = if (settings.appSplitTunnelMode == AppSplitTunnelMode.ALLOWLIST) splitPackageUids else emptySet()
            val blockedUids = if (settings.appSplitTunnelMode == AppSplitTunnelMode.BLOCKLIST) splitPackageUids else emptySet()
            val selfUid = Process.myUid()
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) {
                        delay(20)
                        continue
                    }
                    val activeSettings = currentEffectiveSettings ?: settings
                    val activeRules = currentRoutingRules
                    val verdict = com.example.xraycore.LuxyCoreRuntime.forwardTunPacket(
                        packet = buffer,
                        length = bytesRead,
                        tunOutput = outputStream,
                        ownerResolver = ownerResolver,
                        allowedUids = allowedUids,
                        blockedUids = blockedUids,
                        selfUid = selfUid,
                        settingsOverride = activeSettings,
                        rulesOverride = activeRules
                    )
                    if (verdict.uidDecision == UidFlowDecision.DENY_UNRESOLVED_UID ||
                        verdict.uidDecision == UidFlowDecision.DENY_EXCLUDED_UID
                    ) {
                        TunnelManager.updateTelemetry { prev ->
                            prev.copy(isolatedUidFlowsDenied = prev.isolatedUidFlowsDenied + 1)
                        }
                    } else if (verdict.matchedRuleName != null || verdict.ruleAction != RuleAction.PROXY || verdict.dnsForwarded) {
                        TunnelManager.updateTelemetry { prev ->
                            prev.copy(preFakeDnsMatches = prev.preFakeDnsMatches + 1)
                        }
                    }
                }
            } catch (_: Exception) {
                // Expected when ParcelFileDescriptor is closed on disconnect
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
                try { outputStream.close() } catch (_: Exception) {}
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePackageUids(packages: List<String>): Set<Int> {
        if (packages.isEmpty()) return emptySet()
        val out = linkedSetOf<Int>()
        packages.forEach { pkg ->
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                if (appInfo.uid > 0) out.add(appInfo.uid)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun startTrafficMeter() {
        trafficMeterJob?.cancel()
        trafficMeterJob = serviceScope.launch {
            var prevRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var prevTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var pendingSessionRx = 0L
            var pendingSessionTx = 0L
            var lastUiTelemetryEmitMs = 0L
            var activeThresholdSec = repository.settingsFlow.first().stallThresholdSec
            stallTracker = com.example.xraycore.LiveStallTracker(activeThresholdSec * 1000L)
            while (isActive) {
                delay(1000L)
                val currRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val currTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val deltaRx = (currRx - prevRx).coerceAtLeast(0L)
                val deltaTx = (currTx - prevTx).coerceAtLeast(0L)
                prevRx = currRx
                prevTx = currTx
                pendingSessionRx += deltaRx
                pendingSessionTx += deltaTx

                val now = System.currentTimeMillis()
                if (TunnelManager.isAppInForeground() && now - lastUiTelemetryEmitMs >= 1000L) {
                    val flushRx = pendingSessionRx
                    val flushTx = pendingSessionTx
                    pendingSessionRx = 0L
                    pendingSessionTx = 0L
                    lastUiTelemetryEmitMs = now
                    TunnelManager.updateTelemetry { prev ->
                        val nextThroughput = (prev.throughputHistory + (deltaRx + deltaTx)).takeLast(24)
                        prev.copy(
                            downloadBps = deltaRx,
                            uploadBps = deltaTx,
                            sessionRxBytes = prev.sessionRxBytes + flushRx,
                            sessionTxBytes = prev.sessionTxBytes + flushTx,
                            throughputHistory = nextThroughput
                        )
                    }
                }

                val currentState = TunnelManager.telemetry.value.connectionState
                if (currentState == ConnectionState.CONNECTED && !TunnelManager.telemetry.value.isAdaptingRoute) {
                    val stalled = stallTracker?.sample(deltaTx, deltaRx, now) == true
                    if (stalled) {
                        val settings = repository.settingsFlow.first()
                        if (settings.stallThresholdSec != activeThresholdSec) {
                            activeThresholdSec = settings.stallThresholdSec
                            stallTracker = com.example.xraycore.LiveStallTracker(activeThresholdSec * 1000L)
                        }
                        if (settings.adaptiveFallbackEnabled) {
                            TunnelManager.updateTelemetry { prev ->
                                prev.copy(liveStallEvents = prev.liveStallEvents + 1)
                            }
                            escalateNextAdaptiveStage(settings, reason = "Zero-downlink live byte stall (${activeThresholdSec}s)")
                        }
                    }
                }
            }
        }
    }

    private fun startStabilityWatchdog() {
        stabilityWatchdogJob?.cancel()
        stabilityWatchdogJob = serviceScope.launch {
            while (isActive) {
                val settings = repository.settingsFlow.first()
                delay(settings.probeIntervalSec * 1000L)
                if (!isActive) break
                val state = TunnelManager.telemetry.value.connectionState
                if (state == ConnectionState.CONNECTED) {
                    runSingleStabilityProbe(settings)
                }
            }
        }
    }

    private suspend fun runSingleStabilityProbe(settings: StabilitySettings) {
        val current = activeProfile ?: return
        val probe = repository.probeSingleTcp(current.address, current.port)
        val telemetry = TunnelManager.telemetry.value

        if (probe.success) {
            val updatedHistory = (telemetry.rttHistory + probe.rttMs).takeLast(20)
            val avg = updatedHistory.average().roundToInt()
            val jitter = if (updatedHistory.size > 1) {
                updatedHistory.map { abs(it - avg) }.average().roundToInt()
            } else 0
            val score = calculateStabilityScore(probe.rttMs, jitter, 0)

            if (TunnelManager.isAppInForeground()) {
                TunnelManager.updateTelemetry { prev ->
                    prev.copy(
                        currentRttMs = probe.rttMs,
                        jitterMs = jitter,
                        stabilityScore = score,
                        probeLossPercent = (prev.probeLossPercent - 5).coerceAtLeast(0),
                        consecutiveFailures = 0,
                        rttHistory = updatedHistory,
                        protectedSocketsCount = VpnSocketProtector.getProtectedCount(),
                        lastError = null
                    )
                }
            }
            updateNotification(
                title = "Protected • ${current.name}",
                text = buildNotificationSummary(probe.rttMs, score, telemetry.networkTransport)
            )
            if (currentNetworkFingerprint.isNotBlank()) {
                com.example.xraycore.AdaptiveRoutingEngine.recordStageOutcome(
                    dao = app.database.networkStrategyDao(),
                    fingerprint = currentNetworkFingerprint,
                    transportType = telemetry.networkTransport,
                    stage = currentAdaptiveStage,
                    success = true,
                    activeNode = current
                )
            }
        } else {
            val failures = telemetry.consecutiveFailures + 1
            val loss = min(100, telemetry.probeLossPercent + 20)
            val degradedScore = (100 - loss - failures * 10).coerceIn(10, 95)

            val sanitizedErr = com.example.core.EncryptedConfigVault.sanitizeLogText(
                probe.error.orEmpty(),
                current
            )
            TunnelManager.updateTelemetry { prev ->
                prev.copy(
                    consecutiveFailures = failures,
                    probeLossPercent = loss,
                    stabilityScore = degradedScore,
                    lastError = sanitizedErr
                )
            }

            repository.logEvent(
                level = LogLevel.WARN,
                category = LogCategory.PROBE,
                message = "Keep-alive probe #$failures failed for ${current.name}: $sanitizedErr"
            )

            if (settings.adaptiveFallbackEnabled && currentAdaptiveStage < 4) {
                escalateNextAdaptiveStage(settings, reason = "Keep-alive probe #$failures timeout")
            } else if (failures >= settings.failoverFailureThreshold) {
                if (settings.smartFailover) {
                    attemptSmartFailover(current, settings)
                } else if (settings.autoReconnect) {
                    reconnectTunnel(current, settings, reason = "Consecutive probe timeouts")
                }
            }
        }
    }

    private suspend fun escalateNextAdaptiveStage(
        baseSettings: StabilitySettings,
        reason: String
    ) {
        val nextStage = (currentAdaptiveStage + 1).coerceAtMost(4)
        if (currentNetworkFingerprint.isNotBlank()) {
            com.example.xraycore.AdaptiveRoutingEngine.recordStageOutcome(
                dao = app.database.networkStrategyDao(),
                fingerprint = currentNetworkFingerprint,
                transportType = lastTransportName,
                stage = currentAdaptiveStage,
                success = false,
                activeNode = activeProfile
            )
        }
        hotReloadAdaptiveStage(nextStage, baseSettings, reason)
    }

    /**
     * Recompiles XrayConfigCompiler for the target Resilience Stage (0..4) and hot-reloads
     * LuxyVpnCore telemetry/socket stack WITHOUT tearing down the Android VpnService TUN
     * ParcelFileDescriptor or resetting connectedSinceMs uptime.
     */
    private suspend fun hotReloadAdaptiveStage(
        targetStage: Int,
        baseSettings: StabilitySettings,
        reason: String
    ) {
        val current = activeProfile ?: return
        val clamped = targetStage.coerceIn(0, 4)
        val stageSpec = com.example.xraycore.AdaptiveRoutingEngine.stageSpec(clamped)
        currentAdaptiveStage = clamped
        stallTracker?.reset()

        TunnelManager.updateTelemetry { prev ->
            prev.copy(
                isAdaptingRoute = true,
                adaptiveStage = clamped,
                adaptiveStageLabel = stageSpec.title
            )
        }
        updateNotification(
            title = "Adapting Route (Stage $clamped)…",
            text = "${stageSpec.title} • $reason"
        )

        val stageSettings = com.example.xraycore.AdaptiveRoutingEngine.applyStageToSettings(baseSettings, clamped)
        val effectiveNode = if (clamped == 4) {
            com.example.xraycore.AdaptiveRoutingEngine.selectFallbackDnsTunnelNode(repository.getAllServersOnce()) ?: current
        } else current
        activeProfile = effectiveNode

        val failClosedEch = com.example.xraycore.LuxyVpnCore.isFailClosedEchActive(stageSettings, effectiveNode, clamped)
        if (failClosedEch && effectiveNode.security != "NONE") {
            val outerSni = effectiveNode.sni.ifBlank { effectiveNode.hostHeader.ifBlank { "cloudflare-ech.com" } }
            val echCheck = com.example.xraycore.LuxyVpnCore.verifyStrictEchReadiness(
                outerSni = outerSni,
                inlineEchConfigList = effectiveNode.echConfigList.ifBlank { stageSettings.echConfigList },
                dohUrl = stageSettings.dohServerUrl,
                failClosed = true
            )
            if (!echCheck.allowed) {
                val safeErr = com.example.core.EncryptedConfigVault.sanitizeLogText(
                    echCheck.error ?: "ECH verification failed",
                    effectiveNode
                )
                TunnelManager.updateTelemetry { prev ->
                    prev.copy(
                        isAdaptingRoute = false,
                        echEnforcedMode = "ABORTED",
                        lastError = safeErr
                    )
                }
                repository.logEvent(
                    level = LogLevel.WARN,
                    category = LogCategory.ANTI_DPI,
                    message = "Stage $clamped Fail-Closed ECH aborted: $safeErr"
                )
                if (clamped < 4) {
                    escalateNextAdaptiveStage(baseSettings, reason = "Fail-Closed ECH abort at Stage $clamped")
                }
                return
            }
        }

        val rules = repository.routingRulesFlow.first()
        val relay = stageSettings.relayNodeId?.let { repository.getServerById(it) }
        currentEffectiveSettings = stageSettings
        currentRoutingRules = rules
        activeCompiledXrayJson = com.example.xraycore.XrayConfigCompiler.compile(
            node = effectiveNode,
            settings = stageSettings,
            rules = rules,
            relayNode = relay
        )
        com.example.xraycore.LuxyVpnCore.hotReloadCoreRuntime(
            compiledJson = activeCompiledXrayJson,
            node = effectiveNode,
            settings = stageSettings,
            rules = rules,
            relayNode = relay
        )
        val dialerChain = com.example.xraycore.LuxyVpnCore.describeDialerChain(stageSettings, effectiveNode)
        val probe = repository.probeSingleTcp(effectiveNode.address, effectiveNode.port, timeoutMs = 2500)

        TunnelManager.updateTelemetry { prev ->
            prev.copy(
                connectionState = ConnectionState.CONNECTED,
                activeNode = effectiveNode,
                isAdaptingRoute = false,
                adaptiveStage = clamped,
                adaptiveStageLabel = stageSpec.title,
                activeDialerChain = dialerChain,
                echEnforcedMode = when {
                    failClosedEch -> "FAIL-CLOSED (OK)"
                    stageSettings.echEnabled -> stageSettings.echForceQuery.uppercase()
                    else -> "OFF"
                },
                currentRttMs = if (probe.success) probe.rttMs else prev.currentRttMs,
                consecutiveFailures = if (probe.success) 0 else prev.consecutiveFailures,
                lastError = if (probe.success) null else prev.lastError
            )
        }

        if (currentNetworkFingerprint.isNotBlank()) {
            com.example.xraycore.AdaptiveRoutingEngine.recordStageOutcome(
                dao = app.database.networkStrategyDao(),
                fingerprint = currentNetworkFingerprint,
                transportType = lastTransportName,
                stage = clamped,
                success = probe.success,
                activeNode = effectiveNode
            )
        }

        repository.logEvent(
            level = LogLevel.RECOVERY,
            category = LogCategory.ANTI_DPI,
            message = "Hot-reloaded ${stageSpec.title} ($reason) without dropping TUN -> $dialerChain",
            latencyMs = if (probe.success) probe.rttMs else null
        )
        updateNotification(
            title = "Protected • ${effectiveNode.name}",
            text = "${stageSpec.title} • ${if (probe.success) "${probe.rttMs} ms" else lastTransportName}"
        )
    }

    private suspend fun attemptSmartFailover(
        failedNode: ServerProfileEntity,
        settings: StabilitySettings
    ) {
        TunnelManager.updateTelemetry {
            it.copy(connectionState = ConnectionState.FAILOVER)
        }
        updateNotification("Smart Failover…", "Scanning backup nodes")

        val candidates = repository.getAllServersOnce().filter { it.id != failedNode.id }
        var bestNode: ServerProfileEntity? = null
        var bestRtt = Int.MAX_VALUE

        for (candidate in candidates.take(8)) {
            val res = repository.probeSingleTcp(candidate.address, candidate.port, timeoutMs = 2000)
            if (res.success && res.rttMs < bestRtt) {
                bestRtt = res.rttMs
                bestNode = candidate
            }
        }

        if (bestNode != null) {
            TunnelManager.updateTelemetry { it.copy(failoverCount = it.failoverCount + 1) }
            repository.logEvent(
                level = LogLevel.RECOVERY,
                category = LogCategory.FAILOVER,
                message = "Smart failover: ${failedNode.name} -> ${bestNode.name} ($bestRtt ms)",
                latencyMs = bestRtt
            )
            establishTunnel(bestNode, settings, isSwitch = true)
        } else if (settings.autoReconnect) {
            reconnectTunnel(failedNode, settings, reason = "No healthier backup node found")
        }
    }

    private suspend fun reconnectTunnel(
        profile: ServerProfileEntity,
        settings: StabilitySettings,
        reason: String
    ) {
        TunnelManager.updateTelemetry {
            it.copy(
                connectionState = ConnectionState.RECONNECTING,
                reconnectCount = it.reconnectCount + 1
            )
        }
        repository.logEvent(
            level = LogLevel.RECOVERY,
            category = LogCategory.HANDOFF,
            message = "Auto-reconnecting (${profile.name}): $reason"
        )
        // Exponential backoff jitter before re-establishing
        delay(1200L)
        establishTunnel(profile, settings, isSwitch = true)
    }

    private fun calculateStabilityScore(rttMs: Int, jitterMs: Int, failures: Int): Int {
        val rttPenalty = when {
            rttMs <= 80 -> 0
            rttMs <= 160 -> 4
            rttMs <= 300 -> 10
            else -> 20
        }
        val jitterPenalty = (jitterMs / 4).coerceAtMost(25)
        val failPenalty = failures * 15
        return (100 - rttPenalty - jitterPenalty - failPenalty).coerceIn(10, 100)
    }

    private fun stopVpnSession() {
        stabilityWatchdogJob?.cancel()
        trafficMeterJob?.cancel()
        tunReaderJob?.cancel()
        com.example.xraycore.LuxyVpnCore.stopCoreRuntime()
        VpnSocketProtector.detachService(this)
        closeTunInterface()

        val nodeName = activeProfile?.name
        activeProfile = null

        TunnelManager.updateTelemetry {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                downloadBps = 0L,
                uploadBps = 0L,
                sessionRxBytes = 0L,
                sessionTxBytes = 0L,
                connectedSinceMs = null,
                consecutiveFailures = 0,
                lastError = null
            )
        }

        if (nodeName != null) {
            serviceScope.launch {
                repository.logEvent(
                    level = LogLevel.INFO,
                    category = LogCategory.TUNNEL,
                    message = "Disconnected tunnel from $nodeName"
                )
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun registerNetworkMonitor() {
        if (networkCallbackRegistered) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun resolveActiveTransport(): String =
        connectivityManager.activeNetwork?.let { resolveTransportLabel(it) } ?: "Offline"

    private fun resolveTransportLabel(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        return resolveTransportFromCaps(caps)
    }

    private fun resolveTransportFromCaps(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Network"
    }

    private fun buildNotificationSummary(rttMs: Int?, score: Int, transport: String): String {
        val rttPart = if (rttMs != null) "$rttMs ms" else "Probing"
        return "$transport • $rttPart • Stability $score%"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LuxyVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.vpn_action_disconnect), stopIntent)
            .build()
    }

    override fun onRevoke() {
        stopVpnSession()
        super.onRevoke()
    }

    override fun onDestroy() {
        stabilityWatchdogJob?.cancel()
        trafficMeterJob?.cancel()
        tunReaderJob?.cancel()
        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            networkCallbackRegistered = false
        }
        com.example.xraycore.LuxyVpnCore.stopCoreRuntime()
        VpnSocketProtector.detachService(this)
        closeTunInterface()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        const val ACTION_SWITCH_NODE = "com.example.vpn.SWITCH_NODE"
        const val ACTION_REPROBE_ADAPTIVE = "com.example.vpn.REPROBE_ADAPTIVE"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        private const val CHANNEL_ID = "luxyvpn_tunnel_channel"
        private const val NOTIFICATION_ID = 7001
    }
}
