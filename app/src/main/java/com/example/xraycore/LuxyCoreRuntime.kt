package com.example.xraycore

import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.RuleAction
import com.example.data.model.StabilitySettings
import com.example.vpn.SplitTunnelEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LuxyCoreRuntime(
    private val splitTunnelEngine: SplitTunnelEngine,
    private val onTrafficBytes: (rxDelta: Long, txDelta: Long) -> Unit = { _, _ -> },
    private val onBlockedEvent: (destination: String, ruleName: String) -> Unit = { _, _ -> }
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var currentNode: ServerProfileEntity? = null

    @Volatile
    private var currentSettings: StabilitySettings = StabilitySettings()

    @Volatile
    private var currentRules: List<RoutingRuleEntity> = emptyList()

    private val rxBytesCounter = AtomicLong(0L)
    private val txBytesCounter = AtomicLong(0L)

    val activeSocksPort: Int get() = 10808
    val isRunning: Boolean get() = running.get()

    fun updateRoutingContext(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>
    ) {
        currentNode = node
        currentSettings = settings
        currentRules = rules
    }

    fun start(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>
    ): Boolean {
        stop()
        updateRoutingContext(node, settings, rules)
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", activeSocksPort), 64)
            serverSocket = ss
            running.set(true)
            val threadCounter = AtomicInteger(0)
            val daemonFactory = ThreadFactory { runnable ->
                Thread(runnable, "luxy-core-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
            val pool = Executors.newCachedThreadPool(daemonFactory)
            executor = pool
            pool.execute { acceptLoop(ss) }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        val pool = executor
        executor = null
        if (pool != null) {
            pool.shutdownNow()
            try {
                pool.awaitTermination(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun getTrafficTotals(): Pair<Long, Long> = rxBytesCounter.get() to txBytesCounter.get()

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                val pool = executor
                if (pool == null || !running.get()) {
                    try { client.close() } catch (_: Exception) {}
                    break
                }
                try {
                    pool.execute { handleSocksClient(client) }
                } catch (_: RejectedExecutionException) {
                    try { client.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    private fun handleSocksClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 Greeting: [VER=5, NMETHODS, ...]
            val ver = input.read()
            if (ver != 5) return
            val nMethods = input.read().coerceAtLeast(0)
            if (nMethods > 0) input.readNBytesCompat(nMethods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // SOCKS5 Request: [VER=5, CMD=1(CONNECT), RSV=0, ATYP, DST.ADDR, DST.PORT]
            val header = input.readNBytesCompat(4)
            if (header.size < 4 || header[0].toInt() != 5) return
            val cmd = header[1].toInt() and 0xFF
            val atyp = header[3].toInt() and 0xFF

            var domain: String? = null
            var destIp: String? = null
            when (atyp) {
                0x01 -> {
                    val addr = input.readNBytesCompat(4)
                    if (addr.size < 4) return
                    destIp = InetAddress.getByAddress(addr).hostAddress
                }
                0x03 -> {
                    val len = input.read()
                    if (len <= 0) return
                    val domBytes = input.readNBytesCompat(len)
                    if (domBytes.size < len) return
                    domain = String(domBytes, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = input.readNBytesCompat(16)
                    if (addr.size < 16) return
                    destIp = InetAddress.getByAddress(addr).hostAddress
                }
                else -> return
            }

            val portBytes = input.readNBytesCompat(2)
            if (portBytes.size < 2) return
            val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd != 0x01) {
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val settings = currentSettings
            val rules = currentRules
            val decision = splitTunnelEngine.evaluateDestination(
                domain = domain,
                destIp = destIp,
                routingMode = settings.routingMode,
                rules = rules
            )

            // Block QUIC/UDP 443 handled natively on UDP; for TCP/SOCKS check RuleAction.BLOCK
            if (decision.action == RuleAction.BLOCK) {
                onBlockedEvent(domain ?: destIp ?: "unknown", decision.matchedRule?.name ?: "Block Policy")
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            // Connect upstream via VpnSocketProtector to bypass TUN loop
            val targetHost = domain ?: destIp ?: return
            val remote = Socket()
            VpnSocketProtector.protect(remote)
            remote.soTimeout = 30_000
            remote.connect(InetSocketAddress(targetHost, dstPort), settings.tcpHandshakeTimeoutMs.coerceAtLeast(3000))
            upstream = remote

            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // Bidirectional relay with byte accounting
            val upstreamOut = remote.getOutputStream()
            val upstreamIn = remote.getInputStream()

            val t1 = Thread {
                pumpStream(input, upstreamOut) { n ->
                    txBytesCounter.addAndGet(n)
                    onTrafficBytes(0L, n)
                }
            }
            t1.isDaemon = true
            t1.start()

            pumpStream(upstreamIn, output) { n ->
                rxBytesCounter.addAndGet(n)
                onTrafficBytes(n, 0L)
            }
            t1.join(500)
        } catch (_: Exception) {
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pumpStream(src: InputStream, dst: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = src.read(buf)
                if (read <= 0) break
                dst.write(buf, 0, read)
                dst.flush()
                onBytes(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = read(out, offset, n - offset)
            if (read < 0) return out.copyOf(offset)
            offset += read
        }
        return out
    }
}
