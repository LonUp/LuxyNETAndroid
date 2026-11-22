package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.example.core.AutomaticServerSelector
import com.example.core.ClientPolicy
import com.example.core.ConfigurationHealthTracker
import com.example.core.ErrorClassifier
import com.example.core.ConfigFingerprint
import com.example.core.ExitCountryVerifier
import com.example.core.GeoIpDatabase
import com.example.core.NetworkFingerprintProvider
import com.example.core.ServerCountryResolver
import com.example.core.SensitiveDataRedactor
import com.example.core.TunnelLeakChecker
import com.example.core.VpnPosture
import com.example.core.VpnPostureInspector
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.ErrorCategory
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.MeasurementType
import com.example.data.model.TunnelTelemetry
import com.example.data.model.VerificationState
import com.example.data.repository.LuxyVpnRepository
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.TunnelConnectivityCheck
import com.example.xraycore.XrayBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class TunnelManager internal constructor(
    private val context: Context,
    private val repository: LuxyVpnRepository,
    private val connectivityCheck: TunnelConnectivityCheck = TunnelConnectivityCheck(),
    private val exitCountryVerifier: ExitCountryVerifier = ExitCountryVerifier(
        geoIpLookup = { ip -> GeoIpDatabase.getInstance(context)?.lookupCountry(ip) }
    ),
    private val networkFingerprintProvider: () -> String = { NetworkFingerprintProvider.current(context) },
    private val leakChecker: TunnelLeakChecker = TunnelLeakChecker(),
    private val networkMonitorFactory: (Context) -> NetworkMonitor = { NetworkMonitor(it) },
    private val trafficStatsProvider: (() -> Pair<Long, Long>)? = null,
    private val recoveryDelayMs: (Int) -> Long = { attempt ->
        1_000L * (1L shl (attempt - 1).coerceIn(0, 2))
    },
    private val vpnPostureProvider: () -> VpnPosture? = { VpnPostureInspector.inspect(context) }
) {
    constructor(context: Context, repository: LuxyVpnRepository) : this(
        context = context,
        repository = repository,
        connectivityCheck = TunnelConnectivityCheck()
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()
    private val connectionRequests = AtomicLong(0L)
    private val attempts = AtomicLong(0L)
    @Volatile private var lastHandledFailureAttempt = 0L
    private val core = LuxyVpnCore(XrayBackend(context) { attempt ->
        onTunnelFailed(attempt, "VPN core stopped")
    })
    private val trafficStats: () -> Pair<Long, Long> = trafficStatsProvider ?: { core.queryTrafficStats() }
    private val networkMonitor = networkMonitorFactory(context)
    private var candidates: List<ServerProfileEntity> = emptyList()
    private var candidateIndex = 0
    private var lastConnectCandidates: List<ServerProfileEntity> = emptyList()
    private var recoveryCandidates: List<ServerProfileEntity> = emptyList()
    private val recoveryAttemptedIds = HashSet<String>()
    private var recoveryCurrentProfile: ServerProfileEntity? = null
    private var recoveryCurrentRetryPending = false
    private var recoveryAttempts = 0
    private var recoveryStartedAtMs = 0L
    private var lastRecoveryCategory = ErrorCategory.UNKNOWN
    private var lastRecoveryUserMessage = ""
    private var lastRecoveryTechnicalDetail: String? = null
    private var lastRecoveryOriginalMessage = ""
    private var lastTrafficRx = 0L
    private var lastTrafficTx = 0L
    @Volatile private var lastSelectedCountryCode: String? = null
    @Volatile private var connectionDeadlineMs = 0L
    @Volatile private var intentionalDisconnect = false
    @Volatile private var lastNetworkSnapshot = networkMonitor.currentSnapshot()
    @Volatile private var networkAvailable = lastNetworkSnapshot.available
    @Volatile private var detailsExpanded = false
    @Volatile private var appForegrounded = true
    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    @Volatile private var verificationJob: Job? = null
    @Volatile private var deadlineJob: Job? = null
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var recoveryTimeoutJob: Job? = null
    @Volatile private var trafficStatsJob: Job? = null
    @Volatile private var serviceStarted = false

    init {
        scope.launch {
            networkMonitor.snapshots.collect { snapshot ->
                if (snapshot != lastNetworkSnapshot) onNetworkSnapshot(snapshot)
            }
        }
    }

    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null

    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    internal fun currentAttemptId(): Long = attempts.get()

    fun isTunnelActiveOrConnecting(): Boolean {
        val s = _telemetry.value.connectionState
        return s == ConnectionState.CONNECTED ||
            s == ConnectionState.CONNECTING ||
            s == ConnectionState.RECONNECTING ||
            s == ConnectionState.FAILOVER ||
            s == ConnectionState.DISCONNECTING
    }

    /** Hands off synchronously so a second click always cancels, never starts a second selector. */
    fun connectCandidates(
        profiles: List<ServerProfileEntity>,
        selectedCountryCode: String? = null
    ) {
        intentionalDisconnect = false
        lastSelectedCountryCode = ServerCountryResolver.normalizeCode(selectedCountryCode)
        networkMonitor.start()
        lastNetworkSnapshot = networkMonitor.currentSnapshot()
        networkAvailable = lastNetworkSnapshot.available
        val eligible = profiles.asSequence()
            .filter { ClientPolicy.isSupported(it) && it.isEnabled }
            .distinctBy { it.id }
            .take(MAX_CONNECTION_ATTEMPTS)
            .toList()
        lastConnectCandidates = eligible
        val request = connectionRequests.incrementAndGet()
        recoveryJob?.cancel()
        recoveryJob = null
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        verificationJob?.cancel()
        deadlineJob?.cancel()
        connectionDeadlineMs = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        resetRecoveryState()
        resetTrafficBaseline()
        _telemetry.value = resetSessionTelemetry(_telemetry.value).copy(
            connectionState = ConnectionState.CONNECTING,
            activeNode = eligible.firstOrNull(),
            lastError = null
        )
        deadlineJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            tunnelMutex.withLock {
                if (!isCurrentRequest(request) || _telemetry.value.connectionState != ConnectionState.CONNECTING) return@withLock
                val cutoff = attempts.incrementAndGet()
                verificationJob?.cancel()
                deadlineJob = null
                publishConnectionFailure("Connection timed out; try again")
                core.stopCore(cutoff)
                stopService(request)
            }
        }
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentRequest(request)) return@withLock
                candidates = eligible
                candidateIndex = 0
                startNextCandidateLocked(request, "No supported server is available")
            }
        }
    }

    private suspend fun startNextCandidateLocked(request: Long, failure: String) {
        var lastFailure = failure
        while (isCurrentRequest(request) && candidateIndex < candidates.size &&
            SystemClock.elapsedRealtime() < connectionDeadlineMs) {
            val profile = candidates[candidateIndex++]
            if (startProfileLocked(request, profile, ConnectionState.CONNECTING)) return
            lastFailure = "Unable to start this server"
        }
        if (!isCurrentRequest(request)) return
        val cutoff = attempts.incrementAndGet()
        core.stopCore(cutoff)
        stopService(request)
        publishConnectionFailure(lastFailure)
    }

    private suspend fun startProfileLocked(
        request: Long,
        profile: ServerProfileEntity,
        state: ConnectionState
    ): Boolean {
        if (!isCurrentRequest(request)) return false
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        val attempt = attempts.incrementAndGet()
        _telemetry.value = resetSessionTelemetry(_telemetry.value).copy(
            connectionState = state,
            activeNode = profile,
            lastError = null,
            activeLatencyMs = -1,
            isFailoverInProgress = state == ConnectionState.FAILOVER,
            reconnectAttempt = if (state == ConnectionState.FAILOVER) recoveryAttempts else 0,
            maxReconnectAttempts = if (state == ConnectionState.FAILOVER) MAX_RECOVERY_ATTEMPTS else 0,
            verificationState = if (state == ConnectionState.FAILOVER) {
                VerificationState.CHECKING
            } else {
                VerificationState.IDLE
            }
        )
        return try {
            val settings = ClientPolicy.settings(repository.getSettings())
            if (!isCurrentRequest(request)) return false
            core.startCore(profile, settings, attempt).getOrThrow()
            val intent = Intent(context, LuxyVpnService::class.java).apply {
                action = LuxyVpnService.ACTION_START
                putExtra(LuxyVpnService.EXTRA_MTU, settings.mtu)
                putExtra(LuxyVpnService.EXTRA_NODE_NAME, profile.name)
                putExtra(LuxyVpnService.EXTRA_ATTEMPT_ID, attempt)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            serviceStarted = true
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun disconnectAndAwait(reason: String? = null) {
        disconnectJob(reason).join()
    }

    fun disconnect(reason: String? = null) {
        disconnectJob(reason)
    }

    private fun disconnectJob(reason: String?): Job {
        intentionalDisconnect = true
        val request = connectionRequests.incrementAndGet()
        val cutoff = attempts.incrementAndGet()
        verificationJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryTimeoutJob?.cancel()
        recoveryTimeoutJob = null
        resetRecoveryState()
        deadlineJob?.cancel()
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        networkMonitor.stop()
        _telemetry.value = resetSessionTelemetry(_telemetry.value).copy(connectionState = ConnectionState.DISCONNECTING)
        return scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentRequest(request)) return@withLock
                core.stopCore(cutoff)
                candidates = emptyList()
                recoveryCandidates = emptyList()
                stopService(request)
                publishIdle(ConnectionState.DISCONNECTED, null)
                repository.logEvent(LogLevel.INFO, LogCategory.TUNNEL, reason ?: "VPN disconnected")
            }
        }
    }

    fun failConnection(message: String) {
        val request = connectionRequests.incrementAndGet()
        val cutoff = attempts.incrementAndGet()
        verificationJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryTimeoutJob?.cancel()
        recoveryTimeoutJob = null
        resetRecoveryState()
        deadlineJob?.cancel()
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        _telemetry.value = resetSessionTelemetry(_telemetry.value).copy(
            connectionState = ConnectionState.ERROR,
            lastError = message,
            activeLatencyMs = -1,
            verificationState = VerificationState.FAILED
        )
        networkMonitor.stop()
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentRequest(request)) return@withLock
                core.stopCore(cutoff)
                candidates = emptyList()
                stopService(request)
                publishIdle(ConnectionState.ERROR, message)
                val current = _telemetry.value
                val classified = ErrorClassifier.classify(message, activeProfile = current.activeNode)
                _telemetry.value = current.copy(
                    lastError = message,
                    lastErrorCategory = classified.category.takeUnless { it == ErrorCategory.NONE }
                        ?: ErrorCategory.UNKNOWN,
                    lastErrorTechnicalDetail = redactTechnicalDetail(
                        classified.technicalDetail ?: message,
                        current.activeNode
                    ),
                    verificationState = VerificationState.FAILED
                )
            }
        }
    }

    private fun stopService(request: Long) {
        if (!serviceStarted) return
        serviceStarted = false
        val intent = Intent(context, LuxyVpnService::class.java).apply {
            action = LuxyVpnService.ACTION_STOP
            putExtra(LuxyVpnService.EXTRA_REQUEST_ID, request)
        }
        runCatching { context.startService(intent) }.onFailure {
            runCatching { context.stopService(Intent(context, LuxyVpnService::class.java)) }
        }
    }

    internal fun isCurrentRequest(request: Long): Boolean = connectionRequests.get() == request

    internal fun isCurrentAttempt(attempt: Long): Boolean = attempt > 0L && attempts.get() == attempt

    internal fun onServiceDestroyed(attempt: Long) {
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt)) return@withLock
                serviceStarted = false
                val state = _telemetry.value.connectionState
                if (state != ConnectionState.CONNECTING && state != ConnectionState.CONNECTED &&
                    state != ConnectionState.RECONNECTING && state != ConnectionState.FAILOVER
                ) return@withLock
                connectionRequests.incrementAndGet()
                attempts.incrementAndGet()
                verificationJob?.cancel()
                deadlineJob?.cancel()
                deadlineJob = null
                trafficStatsJob?.cancel()
                trafficStatsJob = null
                recoveryJob?.cancel()
                recoveryJob = null
                recoveryTimeoutJob?.cancel()
                recoveryTimeoutJob = null
                publishConnectionFailure("VPN service stopped")
            }
        }
    }

    internal fun attachTun(attempt: Long, tunFd: Int): Result<Unit> =
        if (isCurrentAttempt(attempt)) core.attachTun(attempt, tunFd)
        else Result.failure(CancellationException("Connection cancelled"))

    internal fun stopCore(throughAttempt: Long) = core.stopCore(throughAttempt)

    /** Native startup alone proves nothing about credentials or the remote data path. */
    internal fun onTunnelEstablished(
        attempt: Long,
        alwaysOn: Boolean = false,
        lockdown: Boolean = false
    ) {
        if (!isCurrentAttempt(attempt)) return
        val request = connectionRequests.get()
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        resetTrafficBaseline()
        val trafficBaseline = runCatching { trafficStats() }.getOrDefault(0L to 0L)
        lastTrafficRx = trafficBaseline.first.coerceAtLeast(0L)
        lastTrafficTx = trafficBaseline.second.coerceAtLeast(0L)
        _telemetry.value = _telemetry.value.copy(
            verificationState = VerificationState.CHECKING,
            connectedAtElapsedMs = nowElapsed,
            connectedSinceMs = nowWall,
            sessionDurationMs = 0L,
            rxBytes = 0L,
            txBytes = 0L,
            rxBytesPerSec = 0L,
            txBytesPerSec = 0L,
            exitIp = null,
            exitCountryCode = null,
            exitCountryConfidence = CountryConfidence.UNKNOWN,
            connectivityVerified = false,
            exitVerified = false,
            dnsLeakProtected = false,
            ipv6LeakProtected = false,
            vpnPostureAvailable = false,
            alwaysOnEnabled = alwaysOn,
            lockdownEnabled = lockdown,
            networkTransport = lastNetworkSnapshot.transport,
            networkFingerprint = activeNetworkFingerprint()
        )
        runVerification(attempt, request, alwaysOn, lockdown, forceExitRefresh = true)
    }

    fun retryVerification() {
        if (_telemetry.value.connectionState != ConnectionState.CONNECTED) return
        val attempt = attempts.get()
        runVerification(
            attempt,
            connectionRequests.get(),
            _telemetry.value.alwaysOnEnabled,
            _telemetry.value.lockdownEnabled,
            forceExitRefresh = true
        )
    }

    private fun runVerification(
        attempt: Long,
        request: Long,
        alwaysOn: Boolean,
        lockdown: Boolean,
        forceExitRefresh: Boolean
    ) {
        if (!isCurrentAttempt(attempt)) return
        _telemetry.value = _telemetry.value.copy(
            verificationState = VerificationState.CHECKING,
            exitIp = null,
            exitCountryCode = null,
            exitCountryConfidence = CountryConfidence.UNKNOWN,
            connectivityVerified = false,
            exitVerified = false,
            dnsLeakProtected = false,
            ipv6LeakProtected = false,
            vpnPostureAvailable = false,
            rxBytesPerSec = 0L,
            txBytesPerSec = 0L
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@launch
            val profile = _telemetry.value.activeNode ?: return@launch
            val netFp = activeNetworkFingerprint()
            val connectivity = runCatching { connectivityCheck.check() }.getOrNull()
            if (connectivity?.reachable != true) {
                if (!networkAvailable) {
                    waitForNetwork(attempt, request)
                } else {
                    onTunnelFailed(
                        attempt,
                        connectivity?.safeMessage ?: "No server passed the secure connection check"
                    )
                }
                return@launch
            }

            val exitResult = runCatching {
                exitCountryVerifier.verifyExit(
                    cacheKey = "${ConfigFingerprint.compute(profile)}|$netFp",
                    forceRefresh = forceExitRefresh,
                    shouldCache = { isCurrentAttempt(attempt) && isCurrentRequest(request) }
                )
            }.getOrNull()
            val targetCountry = lastSelectedCountryCode
            val verifiedCountry = exitResult?.countryCode
            val nowMs = System.currentTimeMillis()
            if (targetCountry != null && verifiedCountry != null && verifiedCountry != targetCountry) {
                tunnelMutex.withLock {
                    if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                    val current = _telemetry.value.activeNode ?: return@withLock
                    val updated = ConfigurationHealthTracker.recordSuccess(
                        profile = current,
                        latencyMs = connectivity.latencyMs ?: current.latencyMs,
                        networkFingerprint = netFp,
                        exitIp = exitResult?.exitIp,
                        verifiedCountryCode = verifiedCountry,
                        nowMs = exitResult?.verifiedAtMs ?: nowMs,
                        measurementType = MeasurementType.TUNNEL_CONNECT
                    )
                    runCatching { repository.updateProfileHealth(updated) }
                    rejectEstablishedCandidateLocked(request, "Server exited in a different country")
                }
                return@launch
            }
            val posture = runCatching { vpnPostureProvider() }.getOrNull()
            val leakResult = runCatching {
                leakChecker.checkObserved(
                    posture = posture,
                    alwaysOnEnabled = alwaysOn,
                    lockdownEnabled = lockdown
                )
            }.getOrNull()

            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                val current = _telemetry.value.activeNode ?: return@withLock
                val updated = ConfigurationHealthTracker.recordSuccess(
                    profile = current,
                    latencyMs = connectivity.latencyMs ?: current.latencyMs,
                    networkFingerprint = netFp,
                    exitIp = exitResult?.exitIp,
                    verifiedCountryCode = verifiedCountry,
                    nowMs = exitResult?.verifiedAtMs ?: nowMs,
                    measurementType = MeasurementType.TUNNEL_CONNECT
                )
                runCatching { repository.updateProfileHealth(updated) }
                runCatching { repository.setActiveProfile(updated.id) }
                if (SystemClock.elapsedRealtime() >= connectionDeadlineMs &&
                    _telemetry.value.connectionState == ConnectionState.CONNECTING) {
                    startNextCandidateLocked(request, "Connection timed out; try again")
                    return@withLock
                }

                val exitVerified = !verifiedCountry.isNullOrBlank()
                val dnsProtected = leakResult?.dnsVerified == true
                val ipv6Protected = leakResult?.ipv6Routed == true
                val verification = when {
                    !exitVerified -> VerificationState.INCONCLUSIVE
                    dnsProtected && ipv6Protected -> VerificationState.VERIFIED
                    else -> VerificationState.PARTIAL
                }
                val errorCategory = when (verification) {
                    VerificationState.VERIFIED, VerificationState.INCONCLUSIVE -> ErrorCategory.NONE
                    else -> ErrorCategory.UNKNOWN
                }
                val technicalDetail = if (
                    verification == VerificationState.VERIFIED ||
                    verification == VerificationState.INCONCLUSIVE
                ) null else {
                    SensitiveDataRedactor.redact(
                        leakResult?.detail?.takeIf { it.isNotBlank() }
                            ?: "Exit or leak verification was incomplete",
                        activeProfile = updated
                    )
                }
                deadlineJob?.cancel()
                recoveryJob?.cancel()
                recoveryJob = null
                _telemetry.value = _telemetry.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    activeNode = updated,
                    lastError = null,
                    activeLatencyMs = connectivity.latencyMs ?: updated.latencyMs ?: -1,
                    networkTransport = lastNetworkSnapshot.transport,
                    networkFingerprint = netFp,
                    exitIp = exitResult?.exitIp,
                    exitCountryCode = verifiedCountry,
                    exitCountryConfidence = if (exitVerified) CountryConfidence.VERIFIED
                        else CountryConfidence.UNKNOWN,
                    connectivityVerified = connectivity.reachable,
                    exitVerified = exitVerified,
                    dnsLeakProtected = dnsProtected,
                    ipv6LeakProtected = ipv6Protected,
                    vpnPostureAvailable = posture != null,
                    alwaysOnEnabled = alwaysOn,
                    lockdownEnabled = lockdown,
                    verificationState = verification,
                    lastErrorCategory = errorCategory,
                    lastErrorTechnicalDetail = technicalDetail,
                    isFailoverInProgress = false,
                    reconnectAttempt = recoveryAttempts,
                    maxReconnectAttempts = MAX_RECOVERY_ATTEMPTS
                )
                candidates = emptyList()
                if (verification == VerificationState.VERIFIED) resetRecoveryState()
                syncTrafficPollingLocked()
            }
        }
        verificationJob?.cancel()
        verificationJob = job
        job.start()
    }

    private suspend fun rejectEstablishedCandidateLocked(request: Long, message: String) {
        if (_telemetry.value.connectionState == ConnectionState.CONNECTING) {
            startNextCandidateLocked(request, message)
        } else {
            val classified = ErrorClassifier.classify(message, activeProfile = _telemetry.value.activeNode)
            beginRecoveryLocked(
                request,
                _telemetry.value.activeNode,
                message,
                classified,
                immediate = false
            )
        }
    }

    @Synchronized
    internal fun onTunnelFailed(attempt: Long, message: String) {
        if (!isCurrentAttempt(attempt) || lastHandledFailureAttempt == attempt) return
        lastHandledFailureAttempt = attempt
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt)) return@withLock
                verificationJob?.cancel()
                if (intentionalDisconnect) {
                    finishIntentionalDisconnectLocked(connectionRequests.get())
                    return@withLock
                }
                if (!networkAvailable && message != VPN_INTERFACE_FAILURE) {
                    enterWaitingForNetworkLocked()
                    return@withLock
                }
                val request = connectionRequests.get()
                val state = _telemetry.value.connectionState
                if (state != ConnectionState.CONNECTING &&
                    state != ConnectionState.CONNECTED &&
                    state != ConnectionState.RECONNECTING &&
                    state != ConnectionState.FAILOVER
                ) return@withLock

                val profile = _telemetry.value.activeNode
                val classified = ErrorClassifier.classify(message, activeProfile = profile)
                val category = classified.category.takeUnless { it == ErrorCategory.NONE } ?: ErrorCategory.UNKNOWN
                if (profile != null) recordProfileFailure(profile, category, classified.userMessage, activeNetworkFingerprint())

                if (state == ConnectionState.CONNECTING) {
                    _telemetry.value = _telemetry.value.copy(
                        lastErrorCategory = category,
                        lastErrorTechnicalDetail = redactTechnicalDetail(
                            classified.technicalDetail ?: core.lastCoreError() ?: message,
                            profile
                        ),
                        verificationState = VerificationState.FAILED
                    )
                    startNextCandidateLocked(request, message)
                    return@withLock
                }

                lastRecoveryCategory = category
                lastRecoveryUserMessage = classified.userMessage
                lastRecoveryOriginalMessage = message
                lastRecoveryTechnicalDetail = core.lastCoreError() ?: classified.technicalDetail
                if (state == ConnectionState.FAILOVER && recoveryStartedAtMs > 0L) {
                    scheduleNextRecoveryLocked(request)
                } else {
                    beginRecoveryLocked(request, profile, message, classified, immediate = false)
                }
            }
        }
    }

    private suspend fun beginRecoveryLocked(
        request: Long,
        failedProfile: ServerProfileEntity?,
        message: String,
        classified: com.example.core.ClassifiedError,
        immediate: Boolean
    ) {
        if (!isCurrentRequest(request) || intentionalDisconnect) return
        recoveryJob?.cancel()
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        val newEpisode = recoveryStartedAtMs <= 0L
        if (newEpisode) {
            recoveryAttempts = 0
            recoveryStartedAtMs = SystemClock.elapsedRealtime().coerceAtLeast(1L)
            recoveryCandidates = emptyList()
            recoveryAttemptedIds.clear()
            recoveryCurrentProfile = null
            recoveryCurrentRetryPending = false
        }
        if (recoveryTimeoutJob?.isActive != true) {
            val remainingMs = (RECOVERY_WINDOW_MS -
                (SystemClock.elapsedRealtime() - recoveryStartedAtMs)).coerceAtLeast(0L)
            recoveryTimeoutJob = scope.launch {
                delay(remainingMs)
                tunnelMutex.withLock {
                    recoveryTimeoutJob = null
                    val state = _telemetry.value.connectionState
                    if (isCurrentRequest(request) && !intentionalDisconnect &&
                        recoveryStartedAtMs > 0L &&
                        (state == ConnectionState.FAILOVER || state == ConnectionState.RECONNECTING)
                    ) finishRecoveryLocked(request)
                }
            }
        }
        lastRecoveryCategory = classified.category.takeUnless { it == ErrorCategory.NONE } ?: ErrorCategory.UNKNOWN
        lastRecoveryUserMessage = classified.userMessage.ifBlank { message }
        lastRecoveryOriginalMessage = message
        lastRecoveryTechnicalDetail = core.lastCoreError() ?: classified.technicalDetail

        val settings = runCatching { repository.getSettings() }.getOrNull()
        val selectedCountry = lastSelectedCountryCode
            ?: ServerCountryResolver.normalizeCode(settings?.selectedCountryCode)
        lastSelectedCountryCode = selectedCountry
        if (failedProfile != null) {
            recoveryCurrentProfile = failedProfile
            recoveryCurrentRetryPending = isTransient(lastRecoveryCategory)
            if (!recoveryCurrentRetryPending) recoveryAttemptedIds += failedProfile.id
        }
        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.FAILOVER,
            lastError = null,
            isFailoverInProgress = true,
            exitIp = null,
            exitCountryCode = null,
            exitCountryConfidence = CountryConfidence.UNKNOWN,
            connectivityVerified = false,
            exitVerified = false,
            dnsLeakProtected = false,
            ipv6LeakProtected = false,
            vpnPostureAvailable = false,
            rxBytesPerSec = 0L,
            txBytesPerSec = 0L,
            verificationState = VerificationState.CHECKING,
            reconnectAttempt = recoveryAttempts,
            maxReconnectAttempts = MAX_RECOVERY_ATTEMPTS
        )
        scheduleNextRecoveryLocked(request, if (immediate) 0L else null)
    }

    private suspend fun scheduleNextRecoveryLocked(request: Long, delayOverrideMs: Long? = null) {
        if (!isCurrentRequest(request) || intentionalDisconnect) return
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            finishRecoveryLocked(request)
            return
        }
        recoveryJob?.cancel()
        val pause = delayOverrideMs ?: recoveryDelayMs(recoveryAttempts + 1)
        recoveryJob = scope.launch {
            if (pause > 0L) delay(pause)
            tunnelMutex.withLock {
                recoveryJob = null
                if (!isCurrentRequest(request) || intentionalDisconnect) return@withLock
                if (!networkAvailable) {
                    enterWaitingForNetworkLocked()
                    return@withLock
                }
                if (SystemClock.elapsedRealtime() - recoveryStartedAtMs > RECOVERY_WINDOW_MS) {
                    finishRecoveryLocked(request)
                    return@withLock
                }
                val next = selectNextRecoveryCandidateLocked()
                if (SystemClock.elapsedRealtime() - recoveryStartedAtMs > RECOVERY_WINDOW_MS) {
                    finishRecoveryLocked(request)
                    return@withLock
                }
                if (next == null) {
                    finishRecoveryLocked(request)
                    return@withLock
                }
                recoveryAttempts += 1
                _telemetry.value = _telemetry.value.copy(
                    connectionState = ConnectionState.FAILOVER,
                    reconnectAttempt = recoveryAttempts,
                    maxReconnectAttempts = MAX_RECOVERY_ATTEMPTS,
                    isFailoverInProgress = true
                )
                if (!startProfileLocked(request, next, ConnectionState.FAILOVER)) {
                    val failed = ErrorClassifier.classify("Unable to start this server", activeProfile = next)
                    recordProfileFailure(next, failed.category, failed.userMessage, activeNetworkFingerprint())
                    lastRecoveryCategory = failed.category
                    lastRecoveryUserMessage = failed.userMessage
                    lastRecoveryOriginalMessage = "Unable to start this server"
                    lastRecoveryTechnicalDetail = core.lastCoreError() ?: failed.technicalDetail
                    scheduleNextRecoveryLocked(request)
                }
            }
        }
    }

    private suspend fun selectNextRecoveryCandidateLocked(): ServerProfileEntity? {
        val settings = runCatching { repository.getSettings() }.getOrNull()
        val selectedCountry = lastSelectedCountryCode
            ?: ServerCountryResolver.normalizeCode(settings?.selectedCountryCode)
        val profiles = runCatching { repository.getAllProfiles() }.getOrDefault(emptyList())
        val subscriptions = runCatching { repository.getAllSubscriptions() }.getOrDefault(emptyList())
        val plan = AutomaticServerSelector.buildPlan(
            allProfiles = profiles,
            subscriptions = subscriptions,
            manualConfigsEnabled = settings?.manualConfigsEnabledForConnection ?: false,
            selectedCountryCode = selectedCountry,
            previousActiveId = _telemetry.value.activeNode?.id ?: lastConnectCandidates.firstOrNull()?.id,
            currentNetworkFingerprint = activeNetworkFingerprint()
        )
        val ordered = (plan as? AutomaticServerSelector.SelectionPlan.Candidates)?.ordered.orEmpty()
        recoveryCandidates = ordered.filterNot { it.id in recoveryAttemptedIds }

        val currentRetry = if (recoveryCurrentRetryPending) {
            recoveryCurrentRetryPending = false
            val retryId = recoveryCurrentProfile?.id
            val eligibleRetry = retryId?.let { id -> recoveryCandidates.firstOrNull { it.id == id } }
            if (eligibleRetry == null) retryId?.let(recoveryAttemptedIds::add)
            eligibleRetry
        } else {
            null
        }
        val next = currentRetry ?: recoveryCandidates.firstOrNull()
        if (next != null) {
            recoveryAttemptedIds += next.id
            if (currentRetry != null) recoveryCurrentProfile = next
        }
        return next
    }

    private suspend fun finishRecoveryLocked(request: Long) {
        if (!isCurrentRequest(request)) return
        recoveryJob?.cancel()
        recoveryJob = null
        val cutoff = attempts.incrementAndGet()
        core.stopCore(cutoff)
        stopService(request)
        candidates = emptyList()
        val exactInterfaceFailure = lastRecoveryOriginalMessage == VPN_INTERFACE_FAILURE ||
            lastRecoveryUserMessage == VPN_INTERFACE_FAILURE ||
            _telemetry.value.lastError == VPN_INTERFACE_FAILURE
        val errorMessage = if (exactInterfaceFailure) VPN_INTERFACE_FAILURE
            else lastRecoveryUserMessage.ifBlank { ErrorClassifier.userMessageFor(lastRecoveryCategory) }
        _telemetry.value = resetSessionTelemetry(_telemetry.value).copy(
            connectionState = ConnectionState.ERROR,
            lastError = errorMessage,
            lastErrorCategory = lastRecoveryCategory,
            lastErrorTechnicalDetail = redactTechnicalDetail(
                lastRecoveryTechnicalDetail ?: lastRecoveryOriginalMessage,
                _telemetry.value.activeNode
            ),
            verificationState = VerificationState.FAILED,
            reconnectAttempt = recoveryAttempts,
            maxReconnectAttempts = MAX_RECOVERY_ATTEMPTS
        )
        networkMonitor.stop()
        resetRecoveryState()
    }

    private fun enterWaitingForNetworkLocked() {
        recoveryJob?.cancel()
        recoveryJob = null
        verificationJob?.cancel()
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.RECONNECTING,
            lastError = WAITING_FOR_NETWORK,
            lastErrorCategory = ErrorCategory.NO_INTERNET,
            lastErrorTechnicalDetail = null,
            isFailoverInProgress = false,
            exitIp = null,
            exitCountryCode = null,
            exitCountryConfidence = CountryConfidence.UNKNOWN,
            connectivityVerified = false,
            exitVerified = false,
            dnsLeakProtected = false,
            ipv6LeakProtected = false,
            vpnPostureAvailable = false,
            rxBytesPerSec = 0L,
            txBytesPerSec = 0L,
            verificationState = VerificationState.CHECKING,
            maxReconnectAttempts = MAX_RECOVERY_ATTEMPTS
        )
    }

    private fun waitForNetwork(attempt: Long, request: Long) {
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                enterWaitingForNetworkLocked()
            }
        }
    }

    private fun onNetworkSnapshot(snapshot: NetworkSnapshot) {
        scope.launch {
            tunnelMutex.withLock {
                val old = lastNetworkSnapshot
                lastNetworkSnapshot = snapshot
                networkAvailable = snapshot.available
                val state = _telemetry.value.connectionState
                if (!snapshot.available) {
                    if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING ||
                        state == ConnectionState.RECONNECTING || state == ConnectionState.FAILOVER
                    ) enterWaitingForNetworkLocked()
                    return@withLock
                }
                if (intentionalDisconnect) return@withLock

                if (state == ConnectionState.RECONNECTING) {
                    val profile = _telemetry.value.activeNode
                    val classified = ErrorClassifier.classify(
                        _telemetry.value.lastError,
                        activeProfile = profile
                    )
                    beginRecoveryLocked(
                        connectionRequests.get(),
                        profile,
                        _telemetry.value.lastError ?: WAITING_FOR_NETWORK,
                        classified,
                        immediate = true
                    )
                } else if (state == ConnectionState.CONNECTED &&
                    (old.transport != snapshot.transport || old.fingerprint != snapshot.fingerprint)
                ) {
                    val attempt = attempts.get()
                    val request = connectionRequests.get()
                    _telemetry.value = _telemetry.value.copy(
                        verificationState = VerificationState.CHECKING,
                        exitIp = null,
                        exitCountryCode = null,
                        exitCountryConfidence = CountryConfidence.UNKNOWN,
                        connectivityVerified = false,
                        exitVerified = false,
                        dnsLeakProtected = false,
                        ipv6LeakProtected = false,
                        vpnPostureAvailable = false
                    )
                    verificationJob?.cancel()
                    val job = scope.launch {
                        val result = runCatching { connectivityCheck.check() }.getOrNull()
                        if (result?.reachable != true) {
                            onTunnelFailed(
                                attempt,
                                result?.safeMessage ?: "Tunnel connectivity was lost after a network change"
                            )
                        } else {
                            refreshVerificationAfterNetworkChange(attempt, request, snapshot, result?.latencyMs)
                        }
                    }
                    verificationJob = job
                }
            }
        }
    }

    private suspend fun refreshVerificationAfterNetworkChange(
        attempt: Long,
        request: Long,
        snapshot: NetworkSnapshot,
        latencyMs: Int?
    ) {
        val profile = _telemetry.value.activeNode ?: return
        val exitResult = runCatching {
            exitCountryVerifier.verifyExit(
                cacheKey = "${ConfigFingerprint.compute(profile)}|${snapshot.fingerprint}",
                shouldCache = { isCurrentAttempt(attempt) && isCurrentRequest(request) }
            )
        }.getOrNull()
        val country = exitResult?.countryCode
        val targetCountry = lastSelectedCountryCode
        if (targetCountry != null && country != null && country != targetCountry) {
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request) ||
                    _telemetry.value.connectionState != ConnectionState.CONNECTED
                ) return@withLock
                val current = _telemetry.value.activeNode ?: return@withLock
                val updated = ConfigurationHealthTracker.recordSuccess(
                    profile = current,
                    latencyMs = latencyMs ?: current.latencyMs,
                    networkFingerprint = snapshot.fingerprint,
                    exitIp = exitResult.exitIp,
                    verifiedCountryCode = country,
                    nowMs = exitResult.verifiedAtMs,
                    measurementType = MeasurementType.TUNNEL_CONNECT
                )
                runCatching { repository.updateProfileHealth(updated) }
                val message = "Server exited in a different country"
                val classified = ErrorClassifier.classify(message, activeProfile = updated)
                beginRecoveryLocked(request, updated, message, classified, immediate = false)
            }
            return
        }

        val posture = runCatching { vpnPostureProvider() }.getOrNull()
        val leakResult = runCatching {
            leakChecker.checkObserved(
                posture = posture,
                alwaysOnEnabled = _telemetry.value.alwaysOnEnabled,
                lockdownEnabled = _telemetry.value.lockdownEnabled
            )
        }.getOrNull()
        tunnelMutex.withLock {
            if (!isCurrentAttempt(attempt) || !isCurrentRequest(request) ||
                _telemetry.value.connectionState != ConnectionState.CONNECTED
            ) return@withLock
            val current = _telemetry.value.activeNode ?: return@withLock
            val updated = ConfigurationHealthTracker.recordSuccess(
                profile = current,
                latencyMs = latencyMs ?: current.latencyMs,
                networkFingerprint = snapshot.fingerprint,
                exitIp = exitResult?.exitIp,
                verifiedCountryCode = country,
                nowMs = exitResult?.verifiedAtMs ?: System.currentTimeMillis(),
                measurementType = MeasurementType.TUNNEL_CONNECT
            )
            runCatching { repository.updateProfileHealth(updated) }
            val exitVerified = !country.isNullOrBlank()
            val dnsProtected = leakResult?.dnsVerified == true
            val ipv6Protected = leakResult?.ipv6Routed == true
            val verification = when {
                !exitVerified -> VerificationState.INCONCLUSIVE
                dnsProtected && ipv6Protected -> VerificationState.VERIFIED
                else -> VerificationState.PARTIAL
            }
            _telemetry.value = _telemetry.value.copy(
                activeNode = updated,
                lastError = null,
                activeLatencyMs = latencyMs ?: updated.latencyMs ?: -1,
                networkTransport = snapshot.transport,
                networkFingerprint = snapshot.fingerprint,
                exitIp = exitResult?.exitIp,
                exitCountryCode = country,
                exitCountryConfidence = if (exitVerified) CountryConfidence.VERIFIED
                    else CountryConfidence.UNKNOWN,
                connectivityVerified = true,
                exitVerified = exitVerified,
                dnsLeakProtected = dnsProtected,
                ipv6LeakProtected = ipv6Protected,
                vpnPostureAvailable = posture != null,
                verificationState = verification,
                lastErrorCategory = when (verification) {
                    VerificationState.VERIFIED, VerificationState.INCONCLUSIVE -> ErrorCategory.NONE
                    else -> ErrorCategory.UNKNOWN
                },
                lastErrorTechnicalDetail = if (
                    verification == VerificationState.VERIFIED ||
                    verification == VerificationState.INCONCLUSIVE
                ) null else redactTechnicalDetail(
                    leakResult?.detail ?: "Exit or leak verification was incomplete",
                    updated
                )
            )
        }
    }

    internal fun onNetworkSnapshotForTest(snapshot: NetworkSnapshot) = onNetworkSnapshot(snapshot)

    fun setDetailsExpanded(expanded: Boolean) {
        detailsExpanded = expanded
        updateTrafficPolling()
    }

    fun setAppForegrounded(foregrounded: Boolean) {
        appForegrounded = foregrounded
        updateTrafficPolling()
    }

    private fun updateTrafficPolling() {
        scope.launch {
            tunnelMutex.withLock { syncTrafficPollingLocked() }
        }
    }

    private fun syncTrafficPollingLocked() {
        val shouldPoll = _telemetry.value.connectionState == ConnectionState.CONNECTED &&
            (detailsExpanded || appForegrounded)
        if (!shouldPoll) {
            trafficStatsJob?.cancel()
            trafficStatsJob = null
            return
        }
        if (trafficStatsJob?.isActive == true) return
        trafficStatsJob = scope.launch {
            while (true) {
                val interval = if (detailsExpanded && appForegrounded) 1_000L else 2_000L
                delay(interval)
                val (rx, tx) = runCatching { trafficStats() }.getOrDefault(0L to 0L)
                tunnelMutex.withLock {
                    if (_telemetry.value.connectionState != ConnectionState.CONNECTED ||
                        !(detailsExpanded || appForegrounded)
                    ) return@launch
                    val rxDelta = (rx - lastTrafficRx).coerceAtLeast(0L)
                    val txDelta = (tx - lastTrafficTx).coerceAtLeast(0L)
                    lastTrafficRx = rx
                    lastTrafficTx = tx
                    val elapsed = SystemClock.elapsedRealtime()
                    val sessionStart = _telemetry.value.connectedAtElapsedMs
                    _telemetry.value = _telemetry.value.copy(
                        rxBytes = _telemetry.value.rxBytes + rxDelta,
                        txBytes = _telemetry.value.txBytes + txDelta,
                        rxBytesPerSec = rxDelta * 1_000L / interval,
                        txBytesPerSec = txDelta * 1_000L / interval,
                        sessionDurationMs = if (sessionStart > 0L) (elapsed - sessionStart).coerceAtLeast(0L) else 0L
                    )
                }
            }
        }
    }

    private fun resetTrafficBaseline() {
        lastTrafficRx = 0L
        lastTrafficTx = 0L
    }

    private fun resetRecoveryState() {
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryTimeoutJob?.cancel()
        recoveryTimeoutJob = null
        recoveryCandidates = emptyList()
        recoveryAttemptedIds.clear()
        recoveryCurrentProfile = null
        recoveryCurrentRetryPending = false
        recoveryAttempts = 0
        recoveryStartedAtMs = 0L
    }

    private fun activeNetworkFingerprint(): String =
        lastNetworkSnapshot.fingerprint.takeIf { lastNetworkSnapshot.available && it.isNotBlank() }
            ?: networkFingerprintProvider()

    private fun isTransient(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.TIMEOUT,
        ErrorCategory.DNS_FAILURE,
        ErrorCategory.CONNECTION_REFUSED,
        ErrorCategory.NO_INTERNET,
        ErrorCategory.UNKNOWN -> true
        else -> false
    }

    private suspend fun recordProfileFailure(
        profile: ServerProfileEntity,
        category: ErrorCategory,
        safeMessage: String,
        networkFingerprint: String
    ) {
        val failed = ConfigurationHealthTracker.recordFailure(
            profile = profile,
            category = category,
            safeMessage = safeMessage,
            networkFingerprint = networkFingerprint,
            measurementType = MeasurementType.TUNNEL_CONNECT
        )
        runCatching { repository.updateProfileHealth(failed) }
        if (_telemetry.value.activeNode?.id == profile.id) {
            _telemetry.value = _telemetry.value.copy(activeNode = failed)
        }
    }

    private fun redactTechnicalDetail(detail: String?, profile: ServerProfileEntity?): String? =
        detail?.takeIf { it.isNotBlank() }?.let { SensitiveDataRedactor.redact(it, activeProfile = profile) }

    private fun publishIdle(state: ConnectionState, error: String?) {
        if (state == ConnectionState.ERROR) networkMonitor.stop()
        deadlineJob?.cancel()
        val current = _telemetry.value
        _telemetry.value = resetSessionTelemetry(current).copy(
            connectionState = state,
            activeNode = if (state == ConnectionState.DISCONNECTED) null else current.activeNode,
            lastError = error,
            activeLatencyMs = -1
        )
    }
    private fun publishConnectionFailure(message: String) {
        val current = _telemetry.value
        val classified = ErrorClassifier.classify(message, activeProfile = current.activeNode)
        val category = current.lastErrorCategory.takeUnless { it == ErrorCategory.NONE }
            ?: classified.category.takeUnless { it == ErrorCategory.NONE }
            ?: ErrorCategory.UNKNOWN
        _telemetry.value = resetSessionTelemetry(current).copy(
            connectionState = ConnectionState.ERROR,
            lastError = if (message == VPN_INTERFACE_FAILURE) VPN_INTERFACE_FAILURE else classified.userMessage,
            lastErrorCategory = category,
            lastErrorTechnicalDetail = redactTechnicalDetail(
                classified.technicalDetail ?: message,
                current.activeNode
            ),
            verificationState = VerificationState.FAILED
        )
        networkMonitor.stop()
    }


    private fun resetSessionTelemetry(value: TunnelTelemetry): TunnelTelemetry = value.copy(
        connectedSinceMs = 0L,
        connectedAtElapsedMs = 0L,
        sessionDurationMs = 0L,
        rxBytes = 0L,
        txBytes = 0L,
        rxBytesPerSec = 0L,
        txBytesPerSec = 0L,
        activeLatencyMs = -1,
        exitIp = null,
        exitCountryCode = null,
        exitCountryConfidence = CountryConfidence.UNKNOWN,
        connectivityVerified = false,
        exitVerified = false,
        dnsLeakProtected = false,
        ipv6LeakProtected = false,
        vpnPostureAvailable = false,
        alwaysOnEnabled = false,
        lockdownEnabled = false,
        verificationState = VerificationState.IDLE,
        lastErrorCategory = ErrorCategory.NONE,
        lastErrorTechnicalDetail = null,
        isFailoverInProgress = false,
        reconnectAttempt = 0,
        maxReconnectAttempts = 0
    )

    fun onAlwaysOnStart() {
        if (isTunnelActiveOrConnecting()) return
        scope.launch {
            tunnelMutex.withLock {
                if (isTunnelActiveOrConnecting()) return@withLock
                intentionalDisconnect = false
                serviceStarted = true
                networkMonitor.start()
                lastNetworkSnapshot = networkMonitor.currentSnapshot()
                networkAvailable = lastNetworkSnapshot.available
                runCatching { repository.ensureMigrated() }
                val settings = runCatching { repository.getSettings() }.getOrNull()
                val allProfiles = runCatching { repository.getAllProfiles() }.getOrDefault(emptyList())
                val subscriptions = runCatching { repository.getAllSubscriptions() }.getOrDefault(emptyList())
                val country = ServerCountryResolver.normalizeCode(settings?.selectedCountryCode)
                val plan = AutomaticServerSelector.buildPlan(
                    allProfiles = allProfiles,
                    subscriptions = subscriptions,
                    manualConfigsEnabled = settings?.manualConfigsEnabledForConnection ?: false,
                    selectedCountryCode = country,
                    previousActiveId = settings?.activeProfileId,
                    currentNetworkFingerprint = activeNetworkFingerprint()
                )
                val ordered = (plan as? AutomaticServerSelector.SelectionPlan.Candidates)?.ordered.orEmpty()
                if (isTunnelActiveOrConnecting()) return@withLock
                if (ordered.isEmpty()) {
                    failConnection("No supported server is available")
                } else {
                    connectCandidates(ordered, country)
                }
            }
        }
    }

    private fun finishIntentionalDisconnectLocked(request: Long) {
        if (!isCurrentRequest(request)) return
        val cutoff = attempts.incrementAndGet()
        core.stopCore(cutoff)
        stopService(request)
        publishIdle(ConnectionState.DISCONNECTED, null)
    }

    companion object {
        private const val MAX_CONNECTION_ATTEMPTS = 5
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private const val CONNECTION_TIMEOUT_MS = 25_000L
        private const val RECOVERY_WINDOW_MS = 35_000L
        private const val WAITING_FOR_NETWORK = "Waiting for network to reconnect…"
        private const val VPN_INTERFACE_FAILURE = "Unable to establish the VPN interface"
    }
}

