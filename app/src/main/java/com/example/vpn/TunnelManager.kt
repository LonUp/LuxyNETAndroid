package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.example.core.ClientPolicy
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
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

class TunnelManager(
    private val context: Context,
    private val repository: LuxyVpnRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()
    private val connectionRequests = AtomicLong(0L)
    private val attempts = AtomicLong(0L)
    private val core = LuxyVpnCore(XrayBackend(context) { attempt ->
        onTunnelFailed(attempt, "VPN core stopped")
    })
    private val connectivityCheck = TunnelConnectivityCheck()
    private var candidates: List<ServerProfileEntity> = emptyList()
    private var candidateIndex = 0
    @Volatile private var connectionDeadlineMs = 0L
    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    @Volatile private var verificationJob: Job? = null
    @Volatile private var deadlineJob: Job? = null
    @Volatile private var serviceStarted = false
    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null

    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    /** Hands off synchronously so a second click always cancels, never starts a second selector. */
    fun connectCandidates(profiles: List<ServerProfileEntity>) {
        val eligible = profiles.asSequence()
            .filter(ClientPolicy::isSupported)
            .distinctBy { it.id }
            .take(MAX_CONNECTION_ATTEMPTS)
            .toList()
        val request = connectionRequests.incrementAndGet()
        connectionDeadlineMs = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        attempts.incrementAndGet()
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

    fun disconnect(reason: String? = null) {
        val request = connectionRequests.incrementAndGet()
        val cutoff = attempts.incrementAndGet()
        verificationJob?.cancel()
        deadlineJob?.cancel()
        _telemetry.value = _telemetry.value.copy(connectionState = ConnectionState.DISCONNECTING)
        scope.launch {
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!isCurrentAttempt(attempt)) return@launch
            val reachable = connectivityCheck.isReachable()
            tunnelMutex.withLock {
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                if (!reachable) {
                    startNextCandidateLocked(request, "No server passed the secure connection check")
                    return@withLock
                }
                val profile = _telemetry.value.activeNode ?: return@withLock
                repository.setActiveProfile(profile.id)
                if (SystemClock.elapsedRealtime() >= connectionDeadlineMs) {
                    startNextCandidateLocked(request, "Connection timed out; try again")
                    return@withLock
                }
                if (!isCurrentAttempt(attempt) || !isCurrentRequest(request)) return@withLock
                deadlineJob?.cancel()
                _telemetry.value = _telemetry.value.copy(
                    connectionState = ConnectionState.CONNECTED,
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
        private const val MAX_CONNECTION_ATTEMPTS = 3
        private const val CONNECTION_TIMEOUT_MS = 25_000L
    }
}
