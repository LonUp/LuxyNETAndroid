package com.example.xraycore

import com.example.core.SensitiveDataRedactor
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin, process-wide façade over the bundled Xray core (libv2ray).
 *
 * Lifecycle: [initialize] once per process → [start] with the compiled config and the
 * established TUN fd → [stop]. The core owns the TUN device through its `tun` inbound,
 * so no userspace TCP/IP stack or external tun2socks process is involved.
 *
 * See docs/adr/0001-tunnel-core-xray-tun.md.
 */
object XrayEngine {

    private const val TAG = "XrayEngine"
    private const val PREFS = "luxy_core_env"
    private const val KEY_XUDP = "xudp_base_key"
    internal const val XUDP_KEY_BYTES = 32
    private const val START_TIMEOUT_MS = 20_000L
    private const val READY_GRACE_MS = 3_000L
    private const val MAX_CORE_ERROR_LENGTH = 1_024


    private val initialized = AtomicBoolean(false)
    private val recentCoreError = AtomicReference<String?>(null)
    private val sessionRxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val sessionTxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val startLock = Any()
    private val defaultStopExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "xray-stop").apply { isDaemon = true }
    }
    private val startExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "xray-start").apply { isDaemon = true }
    }

    /** Overridden only by tests to prove stop() releases startLock without touching the core. */
    @Volatile
    internal var stopExecutor: java.util.concurrent.Executor = defaultStopExecutor

    internal val defaultStopExecutorForTest: java.util.concurrent.Executor
        get() = defaultStopExecutor

    @Volatile
    internal var controller: CoreController? = null
    private var activeAttempt = 0L
    private var newestAttempt = 0L

    @Volatile
    private var logger: ((String) -> Unit)? = null

    fun setLogger(sink: ((String) -> Unit)?) {
        logger = sink
    }

    /** Prepares the Go bridge, fixed routing assets and XUDP key once per process. */
    fun initialize(context: Context): Result<Unit> {
        if (initialized.get()) return Result.success(Unit)
        synchronized(startLock) {
            if (initialized.get()) return Result.success(Unit)
            return runCatching {
                val app = context.applicationContext
                Seq.setContext(app)
                val assets = CoreRoutingAssets.directory(app)
                Libv2ray.initCoreEnv(assets.absolutePath, xudpKey(app))
                initialized.set(true)
                emit("core environment ready (${version()})")
            }.onFailure { emit("core environment failed: ${it.message}") }
        }
    }

    /** Starts one generation of the core. All native lifecycle calls run on worker threads. */
    fun start(context: Context, configJson: String, tunFd: Int, attempt: Long, onStopped: (Long) -> Unit): Result<Unit> {
        if (tunFd <= 0) return Result.failure(IllegalArgumentException("No VPN interface descriptor"))
        initialize(context).onFailure { return Result.failure(it) }
        synchronized(startLock) {
            if (attempt <= newestAttempt) {
                return Result.failure(IllegalStateException("Stale connection attempt"))
            }
            newestAttempt = attempt
            recentCoreError.set(null)
            sessionRxBytes.set(0L)
            sessionTxBytes.set(0L)
            return runCatching {
                controller?.let { stale ->
                    runCatching { stale.stopLoop() }
                        .onFailure { emit("core stop failed: ${it.message}") }
                }
                controller = null
                val fresh = Libv2ray.newCoreController(Callback(attempt, onStopped)).also { controller = it }
                activeAttempt = attempt
                birth(fresh, configJson, tunFd)
                if (!fresh.isRunning) {
                    throw IllegalStateException("Xray core reported not running after startLoop")
                }
                emit("core started (tunFd=$tunFd)")
            }.onFailure {
                controller?.let { failed ->
                    runCatching { failed.stopLoop() }
                        .onFailure { emit("core stop failed: ${it.message}") }
                }
                controller = null
                activeAttempt = 0L
                emit("core start failed: ${it.message}")
            }
        }
    }

    /**
     * `startLoop` is documented as returning immediately, but it boots a Go runtime and a
     * routing stack, so it must never run on the caller's thread. It may also block for the
     * lifetime of the core on some builds: readiness is therefore polled through `isRunning`
     * rather than relying on the call returning.
     */
    private fun birth(ctrl: CoreController, configJson: String, tunFd: Int) {
        val callReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        startExecutor.execute {
            try {
                ctrl.startLoop(configJson, tunFd)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                callReturned.countDown()
            }
        }

        val startedAt = System.nanoTime()
        var returnedAt = 0L
        while (!ctrl.isRunning) {
            failure.get()?.let { throw it }
            val now = System.nanoTime()
            if (returnedAt == 0L && callReturned.count == 0L) {
                returnedAt = now
            }
            if (returnedAt != 0L && now - returnedAt > TimeUnit.MILLISECONDS.toNanos(READY_GRACE_MS)) {
                throw IllegalStateException("startLoop returned without the core becoming live")
            }
            if (now - startedAt > TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MS)) {
                throw IllegalStateException("Timed out waiting for Xray core to start")
            }
            Thread.sleep(50)
        }
        failure.get()?.let { throw it }
    }

    fun stop(throughAttempt: Long) {
        val ctrl: CoreController
        synchronized(startLock) {
            newestAttempt = maxOf(newestAttempt, throughAttempt)
            if (activeAttempt > throughAttempt) return
            ctrl = controller ?: return
            controller = null
            activeAttempt = 0L
            sessionRxBytes.set(0L)
            sessionTxBytes.set(0L)
        }
        // stopLoop may stall in the core's shutdown path; it must never block a concurrent
        // start() waiting on startLock. Each CoreController instance is independent, so a
        // late stop of an old attempt cannot touch a newer core.
        stopExecutor.execute {
            runCatching { ctrl.stopLoop() }
                .onFailure { emit("core stop failed: ${it.message}") }
                .onSuccess { emit("core stopped") }
        }
    }


    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("unavailable")
    fun lastCoreError(): String? = recentCoreError.get()

    fun queryTrafficStats(): Pair<Long, Long> = runCatching {
        val (deltaRx, deltaTx) = parseOutboundTrafficStats(controller?.queryAllOutboundTrafficStats())
        val totalRx = sessionRxBytes.addAndGet(deltaRx.coerceAtLeast(0L))
        val totalTx = sessionTxBytes.addAndGet(deltaTx.coerceAtLeast(0L))
        totalRx to totalTx
    }.getOrDefault(sessionRxBytes.get() to sessionTxBytes.get())
    internal fun parseOutboundTrafficStats(stats: String?): Pair<Long, Long> {
        if (stats.isNullOrBlank()) return 0L to 0L
        var downlink = 0L
        var uplink = 0L
        stats.split(';').forEach { entry ->
            val fields = entry.split(',', limit = 3)
            if (fields.size != 3 || fields[0].trim() != "proxy-out") return@forEach
            val value = fields[2].trim().toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
            when (fields[1].trim().lowercase()) {
                "downlink" -> downlink = addTraffic(downlink, value)
                "uplink" -> uplink = addTraffic(uplink, value)
            }
        }
        return downlink to uplink
    }

    private fun addTraffic(current: Long, increment: Long): Long =
        if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment





    /**
     * `xray.xudp.basekey` is **base64url text that decodes to exactly 32 bytes** — the core
     * reports the *decoded* length, which is why a 36-char UUID showed "len 27" (36/4*3) and a
     * 32-char ASCII key showed "len 24". Keys are therefore 32 random bytes in unpadded base64url
     * (43 chars), and a stored key is validated by decoding it, so devices carrying either of the
     * two broken formats self-heal on the next start.
     */
    internal fun xudpKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_XUDP, null)
        if (isValidXudpKey(stored)) return stored!!
        val generated = generateXudpKey()
        prefs.edit { putString(KEY_XUDP, generated) }
        return generated
    }

    /** 32 random bytes in unpadded base64url: 43 characters that decode back to [XUDP_KEY_BYTES]. */
    internal fun generateXudpKey(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(XUDP_KEY_BYTES).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun isValidXudpKey(stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        return runCatching {
            Base64.decode(stored, Base64.URL_SAFE or Base64.NO_WRAP).size == XUDP_KEY_BYTES
        }.getOrDefault(false)
    }

    private fun emit(message: String) {
        val sanitized = SensitiveDataRedactor.redact(message).take(MAX_CORE_ERROR_LENGTH)
        Log.i(TAG, sanitized)
        logger?.invoke(sanitized)
    }

    private fun recordCoreError(code: Long, message: String) {
        val sanitized = SensitiveDataRedactor.redact(message).take(MAX_CORE_ERROR_LENGTH)
        val line = "[$code] $sanitized"
        recentCoreError.set(line)
        Log.w(TAG, "core status $line")
    }

    private class Callback(private val attempt: Long, private val onStopped: (Long) -> Unit) : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "core callback: startup")
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "core callback: shutdown")
            onStopped(attempt)
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            if (!message.isNullOrBlank()) XrayEngine.recordCoreError(code, message)
            return 0
        }
    }
}
