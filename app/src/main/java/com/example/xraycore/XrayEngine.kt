package com.example.xraycore

import android.content.Context
import android.util.Base64
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
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

    private val initialized = AtomicBoolean(false)
    private val startLock = Any()
    private val startExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xray-start").apply { isDaemon = true }
    }

    @Volatile
    private var controller: CoreController? = null

    @Volatile
    private var logger: ((String) -> Unit)? = null

    fun setLogger(sink: ((String) -> Unit)?) {
        logger = sink
    }

    /** Prepares asset paths, the Go JVM bridge and the XUDP key. Idempotent. */
    fun initialize(context: Context): Result<Unit> {
        if (initialized.get()) return Result.success(Unit)
        synchronized(startLock) {
            if (initialized.get()) return Result.success(Unit)
            return runCatching {
                val app = context.applicationContext
                Seq.setContext(app)
                val assetsDir = copyCoreAssets(app)
                Libv2ray.initCoreEnv(assetsDir.absolutePath, xudpKey(app))
                initialized.set(true)
                emit("core environment ready (${version()})")
            }.onFailure { emit("core environment failed: ${it.message}") }
        }
    }

    /** Starts the core against the given TUN fd. `tunFd <= 0` falls back to SOCKS-only mode. */
    fun start(context: Context, configJson: String, tunFd: Int): Result<Unit> {
        initialize(context).onFailure { return Result.failure(it) }
        synchronized(startLock) {
            val existing = controller
            if (existing != null && existing.isRunning) {
                return Result.failure(IllegalStateException("Core already running"))
            }
            return runCatching {
                val fresh = existing ?: Libv2ray.newCoreController(Callback()).also { controller = it }
                birth(fresh, configJson, tunFd)
                if (!fresh.isRunning) {
                    throw IllegalStateException("Xray core reported not running after startLoop")
                }
                emit("core started (tunFd=$tunFd)")
            }.onFailure {
                runCatching { controller?.stopLoop() }
                controller = null
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

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var returnedAt = 0L
        while (!ctrl.isRunning) {
            failure.get()?.let { throw it }
            if (returnedAt == 0L && callReturned.count == 0L) {
                returnedAt = System.currentTimeMillis()
            }
            if (returnedAt != 0L && System.currentTimeMillis() - returnedAt > READY_GRACE_MS) {
                throw IllegalStateException("startLoop returned without the core becoming live")
            }
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("Timed out waiting for Xray core to start")
            }
            Thread.sleep(50)
        }
        failure.get()?.let { throw it }
    }

    fun stop() {
        synchronized(startLock) {
            val ctrl = controller ?: return
            runCatching { ctrl.stopLoop() }
                .onFailure { emit("core stop failed: ${it.message}") }
                .onSuccess { emit("core stopped") }
            controller = null
        }
    }

    val isRunning: Boolean get() = controller?.isRunning == true

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("unavailable")

    fun trafficTotals(): CoreTraffic =
        CoreStatsParser.parse(runCatching { controller?.queryAllOutboundTrafficStats() }.getOrNull())

    /** Measures real round-trip latency through the running core (through the tunnel). */
    fun measureThroughTunnel(url: String): Long? =
        runCatching { controller?.measureDelay(url) }.getOrNull()?.takeIf { it > 0 }

    private fun copyCoreAssets(context: Context): File {
        val dir = File(context.filesDir, "xray-assets").apply { mkdirs() }
        listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat").forEach { name ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) return@forEach
            runCatching {
                context.assets.open(name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { emit("geo asset $name unavailable: ${it.message}") }
        }
        return dir
    }

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
        prefs.edit().putString(KEY_XUDP, generated).apply()
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
        Log.i(TAG, message)
        logger?.invoke(message)
    }

    private class Callback : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "core callback: startup")
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "core callback: shutdown")
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            if (!message.isNullOrBlank()) Log.w(TAG, "core status [$code]: $message")
            return 0
        }
    }
}
