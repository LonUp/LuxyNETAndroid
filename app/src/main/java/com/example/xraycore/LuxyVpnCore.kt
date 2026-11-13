package com.example.xraycore

import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

data class FragmentTlsProbeResult(
    val success: Boolean,
    val rttMs: Int,
    val tlsProtocol: String = "",
    val cipherSuite: String = "",
    val fragmentsSent: Int = 0,
    val error: String? = null
)

data class RealityReadinessResult(
    val sni: String,
    val port: Int,
    val isReady: Boolean,
    val rttMs: Int,
    val tlsVersion: String,
    val cipherSuite: String,
    val error: String? = null
)

data class DohProbeResult(
    val success: Boolean,
    val rttMs: Int,
    val resolvedIps: List<String> = emptyList(),
    val providerUrl: String = "",
    val error: String? = null
)

data class EchProbeResult(
    val domain: String,
    val echSupported: Boolean,
    val rttMs: Int,
    val httpsRecords: List<String> = emptyList(),
    val alpnHints: String = "",
    val error: String? = null
)

data class EchHandshakeVerificationResult(
    val allowed: Boolean,
    val outerSni: String,
    val echConfigList: String = "",
    val rttMs: Int = -1,
    val enforcedFailClosed: Boolean = false,
    val error: String? = null
)

/**
 * Dedicated, proprietary in-process Xray-compatible Core & Anti-DPI Transport Engine for LuxyVPN.
 * Incorporates advanced anti-censorship capabilities: TCP ClientHello fragmentation with maxSplit,
 * custom TLS cipherSuites enforcement, finalMask post-encryption framing, ECH DNS probing,
 * REALITY TLSv1.3 target readiness checks, and DoH anti-poisoning verification.
 */
object LuxyVpnCore {

    const val CORE_VERSION = "LuxyVpnCore 3.0.0-adaptive"

    val CIPHER_SUITE_PRESETS = listOf(
        "Auto" to "",
        "TLS1.3 GCM" to "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384",
        "ChaCha20" to "TLS_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "ECDHE ECDSA" to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_AES_128_GCM_SHA256"
    )

    val FINAL_MASK_PRESETS = listOf(
        "none",
        "header-custom",
        "header-utp",
        "header-wechat",
        "header-dtls",
        "header-wireguard",
        "mkcp-aes128gcm",
        "sudoku"
    )

    val FINAL_MASK_FAMILIES = listOf("tcp", "tcp4", "tcp6", "udp4", "udp6", "oob")

    val DATAGRAM_SHAPING_PRESETS = listOf("off", "raw", "quic", "dns", "sip", "random")

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(VpnSocketProtector.protectedOkHttpSocketFactory())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun describeDialerChain(
        settings: StabilitySettings,
        node: ServerProfileEntity? = null
    ): String {
        val parts = mutableListOf<String>()
        if (settings.chainingMode != com.example.data.model.ChainingMode.NONE) {
            parts.add(settings.chainingMode.label)
        }
        val proto = node?.protocol?.uppercase().orEmpty()
        if (proto == "WIREGUARD" || settings.chainingMode == com.example.data.model.ChainingMode.WARP_TO_PROXY ||
            settings.chainingMode == com.example.data.model.ChainingMode.PROXY_TO_WARP
        ) {
            parts.add("AWG(Jc=${settings.awgJc},${settings.warpNoisePreset})")
        } else if (proto == "MASQUE") {
            parts.add("MASQUE(${node?.encryption?.ifBlank { settings.warpNoisePreset } ?: settings.warpNoisePreset})")
        } else if (proto == "DNSTT" || proto == "MASTERDNS") {
            val mode = node?.encryption?.ifBlank { "udp" } ?: "udp"
            parts.add("$proto(${mode.uppercase()})")
        }
        if (settings.tlsFragmentEnabled) {
            val hostTag = if (settings.fragmentHost.isNotBlank()) "@${settings.fragmentHost}" else ""
            val realityTag = if (settings.realityFragmentEnabled) "+REALITY" else ""
            parts.add("Fragment(${settings.fragmentLength}B/${settings.fragmentPackets}$hostTag$realityTag)")
        }
        if (settings.noiseEnabled) {
            parts.add("Noise(${settings.noiseType}:${settings.noisePacketSize}B)")
        }
        val activeMask = node?.finalMask?.takeIf { it.isNotBlank() && it != "none" }
            ?: settings.finalMaskType.takeIf { it.isNotBlank() && it != "none" }
        if (activeMask != null) {
            val maskLabel = if (settings.finalMaskFamily == "tcp" || settings.finalMaskFamily.isBlank()) {
                "FinalMask($activeMask)"
            } else {
                "FinalMask[${settings.finalMaskFamily}]($activeMask)"
            }
            parts.add(maskLabel)
        }
        if (settings.mixedCaseSni) {
            parts.add("MixedSNI")
        }
        if (settings.tlsPaddingEnabled) {
            parts.add("TLSPad(7685)")
        }
        val hasEch = settings.echEnabled || node?.echConfigList?.isNotBlank() == true
        if (hasEch) {
            parts.add("ECH(${settings.echForceQuery})")
        }
        val activeCiphers = node?.cipherSuites?.takeIf { it.isNotBlank() } ?: settings.cipherSuites
        if (activeCiphers.isNotBlank()) {
            parts.add("Ciphers")
        }
        if (settings.happyEyeballsEnabled) {
            parts.add("HappyEyeballs")
        }
        if (settings.fakeDnsEnabled) {
            parts.add("FakeDNS")
        }
        if (settings.blockQuicUdp) {
            parts.add("QUIC-Guard")
        }
        return if (parts.isEmpty()) "Direct Outbound" else parts.joinToString(" → ")
    }

    /**
     * Produces a valid case-mixed SNI hostname (RFC 4366 hostnames are DNS case-insensitive)
     * to defeat naive exact-lowercase DPI SNI signatures, without mutating IP literals.
     */
    fun randomizeCaseSni(sni: String): String {
        val trimmed = sni.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.matches(Regex("^[0-9a-fA-F:.\\[\\]]+$")) && !trimmed.any { it in 'g'..'z' || it in 'G'..'Z' }) {
            return trimmed
        }
        var letterIdx = 0
        return buildString(trimmed.length) {
            for (ch in trimmed) {
                if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    append(if (letterIdx % 2 == 0) ch.uppercaseChar() else ch.lowercaseChar())
                    letterIdx++
                } else {
                    append(ch)
                }
            }
        }
    }

    fun parseRangePair(rangeStr: String, defaultMin: Int, defaultMax: Int): IntRange {
        val parts = rangeStr.split("-").mapNotNull { it.trim().toIntOrNull() }
        return when {
            parts.size >= 2 -> {
                val a = parts[0].coerceAtLeast(1)
                val b = parts[1].coerceAtLeast(a)
                a..b
            }
            parts.size == 1 -> {
                val v = parts[0].coerceAtLeast(1)
                v..v
            }
            else -> defaultMin..defaultMax
        }
    }

    /**
     * Performs a real TLS handshake over a custom TCP Fragmenting Socket that splits the
     * outgoing TLS ClientHello record into randomized segment sizes (capped by maxSplit)
     * with inter-segment micro-delays and optional custom TLS cipherSuites enforcement.
     */
    suspend fun probeFragmentedTls(
        host: String,
        port: Int,
        sni: String,
        lengthRangeStr: String = "10-20",
        intervalRangeStr: String = "10-20",
        enableFragment: Boolean = true,
        cipherSuites: String = "",
        maxSplitRangeStr: String = "32-64",
        timeoutMs: Int = 4000,
        mixedCaseSni: Boolean = false
    ): FragmentTlsProbeResult = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext FragmentTlsProbeResult(false, -1, error = "Invalid host or port")
        }
        val baseSni = sni.ifBlank { host }
        val effectiveSni = if (mixedCaseSni) randomizeCaseSni(baseSni) else baseSni
        val lenRange = parseRangePair(lengthRangeStr, 10, 20)
        val delayRange = parseRangePair(intervalRangeStr, 10, 20)
        val maxSplitRange = parseRangePair(maxSplitRangeStr, 32, 64)
        val maxSplits = Random.nextInt(maxSplitRange.first, maxSplitRange.last + 1)
        var rawSocket: FragmentingSocket? = null
        var sslSocket: SSLSocket? = null

        try {
            val startNs = System.nanoTime()
            rawSocket = FragmentingSocket(
                enableFragment = enableFragment,
                fragmentLengthRange = lenRange,
                fragmentDelayRange = delayRange,
                maxSplits = maxSplits
            ).apply {
                VpnSocketProtector.protect(this)
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), timeoutMs)
            }

            sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, effectiveSni, port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs

            if (cipherSuites.isNotBlank()) {
                val requested = cipherSuites.split(":", ",").map { it.trim() }.filter { it.isNotEmpty() }
                val supportedSet = sslSocket.supportedCipherSuites.toSet()
                val matching = requested.filter { it in supportedSet }
                if (matching.isNotEmpty()) {
                    sslSocket.enabledCipherSuites = matching.toTypedArray()
                }
            }

            if (!effectiveSni.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                val params: SSLParameters = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(effectiveSni))
                sslSocket.sslParameters = params
            }

            sslSocket.startHandshake()
            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            val session = sslSocket.session
            FragmentTlsProbeResult(
                success = true,
                rttMs = rttMs,
                tlsProtocol = session.protocol.orEmpty(),
                cipherSuite = session.cipherSuite.orEmpty(),
                fragmentsSent = rawSocket.segmentsEmitted
            )
        } catch (e: Exception) {
            FragmentTlsProbeResult(
                success = false,
                rttMs = -1,
                fragmentsSent = rawSocket?.segmentsEmitted ?: 0,
                error = e.localizedMessage ?: e.javaClass.simpleName
            )
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
            try {
                rawSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Checks whether a candidate SNI / destination is suitable for VLESS-REALITY camouflage
     * (TLS 1.3 support + clean handshake).
     */
    suspend fun probeRealitySniReadiness(
        sni: String,
        port: Int = 443,
        timeoutMs: Int = 3500
    ): RealityReadinessResult = withContext(Dispatchers.IO) {
        val cleanSni = sni.trim()
        if (cleanSni.isBlank()) {
            return@withContext RealityReadinessResult(cleanSni, port, false, -1, "", "", "Empty SNI")
        }
        val res = probeFragmentedTls(
            host = cleanSni,
            port = port,
            sni = cleanSni,
            enableFragment = false,
            timeoutMs = timeoutMs
        )
        val isTls13 = res.tlsProtocol.contains("1.3", ignoreCase = true)
        RealityReadinessResult(
            sni = cleanSni,
            port = port,
            isReady = res.success && isTls13,
            rttMs = res.rttMs,
            tlsVersion = res.tlsProtocol,
            cipherSuite = res.cipherSuite,
            error = res.error ?: if (!isTls13 && res.success) "Server negotiated ${res.tlsProtocol} (REALITY requires TLSv1.3)" else null
        )
    }

    /**
     * Verifies encrypted DoH resolution against DNS poisoning via application/dns-json.
     */
    suspend fun verifyDohAntiPoisoning(
        dohUrl: String,
        testDomain: String = "www.cloudflare.com"
    ): DohProbeResult = withContext(Dispatchers.IO) {
        val cleanEndpoint = dohUrl.trim().ifBlank { "https://cloudflare-dns.com/dns-query" }
        val queryUrl = if (cleanEndpoint.contains("?")) {
            "$cleanEndpoint&name=$testDomain&type=A"
        } else {
            "$cleanEndpoint?name=$testDomain&type=A"
        }
        try {
            val startNs = System.nanoTime()
            val req = Request.Builder()
                .url(queryUrl)
                .header("Accept", "application/dns-json")
                .get()
                .build()
            dohHttpClient.newCall(req).execute().use { response ->
                val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
                if (!response.isSuccessful) {
                    return@withContext DohProbeResult(
                        success = false,
                        rttMs = rttMs,
                        providerUrl = cleanEndpoint,
                        error = "HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer")
                val ips = mutableListOf<String>()
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val item = answers.optJSONObject(i) ?: continue
                        if (item.optInt("type") == 1) {
                            item.optString("data").takeIf { it.isNotBlank() }?.let { ips.add(it) }
                        }
                    }
                }
                DohProbeResult(
                    success = ips.isNotEmpty() || json.optInt("Status", -1) == 0,
                    rttMs = rttMs,
                    resolvedIps = ips,
                    providerUrl = cleanEndpoint
                )
            }
        } catch (e: Exception) {
            DohProbeResult(
                success = false,
                rttMs = -1,
                providerUrl = cleanEndpoint,
                error = e.localizedMessage ?: "DoH verification failed"
            )
        }
    }

    /**
     * Queries DNS HTTPS (RR type 65) over encrypted DoH to verify ECH (Encrypted Client Hello)
     * support and ALPN hints for anti-SNI censorship.
     */
    suspend fun probeEchDnsConfig(
        domain: String,
        dohUrl: String = "https://cloudflare-dns.com/dns-query"
    ): EchProbeResult = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().ifBlank { "cloudflare-ech.com" }
        val cleanEndpoint = dohUrl.trim().ifBlank { "https://cloudflare-dns.com/dns-query" }
        val queryUrl = if (cleanEndpoint.contains("?")) {
            "$cleanEndpoint&name=$cleanDomain&type=HTTPS"
        } else {
            "$cleanEndpoint?name=$cleanDomain&type=HTTPS"
        }
        try {
            val startNs = System.nanoTime()
            val req = Request.Builder()
                .url(queryUrl)
                .header("Accept", "application/dns-json")
                .get()
                .build()
            dohHttpClient.newCall(req).execute().use { response ->
                val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
                if (!response.isSuccessful) {
                    return@withContext EchProbeResult(
                        domain = cleanDomain,
                        echSupported = false,
                        rttMs = rttMs,
                        error = "HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer")
                val records = mutableListOf<String>()
                var hasEch = false
                var alpnHint = ""
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val item = answers.optJSONObject(i) ?: continue
                        if (item.optInt("type") == 65) {
                            val data = item.optString("data")
                            if (data.isNotBlank()) {
                                records.add(data)
                                if (data.contains("ech=", ignoreCase = true) ||
                                    data.contains("65", ignoreCase = true)
                                ) {
                                    hasEch = true
                                }
                                if (data.contains("alpn=", ignoreCase = true) && alpnHint.isBlank()) {
                                    alpnHint = data.substringAfter("alpn=").substringBefore(" ").trim('"')
                                }
                            }
                        }
                    }
                }
                EchProbeResult(
                    domain = cleanDomain,
                    echSupported = hasEch || records.isNotEmpty(),
                    rttMs = rttMs,
                    httpsRecords = records,
                    alpnHints = alpnHint,
                    error = if (records.isEmpty()) "No DNS HTTPS/SVCB (type 65) records returned for $cleanDomain" else null
                )
            }
        } catch (e: Exception) {
            EchProbeResult(
                domain = cleanDomain,
                echSupported = false,
                rttMs = -1,
                error = e.localizedMessage ?: "ECH DNS probe failed"
            )
        }
    }

    fun isFailClosedEchActive(
        settings: StabilitySettings,
        node: ServerProfileEntity? = null,
        adaptiveStage: Int = 0
    ): Boolean {
        val effectiveForce = node?.echForceQuery?.takeIf { it.isNotBlank() && it != "none" }
            ?: settings.echForceQuery
        return settings.failClosedEch ||
            effectiveForce.equals("full", ignoreCase = true) ||
            adaptiveStage == 2
    }

    /**
     * Strict Fail-Closed ECH verification:
     * Fetches & validates ECHConfigList from inline profile config or DNS HTTPS RR (type 65)
     * via VpnSocketProtector-protected DoH. When failClosed=true and valid ECH keys cannot be
     * obtained or validated, aborts the handshake immediately without downgrading to plaintext SNI.
     */
    suspend fun verifyStrictEchReadiness(
        outerSni: String,
        inlineEchConfigList: String = "",
        dohUrl: String = "https://cloudflare-dns.com/dns-query",
        failClosed: Boolean = true,
        customProbe: (suspend (String, String) -> EchProbeResult)? = null
    ): EchHandshakeVerificationResult {
        val cleanSni = outerSni.trim().ifBlank { "cloudflare-ech.com" }
        val inlineClean = MultiResolverDnsEngine.extractEchBase64Token(inlineEchConfigList)
        if (inlineClean.isNotEmpty()) {
            if (MultiResolverDnsEngine.validateEchConfigList(inlineClean)) {
                return EchHandshakeVerificationResult(
                    allowed = true,
                    outerSni = cleanSni,
                    echConfigList = inlineClean,
                    rttMs = 1,
                    enforcedFailClosed = failClosed
                )
            } else if (failClosed) {
                return EchHandshakeVerificationResult(
                    allowed = false,
                    outerSni = cleanSni,
                    enforcedFailClosed = true,
                    error = "Fail-Closed ECH aborted: malformed inline ECHConfigList (downgrade blocked)"
                )
            }
        }

        val probe = customProbe?.invoke(cleanSni, dohUrl) ?: probeEchDnsConfig(cleanSni, dohUrl)
        val extracted = MultiResolverDnsEngine.extractValidEchFromHttpsRecords(probe.httpsRecords)

        if (extracted != null && probe.echSupported) {
            return EchHandshakeVerificationResult(
                allowed = true,
                outerSni = cleanSni,
                echConfigList = extracted,
                rttMs = probe.rttMs,
                enforcedFailClosed = failClosed
            )
        }

        if (failClosed) {
            val detail = when {
                !probe.error.isNullOrBlank() -> probe.error
                probe.httpsRecords.isNotEmpty() -> "HTTPS RR type-65 lacked valid ECHConfigList payload"
                else -> "No ECH keys returned for '$cleanSni'"
            }
            return EchHandshakeVerificationResult(
                allowed = false,
                outerSni = cleanSni,
                rttMs = probe.rttMs,
                enforcedFailClosed = true,
                error = "Fail-Closed ECH aborted: $detail (plaintext SNI downgrade blocked)"
            )
        }

        return EchHandshakeVerificationResult(
            allowed = true,
            outerSni = cleanSni,
            rttMs = probe.rttMs,
            enforcedFailClosed = false
        )
    }

    suspend fun benchmarkMultiResolverPool(
        domain: String = "www.cloudflare.com",
        settings: StabilitySettings
    ): DnsPoolQueryResult = MultiResolverDnsEngine.queryPool(
        domain = domain,
        settings = settings
    )

    fun validateDnsAnswer(
        domain: String,
        expectedTxId: Int,
        answer: DnsWireAnswer?,
        enforceIntegrity: Boolean = true
    ): DnsIntegrityVerdict = MultiResolverDnsEngine.validateAnswer(domain, expectedTxId, answer, enforceIntegrity)

    fun isBogonOrPrivateIp(ip: String): Boolean = MultiResolverDnsEngine.isBogonOrPrivateIp(ip)

    fun compileConfigSummary(node: ServerProfileEntity?, settings: StabilitySettings): String {
        return XrayConfigCompiler.compile(node, settings, emptyList())
    }

    fun startCoreRuntime(
        compiledJson: String,
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<com.example.data.local.RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null,
        preferredPort: Int = 10808
    ): CoreRuntimeStatus = LuxyCoreRuntime.start(
        compiledJson = compiledJson,
        node = node,
        settings = settings,
        rules = rules,
        relayNode = relayNode,
        preferredPort = preferredPort
    )

    fun hotReloadCoreRuntime(
        compiledJson: String,
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<com.example.data.local.RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): CoreRuntimeStatus = LuxyCoreRuntime.hotReload(
        compiledJson = compiledJson,
        node = node,
        settings = settings,
        rules = rules,
        relayNode = relayNode
    )

    fun stopCoreRuntime() {
        LuxyCoreRuntime.stop()
    }

    fun getCoreRuntimeStatus(): CoreRuntimeStatus = LuxyCoreRuntime.status()

    /**
     * Custom Socket subclass that splits the first TLS ClientHello write into small,
     * randomized TCP segments with micro-delays to bypass SNI-inspection DPI middleboxes.
     */
    private class FragmentingSocket(
        private val enableFragment: Boolean,
        private val fragmentLengthRange: IntRange,
        private val fragmentDelayRange: IntRange,
        private val maxSplits: Int = 64
    ) : Socket() {

        @Volatile
        var segmentsEmitted: Int = 0
            private set

        private var wrappedOut: OutputStream? = null

        override fun getOutputStream(): OutputStream {
            val base = super.getOutputStream()
            if (!enableFragment) return base
            return wrappedOut ?: object : FilterOutputStream(base) {
                private var clientHelloFragmented = false

                override fun write(b: ByteArray, off: Int, len: Int) {
                    // TLS Handshake record starts with 0x16 (ContentType=Handshake), 0x03 (TLS major)
                    val isTlsClientHello = !clientHelloFragmented &&
                        len > 6 &&
                        b[off] == 0x16.toByte() &&
                        b[off + 1] == 0x03.toByte()

                    if (isTlsClientHello) {
                        clientHelloFragmented = true
                        var cursor = off
                        val end = off + len
                        while (cursor < end) {
                            val remaining = end - cursor
                            val segLen = if (segmentsEmitted >= maxSplits - 1) {
                                remaining
                            } else {
                                Random.nextInt(
                                    fragmentLengthRange.first,
                                    fragmentLengthRange.last + 1
                                ).coerceAtMost(remaining)
                            }
                            out.write(b, cursor, segLen)
                            out.flush()
                            segmentsEmitted++
                            cursor += segLen
                            if (cursor < end) {
                                val sleepMs = Random.nextInt(
                                    fragmentDelayRange.first,
                                    fragmentDelayRange.last + 1
                                ).toLong()
                                if (sleepMs > 0L) {
                                    Thread.sleep(sleepMs)
                                }
                            }
                        }
                    } else {
                        out.write(b, off, len)
                        out.flush()
                    }
                }

                override fun write(b: Int) {
                    out.write(b)
                }
            }.also { wrappedOut = it }
        }
    }
}
