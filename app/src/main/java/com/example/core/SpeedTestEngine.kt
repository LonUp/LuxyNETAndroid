package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.SpeedTestAction
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestProgress
import com.example.data.model.SpeedTestResult
import com.example.data.model.SpeedTestStage
import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.round

/**
 * Probe surface used by [SpeedTestEngine]. Implementations must call `onSample` from the same
 * coroutine that invoked them — the engine is a cold flow and Compose-state emission is not
 * thread safe.
 */
interface SpeedProbeRunner {
    suspend fun measurePing204(mode: SpeedTestMode): Double
    suspend fun measureDownloadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long>

    suspend fun measureUploadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long>
}

/**
 * OkHttp probe runner.
 *
 * Every call is issued asynchronously and cancelled through [Call.cancel] on coroutine
 * cancellation, so "Stop" closes the sockets immediately instead of waiting for a socket timeout.
 * [threads] parallel streams are used for throughput windows; 1 is single-threaded, >1 is
 * multi-threaded.
 */
class OkHttpSpeedProbeRunner(
    private val activeSocksPort: Int = 10808,
    private val threads: Int = 1,
    private val pingUrls: List<String> = DEFAULT_PING_URLS,
    private val downloadUrls: List<String> = DEFAULT_DOWNLOAD_URLS,
    private val uploadUrls: List<String> = DEFAULT_UPLOAD_URLS
) : SpeedProbeRunner {

    private val streamCount: Int = threads.coerceIn(1, 8)

    private val tunneledClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", activeSocksPort)))
            .build()
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Bypass VPN TUN even while connected by protecting the underlying Socket
            .socketFactory(VpnSocketProtector.createProtectedSocketFactory())
            .build()
    }

    internal fun buildClient(mode: SpeedTestMode): OkHttpClient =
        if (mode == SpeedTestMode.TUNNELED) tunneledClient else directClient

    override suspend fun measurePing204(mode: SpeedTestMode): Double {
        val client = buildClient(mode)
        var lastError: Exception? = null
        for (url in pingUrls) {
            currentCoroutineContext().ensureActive()
            val call = client.newCall(Request.Builder().url(url).get().build())
            val start = System.nanoTime()
            try {
                call.await().use { resp ->
                    if (resp.isSuccessful || resp.code == 204) {
                        val ms = (System.nanoTime() - start) / 1_000_000.0
                        return round(ms.coerceAtLeast(2.0) * 10.0) / 10.0
                    }
                    lastError = IOException("Ping probe HTTP ${resp.code}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All 204 ping probes failed")
    }

    override suspend fun measureDownloadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long> = coroutineScope {
        val client = buildClient(mode)
        val stop = AtomicBoolean(false)
        val totalBytes = AtomicLong(0L)
        val calls = CopyOnWriteArrayList<Call>()
        val errors = ConcurrentLinkedQueue<String>()
        val startNs = System.nanoTime()
        val deadlineNs = startNs + durationMs * 1_000_000L

        val workers = (0 until streamCount).map { index ->
            val url = downloadUrls[index % downloadUrls.size]
            async(Dispatchers.IO) {
                downloadWorker(client, withThreadIndex(url, index), stop, deadlineNs, totalBytes, calls, errors)
            }
        }

        try {
            var lastBytes = 0L
            var lastNs = startNs
            var smoothed = 0.0
            while (true) {
                val now = System.nanoTime()
                val elapsedMs = (now - startNs) / 1_000_000L
                if (elapsedMs >= durationMs) break
                delay(SAMPLE_INTERVAL_MS)
                val sampleNs = System.nanoTime()
                val bytes = totalBytes.get()
                val deltaMs = ((sampleNs - lastNs) / 1_000_000L).coerceAtLeast(1L)
                val instant = SpeedTestEngine.computeMbps(bytes - lastBytes, deltaMs)
                smoothed = smooth(smoothed, instant)
                lastNs = sampleNs
                lastBytes = bytes
                onSample(
                    (sampleNs - startNs) / 1_000_000L,
                    bytes,
                    round(smoothed * 100.0) / 100.0
                )
            }
        } finally {
            stop.set(true)
            calls.forEach { runCatching { it.cancel() } }
        }
        workers.awaitAll()

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw IOException(errors.firstOrNull() ?: "Download probe failed on all streams")
        }
        onSample(elapsedMs, bytes, SpeedTestEngine.computeMbps(bytes, elapsedMs))
        bytes to elapsedMs
    }

    private fun downloadWorker(
        client: OkHttpClient,
        url: String,
        stop: AtomicBoolean,
        deadlineNs: Long,
        totalBytes: AtomicLong,
        calls: CopyOnWriteArrayList<Call>,
        errors: ConcurrentLinkedQueue<String>
    ) {
        val call = client.newCall(Request.Builder().url(url).get().build())
        calls += call
        try {
            val response: Response = runBlockingAwait(call)
            response.use { resp ->
                if (!resp.isSuccessful) {
                    errors += "HTTP ${resp.code}"
                    return
                }
                val stream = resp.body?.byteStream() ?: return
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (!stop.get() && !call.isCanceled() && System.nanoTime() < deadlineNs) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    totalBytes.addAndGet(read.toLong())
                }
            }
        } catch (e: Exception) {
            if (!stop.get() && !call.isCanceled()) errors += (e.message ?: e.javaClass.simpleName)
        } finally {
            calls.remove(call)
        }
    }

    override suspend fun measureUploadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long> = coroutineScope {
        val client = buildClient(mode)
        val stop = AtomicBoolean(false)
        val totalBytes = AtomicLong(0L)
        val calls = CopyOnWriteArrayList<Call>()
        val errors = ConcurrentLinkedQueue<String>()
        val startNs = System.nanoTime()
        val deadlineNs = startNs + durationMs * 1_000_000L
        val chunk = ByteArray(UPLOAD_CHUNK_BYTES) { 0x5A }

        val workers = (0 until streamCount).map { index ->
            val url = withThreadIndex(uploadUrls[index % uploadUrls.size], index)
            async(Dispatchers.IO) {
                uploadWorker(client, url, chunk, stop, deadlineNs, totalBytes, calls, errors)
            }
        }

        try {
            var lastBytes = 0L
            var lastNs = startNs
            var smoothed = 0.0
            while (true) {
                val now = System.nanoTime()
                val elapsedMs = (now - startNs) / 1_000_000L
                if (elapsedMs >= durationMs) break
                delay(SAMPLE_INTERVAL_MS)
                val sampleNs = System.nanoTime()
                val bytes = totalBytes.get()
                val deltaMs = ((sampleNs - lastNs) / 1_000_000L).coerceAtLeast(1L)
                val instant = SpeedTestEngine.computeMbps(bytes - lastBytes, deltaMs)
                smoothed = smooth(smoothed, instant)
                lastNs = sampleNs
                lastBytes = bytes
                onSample(
                    (sampleNs - startNs) / 1_000_000L,
                    bytes,
                    round(smoothed * 100.0) / 100.0
                )
            }
        } finally {
            stop.set(true)
            calls.forEach { runCatching { it.cancel() } }
        }
        workers.awaitAll()

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw IOException(errors.firstOrNull() ?: "Upload probe failed on all streams")
        }
        onSample(elapsedMs, bytes, SpeedTestEngine.computeMbps(bytes, elapsedMs))
        bytes to elapsedMs
    }

    private fun uploadWorker(
        client: OkHttpClient,
        url: String,
        chunk: ByteArray,
        stop: AtomicBoolean,
        deadlineNs: Long,
        totalBytes: AtomicLong,
        calls: CopyOnWriteArrayList<Call>,
        errors: ConcurrentLinkedQueue<String>
    ) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = -1L

            override fun writeTo(sink: BufferedSink) {
                while (!stop.get() && System.nanoTime() < deadlineNs) {
                    sink.write(chunk)
                    sink.flush()
                    totalBytes.addAndGet(chunk.size.toLong())
                }
            }
        }
        val call = client.newCall(Request.Builder().url(url).post(body).build())
        calls += call
        try {
            runBlockingAwait(call).use { resp ->
                if (!resp.isSuccessful && !stop.get()) errors += "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            if (!stop.get() && !call.isCanceled()) errors += (e.message ?: e.javaClass.simpleName)
        } finally {
            calls.remove(call)
        }
    }

    /** Blocking bridge for worker threads: the coroutine layer owns cancellation. */
    private fun runBlockingAwait(call: Call): Response {
        val latch = java.util.concurrent.CountDownLatch(1)
        var resolved: Response? = null
        var failure: IOException? = null
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure = e
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                resolved = response
                latch.countDown()
            }
        })
        latch.await()
        failure?.let { throw it }
        return resolved ?: throw IOException("No response")
    }

    private fun smooth(previous: Double, sample: Double): Double =
        if (previous <= 0.0) sample else previous * 0.65 + sample * 0.35

    private fun withThreadIndex(url: String, index: Int): String {
        if (index == 0) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}stream=$index"
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 250L
        private const val READ_BUFFER_BYTES = 32 * 1024
        private const val UPLOAD_CHUNK_BYTES = 64 * 1024
        val DEFAULT_PING_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        val DEFAULT_DOWNLOAD_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=50000000",
            "https://proof.ovh.net/files/10Mb.dat"
        )
        val DEFAULT_UPLOAD_URLS = listOf(
            "https://speed.cloudflare.com/__up"
        )
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

object SpeedTestEngine {

    fun computeMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val raw = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
        return round(raw * 10.0) / 10.0
    }

    fun computeJitter(samples: List<Double>): Double {
        if (samples.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until samples.size) {
            sum += abs(samples[i] - samples[i - 1])
        }
        return round((sum / (samples.size - 1)) * 10.0) / 10.0
    }

    fun computeMbps(bytes: Long, elapsedMs: Long): Double {
        if (bytes <= 0L || elapsedMs <= 0L) return 0.0
        val mbps = (bytes * 8.0) / ((elapsedMs / 1000.0) * 1_000_000.0)
        return round(mbps * 100.0) / 100.0
    }

    fun resolveSafeExitLabel(mode: SpeedTestMode, activeNode: ServerProfileEntity?): String {
        if (mode == SpeedTestMode.DIRECT) {
            return "Direct ISP Baseline (Protected Bypass Socket)"
        }
        if (activeNode == null) return "No Active Node"
        return if (activeNode.isLocked) {
            "${activeNode.name} • ${EncryptedConfigVault.VAULT_REDACTED_VALUE}"
        } else {
            "${activeNode.name} • ${activeNode.address}:${activeNode.port}"
        }
    }

    /** Full dual-mode benchmark; kept for callers and tests written against the original API. */
    fun runDualModeSpeedTest(
        mode: SpeedTestMode,
        connectionState: ConnectionState,
        activeNode: ServerProfileEntity?,
        activeSocksPort: Int = 10808,
        allowDisconnectedNodeProbe: Boolean = false,
        pingCount: Int = 6,
        phaseWindowMs: Long = 7_000L,
        runner: SpeedProbeRunner = OkHttpSpeedProbeRunner(activeSocksPort)
    ): Flow<Pair<SpeedTestProgress, SpeedTestResult?>> = runSpeedTest(
        mode = mode,
        action = SpeedTestAction.FULL,
        threads = 1,
        connectionState = connectionState,
        activeNode = activeNode,
        activeSocksPort = activeSocksPort,
        allowDisconnectedNodeProbe = allowDisconnectedNodeProbe,
        pingCount = pingCount,
        phaseWindowMs = phaseWindowMs,
        runner = runner
    )

    /**
     * Runs only the phases selected by [action] with [threads] parallel streams for throughput.
     *
     * Phase failures degrade to warnings so one blocked URL cannot kill the whole benchmark, and
     * cancellation propagates immediately (the runner closes its sockets).
     */
    fun runSpeedTest(
        mode: SpeedTestMode,
        action: SpeedTestAction,
        threads: Int,
        connectionState: ConnectionState,
        activeNode: ServerProfileEntity?,
        activeSocksPort: Int = 10808,
        allowDisconnectedNodeProbe: Boolean = false,
        pingCount: Int = 6,
        phaseWindowMs: Long = 7_000L,
        runner: SpeedProbeRunner = OkHttpSpeedProbeRunner(activeSocksPort, threads)
    ): Flow<Pair<SpeedTestProgress, SpeedTestResult?>> = flow {
        val exitLabel = resolveSafeExitLabel(mode, activeNode)
        val disconnectedHandshake = allowDisconnectedNodeProbe &&
            connectionState != ConnectionState.CONNECTED &&
            activeNode != null

        if (mode == SpeedTestMode.TUNNELED &&
            connectionState != ConnectionState.CONNECTED &&
            !allowDisconnectedNodeProbe
        ) {
            throw IllegalStateException("VPN Disconnected")
        }

        val warnings = mutableListOf<String>()
        val runPing = action == SpeedTestAction.FULL || action == SpeedTestAction.PING
        val runDownload = action == SpeedTestAction.FULL || action == SpeedTestAction.DOWNLOAD
        val runUpload = action == SpeedTestAction.FULL || action == SpeedTestAction.UPLOAD

        // Phase 1: ping / jitter
        val pingSamples = mutableListOf<Double>()
        if (runPing) {
            for (i in 0 until pingCount) {
                currentCoroutineContext().ensureActive()
                try {
                    val rtt = withTimeoutOrNull(PING_PROBE_TIMEOUT_MS) {
                        if (disconnectedHandshake) {
                            measureDirectNodeTcpHandshake(activeNode!!)
                        } else {
                            runner.measurePing204(mode)
                        }
                    }
                    if (rtt != null) {
                        pingSamples += rtt
                    } else {
                        warnings += "Ping probe ${i + 1} timed out"
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    warnings += "Ping probe ${i + 1} failed: ${e.message ?: e.javaClass.simpleName}"
                }
                emit(
                    SpeedTestProgress(
                        mode = mode,
                        action = action,
                        threads = threads,
                        stage = SpeedTestStage.PING,
                        progressFraction = pingFraction(action, i + 1, pingCount),
                        currentMbps = 0.0,
                        pingMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeMedian),
                        jitterMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeJitter),
                        downloadMbps = null,
                        uploadMbps = null,
                        exitLabel = exitLabel,
                        statusNote = when {
                            disconnectedHandshake -> "Handshake latency probe (${i + 1}/$pingCount)…"
                            else -> "Ping ${i + 1}/$pingCount…"
                        },
                        warnings = warnings.toList()
                    ) to null
                )
            }
        }
        val finalPing = computeMedian(pingSamples)
        val finalJitter = computeJitter(pingSamples)
        if (runPing && pingSamples.isEmpty()) {
            warnings += "Ping unavailable on both probe endpoints"
        }

        if (action == SpeedTestAction.PING) {
            val result = SpeedTestResult(
                mode = mode,
                action = action,
                threads = threads,
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                exitLabel = exitLabel,
                timestamp = System.currentTimeMillis(),
                probeCount = pingSamples.size,
                isHandshakeOnly = disconnectedHandshake,
                warnings = warnings.toList()
            )
            emit(
                SpeedTestProgress(
                    mode = mode,
                    action = action,
                    threads = threads,
                    stage = SpeedTestStage.COMPLETE,
                    progressFraction = 1f,
                    currentMbps = 0.0,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = null,
                    uploadMbps = null,
                    exitLabel = exitLabel,
                    statusNote = "Ping complete",
                    warnings = warnings.toList()
                ) to result
            )
            return@flow
        }

        if (disconnectedHandshake) {
            val result = SpeedTestResult(
                mode = mode,
                action = action,
                threads = threads,
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                exitLabel = exitLabel,
                timestamp = System.currentTimeMillis(),
                probeCount = pingSamples.size,
                isHandshakeOnly = true,
                warnings = warnings.toList()
            )
            emit(
                SpeedTestProgress(
                    mode = mode,
                    action = action,
                    threads = threads,
                    stage = SpeedTestStage.COMPLETE,
                    progressFraction = 1f,
                    currentMbps = 0.0,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = 0.0,
                    uploadMbps = 0.0,
                    exitLabel = exitLabel,
                    statusNote = "Node handshake latency verified (connect VPN for throughput)",
                    warnings = warnings.toList()
                ) to result
            )
            return@flow
        }

        // Phase 2: bounded download window
        var finalDownload: Double? = null
        var finalUpload: Double? = null

        if (runDownload) {
            try {
                val measured = withTimeoutOrNull(phaseWindowMs + PHASE_WATCHDOG_GRACE_MS) {
                    runner.measureDownloadWindow(mode, phaseWindowMs) { elapsed, bytesSoFar, instant ->
                    val mbps = if (instant > 0.0) instant else computeMbps(bytesSoFar, elapsed)
                    emit(
                        SpeedTestProgress(
                            mode = mode,
                            action = action,
                            threads = threads,
                            stage = SpeedTestStage.DOWNLOAD,
                            progressFraction = phaseFraction(elapsed, phaseWindowMs, 0.25f),
                            currentMbps = mbps,
                            pingMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeMedian),
                            jitterMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeJitter),
                            downloadMbps = null,
                            uploadMbps = null,
                            exitLabel = exitLabel,
                            statusNote = if (mode == SpeedTestMode.TUNNELED) {
                                "Download via SOCKS5 :$activeSocksPort • ${threadsLabel(threads)}"
                            } else {
                                "Direct ISP download • ${threadsLabel(threads)}"
                            },
                            warnings = warnings.toList()
                        ) to null
                    )
                    }
                }
                if (measured == null) {
                    warnings += "Download timed out"
                } else {
                    finalDownload = computeMbps(measured.first, measured.second)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                warnings += "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // Phase 3: bounded upload window
        if (runUpload) {
            try {
                val measured = withTimeoutOrNull(phaseWindowMs + PHASE_WATCHDOG_GRACE_MS) {
                    runner.measureUploadWindow(mode, phaseWindowMs) { elapsed, bytesSoFar, instant ->
                    val mbps = if (instant > 0.0) instant else computeMbps(bytesSoFar, elapsed)
                    emit(
                        SpeedTestProgress(
                            mode = mode,
                            action = action,
                            threads = threads,
                            stage = SpeedTestStage.UPLOAD,
                            progressFraction = phaseFraction(elapsed, phaseWindowMs, 0.6f),
                            currentMbps = mbps,
                            pingMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeMedian),
                            jitterMs = pingSamples.takeIf { it.isNotEmpty() }?.let(::computeJitter),
                            downloadMbps = finalDownload,
                            uploadMbps = null,
                            exitLabel = exitLabel,
                            statusNote = if (mode == SpeedTestMode.TUNNELED) {
                                "Upload via SOCKS5 :$activeSocksPort • ${threadsLabel(threads)}"
                            } else {
                                "Direct ISP upload • ${threadsLabel(threads)}"
                            },
                            warnings = warnings.toList()
                        ) to null
                    )
                    }
                }
                if (measured == null) {
                    warnings += "Upload timed out"
                } else {
                    finalUpload = computeMbps(measured.first, measured.second)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                warnings += "Upload failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val measuredAnything = pingSamples.isNotEmpty() || finalDownload != null || finalUpload != null
        if (!measuredAnything) {
            val detail = warnings.joinToString("; ").ifBlank { "no phase produced a measurement" }
            throw IllegalStateException("Speed test failed: $detail")
        }

        val finalResult = SpeedTestResult(
            mode = mode,
            action = action,
            threads = threads,
            pingMs = finalPing,
            jitterMs = finalJitter,
            downloadMbps = finalDownload ?: 0.0,
            uploadMbps = finalUpload ?: 0.0,
            exitLabel = exitLabel,
            timestamp = System.currentTimeMillis(),
            probeCount = pingSamples.size,
            warnings = warnings.toList()
        )

        emit(
            SpeedTestProgress(
                mode = mode,
                action = action,
                threads = threads,
                stage = SpeedTestStage.COMPLETE,
                progressFraction = 1f,
                currentMbps = finalDownload ?: finalUpload ?: 0.0,
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = finalDownload,
                uploadMbps = finalUpload,
                exitLabel = exitLabel,
                statusNote = if (warnings.isEmpty()) "Benchmark complete" else "Completed with warnings",
                warnings = warnings.toList()
            ) to finalResult
        )
    }.flowOn(Dispatchers.IO)

    private const val PING_PROBE_TIMEOUT_MS = 3_000L
    private const val PHASE_WATCHDOG_GRACE_MS = 8_000L

    private fun threadsLabel(threads: Int): String =
        if (threads <= 1) "single stream" else "$threads streams"

    private fun pingFraction(action: SpeedTestAction, done: Int, total: Int): Float {
        val share = if (action == SpeedTestAction.PING) 1f else 0.25f
        return ((done.toFloat() / total.coerceAtLeast(1)) * share).coerceIn(0.02f, share)
    }

    private fun phaseFraction(elapsedMs: Long, windowMs: Long, start: Float): Float {
        val span = 1f - start - 0.05f
        return (start + (elapsedMs.toFloat() / windowMs.coerceAtLeast(1L)) * span).coerceIn(start, start + span)
    }

    private fun measureDirectNodeTcpHandshake(node: ServerProfileEntity): Double {
        val start = System.nanoTime()
        Socket().use { socket ->
            VpnSocketProtector.protect(socket)
            socket.connect(InetSocketAddress(node.address, node.port), 3000)
        }
        val ms = (System.nanoTime() - start) / 1_000_000.0
        return round(ms.coerceAtLeast(2.0) * 10.0) / 10.0
    }
}
