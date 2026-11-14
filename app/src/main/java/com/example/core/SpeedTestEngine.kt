package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestProgress
import com.example.data.model.SpeedTestResult
import com.example.data.model.SpeedTestStage
import com.example.xraycore.VpnSocketProtector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round

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

class OkHttpSpeedProbeRunner(
    private val activeSocksPort: Int = 10808,
    private val pingUrls: List<String> = DEFAULT_PING_URLS,
    private val downloadUrls: List<String> = DEFAULT_DOWNLOAD_URLS,
    private val uploadUrls: List<String> = DEFAULT_UPLOAD_URLS
) : SpeedProbeRunner {

    private val tunneledClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", activeSocksPort)))
            .build()
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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
            val req = Request.Builder().url(url).get().build()
            val start = System.nanoTime()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 204) {
                        val ms = (System.nanoTime() - start) / 1_000_000.0
                        return round(ms.coerceAtLeast(2.0) * 10.0) / 10.0
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("All 204 ping probes failed")
    }

    override suspend fun measureDownloadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long> {
        val client = buildClient(mode)
        var lastError: Exception? = null
        for (url in downloadUrls) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder().url(url).get().build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IllegalStateException("Download probe HTTP ${resp.code}")
                        return@use
                    }
                    val stream = resp.body?.byteStream() ?: return@use
                    val buf = ByteArray(16 * 1024)
                    val startNs = System.nanoTime()
                    var lastEmitNs = startNs
                    var windowBytes = 0L
                    var totalBytes = 0L

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buf)
                        if (read <= 0) break
                        totalBytes += read
                        windowBytes += read
                        val nowNs = System.nanoTime()
                        val elapsedMs = (nowNs - startNs) / 1_000_000L
                        val sinceEmitMs = (nowNs - lastEmitNs) / 1_000_000L
                        if (sinceEmitMs >= 250L) {
                            val instantMbps = SpeedTestEngine.computeMbps(windowBytes, sinceEmitMs)
                            onSample(elapsedMs, totalBytes, instantMbps)
                            lastEmitNs = nowNs
                            windowBytes = 0L
                        }
                        if (elapsedMs >= durationMs) break
                    }
                    val totalElapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    onSample(totalElapsedMs, totalBytes, SpeedTestEngine.computeMbps(totalBytes, totalElapsedMs))
                    return totalBytes to totalElapsedMs
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Download speed probe failed")
    }

    override suspend fun measureUploadWindow(
        mode: SpeedTestMode,
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, bytesSoFar: Long, instantMbps: Double) -> Unit
    ): Pair<Long, Long> = coroutineScope {
        val client = buildClient(mode)
        var lastError: Exception? = null
        val chunk = ByteArray(16 * 1024) { 0x5A }

        for (url in uploadUrls) {
            currentCoroutineContext().ensureActive()
            val startNs = System.nanoTime()
            val uploadedBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val prevSampleBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val prevSampleNs = java.util.concurrent.atomic.AtomicLong(startNs)

            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    while (true) {
                        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                        if (elapsedMs >= durationMs || Thread.currentThread().isInterrupted) break
                        sink.write(chunk)
                        sink.flush()
                        uploadedBytes.addAndGet(chunk.size.toLong())
                    }
                }
            }

            val ticker = launch {
                while (true) {
                    delay(250L)
                    val nowNs = System.nanoTime()
                    val elapsedMs = ((nowNs - startNs) / 1_000_000L).coerceAtLeast(1L)
                    val total = uploadedBytes.get()
                    val prevB = prevSampleBytes.getAndSet(total)
                    val prevT = prevSampleNs.getAndSet(nowNs)
                    val deltaMs = ((nowNs - prevT) / 1_000_000L).coerceAtLeast(1L)
                    val instantMbps = SpeedTestEngine.computeMbps((total - prevB).coerceAtLeast(0L), deltaMs)
                    onSample(elapsedMs, total, instantMbps)
                }
            }

            try {
                val req = Request.Builder().url(url).post(body).build()
                val res = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        val total = uploadedBytes.get()
                        if (resp.isSuccessful || total > 0L) {
                            total to elapsedMs
                        } else {
                            null
                        }
                    }
                }
                ticker.cancel()
                if (res != null) {
                    onSample(res.second, res.first, SpeedTestEngine.computeMbps(res.first, res.second))
                    return@coroutineScope res
                }
            } catch (ce: CancellationException) {
                ticker.cancel()
                throw ce
            } catch (e: Exception) {
                ticker.cancel()
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Upload speed probe failed")
    }

    companion object {
        val DEFAULT_PING_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        val DEFAULT_DOWNLOAD_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://proof.ovh.net/files/10Mb.dat"
        )
        val DEFAULT_UPLOAD_URLS = listOf(
            "https://speed.cloudflare.com/__up"
        )
    }
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

    fun runDualModeSpeedTest(
        mode: SpeedTestMode,
        connectionState: ConnectionState,
        activeNode: ServerProfileEntity?,
        activeSocksPort: Int = 10808,
        allowDisconnectedNodeProbe: Boolean = false,
        pingCount: Int = 6,
        phaseWindowMs: Long = 7_000L,
        runner: SpeedProbeRunner = OkHttpSpeedProbeRunner(activeSocksPort)
    ): Flow<Pair<SpeedTestProgress, SpeedTestResult?>> = flow {
        val exitLabel = resolveSafeExitLabel(mode, activeNode)

        if (mode == SpeedTestMode.TUNNELED &&
            connectionState != ConnectionState.CONNECTED &&
            !allowDisconnectedNodeProbe
        ) {
            throw IllegalStateException("VPN Disconnected")
        }

        // Phase 1: 6 Sequential Ping & Jitter Probes
        val pingSamples = mutableListOf<Double>()
        for (i in 0 until pingCount) {
            currentCoroutineContext().ensureActive()
            val rtt = if (allowDisconnectedNodeProbe && connectionState != ConnectionState.CONNECTED && activeNode != null) {
                measureDirectNodeTcpHandshake(activeNode)
            } else {
                runner.measurePing204(mode)
            }
            pingSamples += rtt
            val median = computeMedian(pingSamples)
            val jitter = computeJitter(pingSamples)
            emit(
                SpeedTestProgress(
                    mode = mode,
                    stage = SpeedTestStage.PING,
                    progressFraction = (i + 1).toFloat() / (pingCount * 3f),
                    currentMbps = 0.0,
                    pingMs = median,
                    jitterMs = jitter,
                    downloadMbps = null,
                    uploadMbps = null,
                    exitLabel = exitLabel,
                    statusNote = if (allowDisconnectedNodeProbe && connectionState != ConnectionState.CONNECTED) {
                        "Handshake Latency Probe (VPN Disconnected)"
                    } else {
                        "Measuring ping (${i + 1}/$pingCount)…"
                    }
                ) to null
            )
        }

        val finalPing = computeMedian(pingSamples)
        val finalJitter = computeJitter(pingSamples)

        if (allowDisconnectedNodeProbe && connectionState != ConnectionState.CONNECTED) {
            val result = SpeedTestResult(
                mode = mode,
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                exitLabel = exitLabel,
                timestamp = System.currentTimeMillis(),
                probeCount = pingSamples.size,
                isHandshakeOnly = true
            )
            emit(
                SpeedTestProgress(
                    mode = mode,
                    stage = SpeedTestStage.COMPLETE,
                    progressFraction = 1f,
                    currentMbps = 0.0,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = 0.0,
                    uploadMbps = 0.0,
                    exitLabel = exitLabel,
                    statusNote = "Node handshake latency verified (connect VPN for throughput)"
                ) to result
            )
            return@flow
        }

        // Phase 2: Bounded Download Window
        val (dlBytes, dlMs) = runner.measureDownloadWindow(mode, phaseWindowMs) { elapsed, bytesSoFar, instant ->
            val mbps = if (instant > 0.0) instant else computeMbps(bytesSoFar, elapsed)
            emit(
                SpeedTestProgress(
                    mode = mode,
                    stage = SpeedTestStage.DOWNLOAD,
                    progressFraction = 0.33f + ((elapsed.toFloat() / phaseWindowMs.coerceAtLeast(1L)) * 0.33f).coerceAtMost(0.33f),
                    currentMbps = mbps,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = mbps,
                    uploadMbps = null,
                    exitLabel = exitLabel,
                    statusNote = if (mode == SpeedTestMode.TUNNELED) {
                        "Testing download via SOCKS5 :$activeSocksPort…"
                    } else {
                        "Testing direct ISP download…"
                    }
                ) to null
            )
        }
        val finalDownload = computeMbps(dlBytes, dlMs)

        // Phase 3: Bounded Upload Window
        val (ulBytes, ulMs) = runner.measureUploadWindow(mode, phaseWindowMs) { elapsed, bytesSoFar, instant ->
            val mbps = if (instant > 0.0) instant else computeMbps(bytesSoFar, elapsed)
            emit(
                SpeedTestProgress(
                    mode = mode,
                    stage = SpeedTestStage.UPLOAD,
                    progressFraction = 0.66f + ((elapsed.toFloat() / phaseWindowMs.coerceAtLeast(1L)) * 0.33f).coerceAtMost(0.33f),
                    currentMbps = mbps,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = finalDownload,
                    uploadMbps = mbps,
                    exitLabel = exitLabel,
                    statusNote = if (mode == SpeedTestMode.TUNNELED) {
                        "Testing upload via SOCKS5 :$activeSocksPort…"
                    } else {
                        "Testing direct ISP upload…"
                    }
                ) to null
            )
        }
        val finalUpload = computeMbps(ulBytes, ulMs)

        val finalResult = SpeedTestResult(
            mode = mode,
            pingMs = finalPing,
            jitterMs = finalJitter,
            downloadMbps = finalDownload,
            uploadMbps = finalUpload,
            exitLabel = exitLabel,
            timestamp = System.currentTimeMillis(),
            probeCount = pingSamples.size
        )

        emit(
            SpeedTestProgress(
                mode = mode,
                stage = SpeedTestStage.COMPLETE,
                progressFraction = 1f,
                currentMbps = finalDownload,
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = finalDownload,
                uploadMbps = finalUpload,
                exitLabel = exitLabel,
                statusNote = "Benchmark complete"
            ) to finalResult
        )
    }.flowOn(Dispatchers.IO)

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
