package com.example.xraycore

import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.RuleAction
import com.example.data.model.StabilitySettings
import com.example.vpn.ConnectionOwnerResolver
import com.example.vpn.OriginalDestinationFlow
import com.example.vpn.SplitTunnelEngine
import com.example.vpn.UidFlowDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class CoreRuntimeStatus(
    val isRunning: Boolean = false,
    val socksPort: Int = -1,
    val generation: Int = 0,
    val primaryOutboundProtocol: String = "none",
    val activeDialerProxy: String? = null,
    val activeConnections: Int = 0
)

data class TunForwardVerdict(
    val uidDecision: UidFlowDecision = UidFlowDecision.ALLOW,
    val ruleAction: RuleAction = RuleAction.PROXY,
    val matchedRuleName: String? = null,
    val dnsForwarded: Boolean = false
)

internal data class CoreRuntimeSnapshot(
    val compiledJson: String,
    val node: ServerProfileEntity?,
    val settings: StabilitySettings,
    val rules: List<RoutingRuleEntity>,
    val relayNode: ServerProfileEntity?,
    val primaryOutboundProtocol: String,
    val activeDialerProxy: String?,
    val generation: Int
)

/**
 * In-process Xray-compatible local SOCKS5 inbound + TUN packet dispatcher for LuxyVPN.
 *
 * - Binds 127.0.0.1:<socksPort> as configured by XrayConfigCompiler's "socks-in" inbound.
 * - Supports atomic configuration hot-reload (`hotReload`) for Adaptive Fallback Stages 0..4
 *   without closing the listening ServerSocket or interrupting the VpnService TUN descriptor.
 * - Enforces SplitTunnelEngine pre-FakeDNS routing and protects every outbound socket via
 *   VpnSocketProtector so traffic never loops back into the TUN interface.
 * - Forwards TUN IPv4/UDP port 53 queries through MultiResolverDnsEngine and injects checksummed
 *   IPv4/UDP DNS responses back into the TUN OutputStream.
 */
object LuxyCoreRuntime {

    private val snapshotRef = AtomicReference<CoreRuntimeSnapshot?>(null)
    private val generationCounter = AtomicInteger(0)
    private val activeSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var runtimeScope: CoroutineScope? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var boundPort: Int = -1

    @Synchronized
    fun start(
        compiledJson: String,
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null,
        preferredPort: Int = 10808
    ): CoreRuntimeStatus {
        val parsed = parseCompiledSnapshot(
            compiledJson = compiledJson,
            node = node,
            settings = settings,
            rules = rules,
            relayNode = relayNode,
            generation = generationCounter.incrementAndGet()
        )
        snapshotRef.set(parsed)

        val currentSocket = serverSocket
        if (currentSocket != null && !currentSocket.isClosed) {
            return status()
        }

        val targetPort = parseInboundPort(compiledJson, preferredPort)
        val loopback = InetAddress.getByName("127.0.0.1")
        val bound = try {
            ServerSocket(targetPort, 64, loopback)
        } catch (_: Exception) {
            ServerSocket(0, 64, loopback)
        }
        serverSocket = bound
        boundPort = bound.localPort

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runtimeScope = scope
        acceptJob = scope.launch {
            while (isActive && !bound.isClosed) {
                try {
                    val client = bound.accept()
                    activeSockets.add(client)
                    scope.launch {
                        handleSocks5Client(client)
                    }
                } catch (_: Exception) {
                    if (bound.isClosed) break
                }
            }
        }
        return status()
    }

    @Synchronized
    fun hotReload(
        compiledJson: String,
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): CoreRuntimeStatus {
        val current = serverSocket
        if (current == null || current.isClosed) {
            return start(compiledJson, node, settings, rules, relayNode)
        }
        val next = parseCompiledSnapshot(
            compiledJson = compiledJson,
            node = node,
            settings = settings,
            rules = rules,
            relayNode = relayNode,
            generation = generationCounter.incrementAndGet()
        )
        snapshotRef.set(next)
        return status()
    }

    @Synchronized
    fun stop() {
        snapshotRef.set(null)
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        boundPort = -1
        activeSockets.toList().forEach { sock ->
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
        activeSockets.clear()
        runtimeScope?.cancel()
        runtimeScope = null
    }

    fun status(): CoreRuntimeStatus {
        val snap = snapshotRef.get()
        val running = serverSocket?.let { !it.isClosed } == true && snap != null
        return CoreRuntimeStatus(
            isRunning = running,
            socksPort = if (running) boundPort else -1,
            generation = snap?.generation ?: generationCounter.get(),
            primaryOutboundProtocol = snap?.primaryOutboundProtocol ?: "none",
            activeDialerProxy = snap?.activeDialerProxy,
            activeConnections = activeSockets.size
        )
    }

    fun activeCompiledConfigJson(): String = snapshotRef.get()?.compiledJson.orEmpty()

    /**
     * Evaluates a raw IPv4 packet read from VpnService's TUN interface against UID isolation and
     * pre-FakeDNS routing rules, and dispatches IPv4/UDP port-53 DNS packets through the
     * VpnSocketProtector-protected MultiResolver pool.
     */
    fun forwardTunPacket(
        packet: ByteArray,
        length: Int,
        tunOutput: OutputStream?,
        ownerResolver: ConnectionOwnerResolver?,
        allowedUids: Set<Int>,
        blockedUids: Set<Int>,
        selfUid: Int,
        settingsOverride: StabilitySettings? = null,
        rulesOverride: List<RoutingRuleEntity>? = null,
        rawDnsResolver: (ByteArray, StabilitySettings) -> ByteArray? = { q, s ->
            MultiResolverDnsEngine.forwardRawDnsQuery(q, s)
        }
    ): TunForwardVerdict {
        val parsed = SplitTunnelEngine.parseIpv4FlowHeader(packet, length)
            ?: return TunForwardVerdict()
        val snap = snapshotRef.get()
        val settings = settingsOverride ?: snap?.settings ?: StabilitySettings()
        val rules = rulesOverride ?: snap?.rules ?: emptyList()

        val flow = OriginalDestinationFlow(
            originalDomain = null,
            originalIp = parsed.destIp,
            resolvedOrFakeIp = parsed.destIp,
            port = parsed.destPort,
            protocol = parsed.protocol,
            sourceIp = parsed.sourceIp,
            sourcePort = parsed.sourcePort
        )

        val uid = if (ownerResolver != null) {
            SplitTunnelEngine.resolveFlowOwnerUid(flow, ownerResolver)
        } else {
            selfUid
        }
        val uidDecision = SplitTunnelEngine.evaluateUidFlowPolicy(
            uid = uid,
            selfUid = selfUid,
            mode = settings.appSplitTunnelMode,
            allowedUids = allowedUids,
            blockedUids = blockedUids,
            strictUidIsolation = settings.strictUidIsolationEnabled
        )
        if (uidDecision == UidFlowDecision.DENY_UNRESOLVED_UID || uidDecision == UidFlowDecision.DENY_EXCLUDED_UID) {
            return TunForwardVerdict(uidDecision = uidDecision, ruleAction = RuleAction.BLOCK)
        }

        val routeDecision = SplitTunnelEngine.evaluatePreFakeDnsRouting(
            flow = flow,
            routingMode = settings.routingMode,
            rules = rules,
            blockQuicUdp = settings.blockQuicUdp
        )
        if (routeDecision.action == RuleAction.BLOCK) {
            return TunForwardVerdict(
                uidDecision = uidDecision,
                ruleAction = RuleAction.BLOCK,
                matchedRuleName = routeDecision.matchedRuleName
            )
        }

        // Intercept TUN UDP/53 DNS queries -> forward via VpnSocketProtector-protected MultiResolverDnsEngine
        if (parsed.protocol == android.system.OsConstants.IPPROTO_UDP && parsed.destPort == 53) {
            val ihlBytes = (packet[0].toInt() and 0x0F) * 4
            val udpHeaderEnd = ihlBytes + 8
            if (length > udpHeaderEnd + 12) {
                val dnsQuery = packet.copyOfRange(udpHeaderEnd, length)
                val dnsResp = rawDnsResolver(dnsQuery, settings)
                if (dnsResp != null && tunOutput != null) {
                    val replyIpPacket = buildIpv4UdpReplyPacket(
                        srcIpBytes = packet.copyOfRange(16, 20),
                        dstIpBytes = packet.copyOfRange(12, 16),
                        srcPort = parsed.destPort,
                        dstPort = parsed.sourcePort,
                        payload = dnsResp
                    )
                    synchronized(tunOutput) {
                        tunOutput.write(replyIpPacket)
                        tunOutput.flush()
                    }
                    return TunForwardVerdict(
                        uidDecision = uidDecision,
                        ruleAction = routeDecision.action,
                        matchedRuleName = routeDecision.matchedRuleName,
                        dnsForwarded = true
                    )
                }
            }
        }

        return TunForwardVerdict(
            uidDecision = uidDecision,
            ruleAction = routeDecision.action,
            matchedRuleName = routeDecision.matchedRuleName
        )
    }

    fun buildIpv4UdpReplyPacket(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        // IPv4 header (20 bytes)
        out[0] = 0x45.toByte()
        out[1] = 0x00
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = 0x00
        out[5] = 0x01
        out[6] = 0x40.toByte() // DF
        out[7] = 0x00
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        out[10] = 0x00 // Checksum placeholder
        out[11] = 0x00
        System.arraycopy(srcIpBytes, 0, out, 12, 4)
        System.arraycopy(dstIpBytes, 0, out, 16, 4)

        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val ipChecksum = sum.inv() and 0xFFFF
        out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP header (8 bytes)
        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0x00 // IPv4 UDP checksum optional (0)
        out[27] = 0x00

        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun handleSocks5Client(client: Socket) {
        var outbound: Socket? = null
        try {
            client.soTimeout = 10_000
            val inp = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            val ver = inp.readUnsignedByte()
            if (ver != 0x05) return
            val nMethods = inp.readUnsignedByte()
            val methods = ByteArray(nMethods)
            inp.readFully(methods)
            if (0x00.toByte() !in methods) {
                out.write(byteArrayOf(0x05, 0xFF.toByte()))
                out.flush()
                return
            }
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            val reqVer = inp.readUnsignedByte()
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte() // RSV
            val atyp = inp.readUnsignedByte()
            if (reqVer != 0x05 || cmd != 0x01) {
                // Command not supported
                out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                return
            }

            val targetHost = when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    inp.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress.orEmpty()
                }
                0x03 -> {
                    val len = inp.readUnsignedByte()
                    val nameBytes = ByteArray(len)
                    inp.readFully(nameBytes)
                    String(nameBytes, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    inp.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress.orEmpty()
                }
                else -> return
            }
            val targetPort = inp.readUnsignedShort()

            val snap = snapshotRef.get()
            if (snap != null) {
                val flow = OriginalDestinationFlow(
                    originalDomain = if (atyp == 0x03) targetHost else null,
                    originalIp = if (atyp != 0x03) targetHost else "",
                    resolvedOrFakeIp = targetHost,
                    port = targetPort
                )
                val decision = SplitTunnelEngine.evaluatePreFakeDnsRouting(
                    flow = flow,
                    routingMode = snap.settings.routingMode,
                    rules = snap.rules,
                    blockQuicUdp = snap.settings.blockQuicUdp
                )
                if (decision.action == RuleAction.BLOCK) {
                    // 0x02 = connection not allowed by ruleset
                    out.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    return
                }
            }

            outbound = VpnSocketProtector.createProtectedSocket().apply {
                soTimeout = snap?.settings?.tcpHandshakeTimeoutMs ?: 5000
                connect(InetSocketAddress(targetHost, targetPort), snap?.settings?.tcpHandshakeTimeoutMs ?: 4000)
                soTimeout = 0
            }
            activeSockets.add(outbound)
            client.soTimeout = 0

            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()

            val scope = runtimeScope
            val pumpUp = scope?.launch {
                pipeStreams(client.getInputStream(), outbound.getOutputStream())
            }
            pipeStreams(outbound.getInputStream(), client.getOutputStream())
            pumpUp?.cancel()
        } catch (_: Exception) {
            try {
                client.getOutputStream().write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.getOutputStream().flush()
            } catch (_: Exception) {
            }
        } finally {
            activeSockets.remove(client)
            outbound?.let { activeSockets.remove(it) }
            try { outbound?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipeStreams(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun parseInboundPort(compiledJson: String, fallback: Int): Int {
        return try {
            val root = JSONObject(compiledJson)
            root.optJSONArray("inbounds")?.optJSONObject(0)?.optInt("port", fallback) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseCompiledSnapshot(
        compiledJson: String,
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity?,
        generation: Int
    ): CoreRuntimeSnapshot {
        var primaryProto = node?.protocol?.lowercase() ?: "direct"
        var dialerProxy: String? = null
        try {
            val root = JSONObject(compiledJson)
            val outbound0 = root.optJSONArray("outbounds")?.optJSONObject(0)
            if (outbound0 != null) {
                primaryProto = outbound0.optString("protocol", primaryProto)
                dialerProxy = outbound0.optJSONObject("streamSettings")
                    ?.optJSONObject("sockopt")
                    ?.optString("dialerProxy", "")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
        }
        return CoreRuntimeSnapshot(
            compiledJson = compiledJson,
            node = node,
            settings = settings,
            rules = rules,
            relayNode = relayNode,
            primaryOutboundProtocol = primaryProto,
            activeDialerProxy = dialerProxy,
            generation = generation
        )
    }
}
