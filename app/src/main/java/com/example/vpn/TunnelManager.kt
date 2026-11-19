package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.example.core.ClientPolicy
import com.example.core.ConfigurationHealthTracker
import com.example.core.ExitCountryVerifier
import com.example.core.GeoIpDatabase
import com.example.core.NetworkFingerprintProvider
import com.example.core.ServerCountryResolver
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.CountryConfidence
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.TunnelTelemetry
import com.example.data.repository.LuxyVpnRepository
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.XrayBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.example.xraycore.TunnelConnectivityCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val networkFingerprintProvider: () -> String = { NetworkFingerprintProvider.current(context) }
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
    private val core = LuxyVpnCore(XrayBackend(context) { attempt ->
        onTunnelFailed(attempt, "VPN core stopped")
    })
    private var candidates: List<ServerProfileEntity> = emptyList()
    private var candidateIndex = 0
    @Volatile private var requestedCountryCode: String? = null
    @Volatile private var connectionDeadlineMs = 0L
    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    @Volatile private var verificationJob: Job? = null
    @Volatile private var deadlineJob: Job? = null
    @Volatile private var serviceStarted = false
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
        requestedCountryCode = ServerCountryResolver.normalizeCode(selectedCountryCode)
        val eligible = profiles.asSequence()
            .filter { ClientPolicy.isSupported(it) && it.isEnabled }
            .distinctBy { it.id }
            .take(MAX_CONNECTION_ATTEMPTS)
            .toList()
        val request = connectionRequests.incrementAndGet()
        connectionDeadlineMs = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        verificationJob?.cancel()
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            tunnelMutex.withLock {
                if (!isCurrentRequest(request) || _telemetry.value.connectionState != ConnectionState.CONNECTING) return@withLock
                val cutoff = attempts.incrementAndGet()
                verificationJob?.cancel()
                deadlineJob = null
                publishIdle(ConnectionState.ERROR, "Connection timed out; try again")
                core.stopCore(cutoff)
                stopService(request)
            }
        }
        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.CONNECTING,
            activeNode = eligible.firstOrNull(),
            lastError = null
        )
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
            val attempt = attempts.incrementAndGet()
            _telemetry.value = _telemetry.value.copy(
                connectionState = ConnectionState.CONNECTING,
                activeNode = profile,
                lastError = null,
                activeLatencyMs = -1
            )
            try {
                val settings = ClientPolicy.settings(repository.getSettings())
                if (!isCurrentRequest(request)) return
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
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastFailure = "Unable to start this server"
            }
        }
        if (!isCurrentRequest(request)) return
        val cutoff = attempts.incrementAndGet()
        core.stopCore(cutoff)
        stopService(request)
        publishIdle(ConnectionState.ERROR, lastFailure)
    }

    suspend fun disconnectAndAwait(reason: String? = null) {
        disconnectJob(reason).join()
    }

    fun disconnect(reason: String? = null) {
        disconnectJob(reason)
    }

    private fun disconnectJob(reason: String?): Job {
        val request = connectionRequests.incrementAndGet()
        val cutoff = attempts.incrementAndGet()
        verificationJob?.cancel()
        deadlineJob?.cancel()
        _telemetry.value = _telemetry.value.copy(connectionState = ConnectionState.DISCONNECTING)
        return scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentRequest(request)) return@withLock
                core.stopCore(cutoff)
                candidates = emptyList()
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
        deadlineJob?.cancel()
        _telemetry.value = _telemetry.value.copy(
            connectionState = ConnectionState.ERROR,
            lastError = message,
            activeLatencyMs = -1
        )
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentRequest(request)) return@withLock
                core.stopCore(cutoff)
                candidates = emptyList()
                stopService(request)
                publishIdle(ConnectionState.ERROR, message)
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
                if (state != ConnectionState.CONNECTING && state != ConnectionState.CONNECTED) return@withLock
                connectionRequests.incrementAndGet()
                attempts.incrementAndGet()
                verificationJob?.cancel()
                publishIdle(ConnectionState.ERROR, "VPN service stopped")
            }
        }
    }

    internal fun attachTun(attempt: Long, tunFd: Int): Result<Unit> =
        if (isCurrentAttempt(attempt)) core.attachTun(attempt, tunFd)
        else Result.failure(CancellationException("Connection cancelled"))

    internal fun stopCore(throughAttempt: Long) = core.stopCore(throughAttempt)

    /** Native startup alone proves nothing about credentials or the remote data path. */
    internal fun onTunnelEstablished(attempt: Long) {
        if (!isCurrentAttempt(attempt)) return
        val request = connectionRequests.get()
        val targetCountry = requestedCountryCode
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!isCurrentAttempt(attempt)) return@launch
            val reachable = connectivityCheck.isReachable()
            val activeProfile = _telemetry.value.activeNode
            val netFp = networkFingerprintProvider()
            if (!reachable) {
                if (activeProfile != null) {
                    val failed = ConfigurationHealthTracker.recordFailure(
                        profile = activeProfile,
                        category = ErrorCategory.TIMEOUT,
                        safeMessage = "No server passed the secure connection check",
                        networkFingerprint = netFp
                    )
                    runCatching { repository.updateProfileHealth(failed) }
                }
                tunnelMutex.withLock {
                    if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                    startNextCandidateLocked(request, "No server passed the secure connection check")
                }
                return@launch
            }

            val exitResult = exitCountryVerifier.verifyExit()
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                val profile = _telemetry.value.activeNode ?: return@withLock
                val nowMs = System.currentTimeMillis()

                if (targetCountry != null) {
                    val verifiedCountry = exitResult?.countryCode
                    if (verifiedCountry != null && verifiedCountry != targetCountry) {
                        // Server works, but exits in a different country: record its true verified country
                        // and never silently connect through the wrong country.
                        val updated = ConfigurationHealthTracker.recordSuccess(
                            profile = profile,
                            latencyMs = profile.latencyMs,
                            networkFingerprint = netFp,
                            exitIp = exitResult.exitIp,
                            verifiedCountryCode = verifiedCountry,
                            nowMs = nowMs
                        )
                        runCatching { repository.updateProfileHealth(updated) }
                        startNextCandidateLocked(request, "Server exited in a different country")
                        return@withLock
                    }
                    if (verifiedCountry == null) {
                        val previouslyVerified = CountryConfidence.parse(profile.countryConfidence) == CountryConfidence.VERIFIED &&
                            ServerCountryResolver.normalizeCode(profile.countryCode) == targetCountry &&
                            profile.countryVerifiedAt != null &&
                            (nowMs - profile.countryVerifiedAt) <= HealthPolicy.EXIT_VERIFICATION_STALE_MS
                        if (!previouslyVerified) {
                            val failed = ConfigurationHealthTracker.recordFailure(
                                profile = profile,
                                category = ErrorCategory.EXIT_VERIFICATION_FAILED,
                                safeMessage = "Could not verify exit country",
                                networkFingerprint = netFp,
                                nowMs = nowMs
                            )
                            runCatching { repository.updateProfileHealth(failed) }
                            startNextCandidateLocked(request, "Could not verify exit country for the selected location")
                            return@withLock
                        }
                    }
                }

                val updated = ConfigurationHealthTracker.recordSuccess(
                    profile = profile,
                    latencyMs = profile.latencyMs,
                    networkFingerprint = netFp,
                    exitIp = exitResult?.exitIp,
                    verifiedCountryCode = exitResult?.countryCode,
                    nowMs = nowMs
                )
                runCatching { repository.updateProfileHealth(updated) }
                repository.setActiveProfile(updated.id)
                if (SystemClock.elapsedRealtime() >= connectionDeadlineMs) {
                    startNextCandidateLocked(request, "Connection timed out; try again")
                    return@withLock
                }
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                deadlineJob?.cancel()
                _telemetry.value = _telemetry.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    activeNode = updated,
                    lastError = null
                )
                candidates = emptyList()
            }
        }
        verificationJob = job
        job.start()
    }

    internal fun onTunnelFailed(attempt: Long, message: String) {
        scope.launch {
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt)) return@withLock
                verificationJob?.cancel()
                val profile = _telemetry.value.activeNode
                if (profile != null) {
                    val category = ConfigurationHealthTracker.classifyError(message)
                    val failed = ConfigurationHealthTracker.recordFailure(
                        profile = profile,
                        category = category,
                        safeMessage = message,
                        networkFingerprint = networkFingerprintProvider()
                    )
                    runCatching { repository.updateProfileHealth(failed) }
                }
                startNextCandidateLocked(connectionRequests.get(), message)
            }
        }
    }

    private fun publishIdle(state: ConnectionState, error: String?) {
        deadlineJob?.cancel()
        _telemetry.value = _telemetry.value.copy(
            connectionState = state,
            lastError = error,
            activeLatencyMs = -1
        )
    }

    companion object {
        private const val MAX_CONNECTION_ATTEMPTS = 5
        private const val CONNECTION_TIMEOUT_MS = 25_000L
    }
}
