package com.example

import android.content.Context
import android.os.Process
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import com.example.core.EncryptedConfigVault
import com.example.core.NetworkProbeEngine
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
import com.example.core.SubscriptionFetchResult
import com.example.core.SubscriptionFetcher
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.SettingsDataStore
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.ProxyProtocol
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.vpn.OriginalDestinationFlow
import com.example.vpn.SplitTunnelEngine
import com.example.vpn.UidFlowDecision
import com.example.xraycore.DnsTransportProtocol
import com.example.xraycore.DnsWireAnswer
import com.example.xraycore.MultiResolverDnsEngine
import com.example.xraycore.ResolverProbeEntry
import com.example.xraycore.VpnSocketProtector
import com.example.xraycore.XrayConfigCompiler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3DnsSplitTunnelOpsecTest {

    @After
    fun tearDown() {
        VpnSocketProtector.setTestHook(null)
        VpnSocketProtector.resetCounter()
    }

    @Test
    fun parsesSerializesCompilesAndRedactsDnsttAndMasterDns() {
        val dnsttUri = "dnstt://dnsttPubKeyHex99@1.1.1.1:53?domain=t.example.org&transport=udp&mtu=1200#DNSTT-Edge"
        val masterDnsUri = "masterdns://masterPub888@8.8.4.4:853?domain=ns1.cdn-tunnel.net&transport=doh&doh=https://dns.google/dns-query&sni=dns.google&mtu=1100#MasterDNS-DoH"

        val parsed = ProxyUriParser.parseBatch("$dnsttUri\n$masterDnsUri")
        assertEquals(2, parsed.size)

        val dnstt = parsed[0]
        assertEquals(ProxyProtocol.DNSTT.name, dnstt.protocol)
        assertEquals(TransportType.DNSTT.name, dnstt.transport)
        assertEquals("1.1.1.1", dnstt.address)
        assertEquals(53, dnstt.port)
        assertEquals("t.example.org", dnstt.hostHeader)
        assertEquals("dnsttPubKeyHex99", dnstt.publicKey)
        assertEquals("udp", dnstt.encryption)
        assertEquals("1200", dnstt.flow)

        val dnsttShare = ProxyUriParser.toShareUri(dnstt.copy(rawUri = ""))
        assertTrue(dnsttShare.startsWith("dnstt://"))
        val dnsttReParsed = ProxyUriParser.parseSingle(dnsttShare)!!
        assertEquals("t.example.org", dnsttReParsed.hostHeader)
        assertEquals("dnsttPubKeyHex99", dnsttReParsed.publicKey)

        val master = parsed[1]
        assertEquals(ProxyProtocol.MASTERDNS.name, master.protocol)
        assertEquals(TransportType.MASTERDNS.name, master.transport)
        assertEquals(SecurityType.TLS.name, master.security)
        assertEquals("doh", master.encryption)
        assertEquals("https://dns.google/dns-query", master.path)
        assertEquals("ns1.cdn-tunnel.net", master.hostHeader)

        val settings = StabilitySettings(
            chainingMode = ChainingMode.WARP_TO_PROXY,
            dnsPoolStrategy = DnsPoolStrategy.STICKY,
            dnsDotEnabled = true,
            dnsDotServer = "9.9.9.9:853",
            strictUidIsolationEnabled = true
        )
        val compiledMaster = XrayConfigCompiler.compile(master, settings, emptyList())
        val root = JSONObject(compiledMaster)
        assertEquals("sticky", root.getJSONObject("dns").getString("poolStrategy"))
        assertTrue(root.getJSONObject("dns").getBoolean("integrityFilter"))
        assertTrue(root.getJSONObject("routing").getBoolean("preFakeDnsEvaluation"))
        assertTrue(root.getJSONObject("routing").getBoolean("strictUidIsolation"))

        val outbound0 = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("masterdns", outbound0.getString("protocol"))
        val obSettings = outbound0.getJSONObject("settings")
        assertEquals("ns1.cdn-tunnel.net", obSettings.getString("domain"))
        assertEquals("masterPub888", obSettings.getString("publicKey"))
        assertEquals("doh", obSettings.getString("transport"))
        assertEquals("warp-out", outbound0.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))

        // Round-trip through RawXrayJsonSanitizer
        val sanitized = RawXrayJsonSanitizer.sanitize(compiledMaster)
        assertTrue(sanitized.extractedNodes.any { it.protocol == ProxyProtocol.MASTERDNS.name })

        // Verify locked profile redaction
        val lockedDnstt = dnstt.copy(isLocked = true, vaultKeyScheme = EncryptedConfigVault.SCHEME_X25519)
        val redacted = XrayConfigCompiler.compileRedactedForInspector(lockedDnstt, settings, emptyList())
        assertFalse(redacted.contains("dnsttPubKeyHex99"))
        assertFalse(redacted.contains("t.example.org"))
        assertTrue(redacted.contains(EncryptedConfigVault.REDACTED_VALUE))
        assertTrue(redacted.contains("LOCKED_NO_REVEAL"))
    }

    @Test
    fun vpnSocketProtectorProtectsProbeAndDnsSockets() = runBlocking {
        var protectedTcp = 0
        var protectedUdp = 0
        VpnSocketProtector.resetCounter()
        VpnSocketProtector.setTestHook(object : VpnSocketProtector.SocketProtectHook {
            override fun onProtectSocket(socket: Socket): Boolean {
                protectedTcp++
                return true
            }

            override fun onProtectDatagram(socket: DatagramSocket): Boolean {
                protectedUdp++
                return true
            }

            override fun onProtectFd(fd: Int): Boolean = true
        })

        // Direct socket creation helpers
        VpnSocketProtector.createProtectedSocket().close()
        VpnSocketProtector.createProtectedDatagramSocket().close()
        assertEquals(1, protectedTcp)
        assertEquals(1, protectedUdp)

        // Probe engine routes through VpnSocketProtector (using unreachable TEST-NET/loopback port with short timeout)
        val probeEngine = NetworkProbeEngine()
        probeEngine.measureSingleTcpRtt("127.0.0.1", 1, timeoutMs = 150)
        assertTrue(protectedTcp >= 2)
        assertTrue(VpnSocketProtector.getProtectedCount() >= 3)
    }

    @Test
    fun multiResolverDnsEngineValidatesIntegrityFiltersPoisoningAndAppliesStrategies() = runBlocking {
        // 1. Wire-packet construction + parsing
        val query = MultiResolverDnsEngine.buildDnsQueryPacket("www.example.com", txId = 0x4242, qType = 1)
        assertTrue(query.size > 12)
        assertEquals(0x42, query[0].toInt() and 0xFF)
        assertEquals(0x42, query[1].toInt() and 0xFF)

        val forgedResponse = buildMockDnsAResponse(txId = 0x4242, rcode = 0, ipBytes = byteArrayOf(127, 0, 0, 1))
        val parsedForged = MultiResolverDnsEngine.parseDnsResponsePacket(forgedResponse)
        assertNotNull(parsedForged)
        assertEquals(listOf("127.0.0.1"), parsedForged!!.ips)

        // 2. Reject bogon/private IP poisoning for public domain
        val bogonVerdict = MultiResolverDnsEngine.validateAnswer(
            domain = "www.example.com",
            expectedTxId = 0x4242,
            answer = parsedForged,
            enforceIntegrity = true
        )
        assertFalse(bogonVerdict.isValid)
        assertTrue(bogonVerdict.reason!!.contains("bogon/private", ignoreCase = true))

        // 3. Reject TXID mismatch
        val txMismatchVerdict = MultiResolverDnsEngine.validateAnswer(
            domain = "www.example.com",
            expectedTxId = 0x9999,
            answer = DnsWireAnswer(txId = 0x4242, rcode = 0, ips = listOf("93.184.216.34")),
            enforceIntegrity = true
        )
        assertFalse(txMismatchVerdict.isValid)
        assertTrue(txMismatchVerdict.reason!!.contains("TXID"))

        // 4. PARALLEL_FASTEST discards forged NXDOMAIN & bogon UDP answers, picking clean DoH/DoT answer
        val poolSettings = StabilitySettings(
            dnsPoolStrategy = DnsPoolStrategy.PARALLEL_FASTEST,
            dnsIntegrityFilterEnabled = true,
            dohEnabled = true,
            dnsDotEnabled = true,
            dnsTcpEnabled = false,
            dnsUdpEnabled = true
        )
        val parallelResult = MultiResolverDnsEngine.queryPool(
            domain = "www.example.com",
            settings = poolSettings
        ) { ep, _, _ ->
            when (ep.transport) {
                DnsTransportProtocol.UDP -> ResolverProbeEntry(
                    resolverId = ep.id,
                    label = ep.label,
                    transport = ep.transport,
                    success = false,
                    rttMs = 5,
                    rcode = 3,
                    rejectedReason = "NXDOMAIN returned (subject to cross-resolver verification)"
                )
                DnsTransportProtocol.DOT -> ResolverProbeEntry(
                    resolverId = ep.id,
                    label = ep.label,
                    transport = ep.transport,
                    success = true,
                    rttMs = 38,
                    ips = listOf("93.184.216.34")
                )
                DnsTransportProtocol.DOH -> ResolverProbeEntry(
                    resolverId = ep.id,
                    label = ep.label,
                    transport = ep.transport,
                    success = true,
                    rttMs = 64,
                    ips = listOf("93.184.216.34")
                )
                else -> ResolverProbeEntry(ep.id, ep.label, ep.transport, false, -1, rejectedReason = "disabled")
            }
        }
        assertTrue(parallelResult.success)
        assertEquals(DnsTransportProtocol.DOT, parallelResult.transport)
        assertEquals(38, parallelResult.rttMs)
        assertTrue(parallelResult.rejectedForPoisoning.any { it.contains("Forged NXDOMAIN", ignoreCase = true) })

        // 5. STICKY re-orders winning dot_primary resolver first on next query
        val seenOrder = mutableListOf<String>()
        val stickyResult = MultiResolverDnsEngine.queryPool(
            domain = "api.example.com",
            settings = poolSettings.copy(dnsPoolStrategy = DnsPoolStrategy.STICKY)
        ) { ep, _, _ ->
            seenOrder.add(ep.id)
            ResolverProbeEntry(
                resolverId = ep.id,
                label = ep.label,
                transport = ep.transport,
                success = true,
                rttMs = 20,
                ips = listOf("1.1.1.1")
            )
        }
        assertTrue(stickyResult.success)
        assertEquals("dot_primary", seenOrder.first())
    }

    @Test
    fun splitTunnelEvaluatesPreFakeDnsOriginalDestinationAndStrictUidPolicy() {
        val rules = listOf(
            RoutingRuleEntity(
                id = "r1",
                name = "Direct Local Bank",
                matchType = RuleMatchType.DOMAIN_SUFFIX.name,
                pattern = "bank.ir",
                action = RuleAction.DIRECT.name,
                isEnabled = true
            ),
            RoutingRuleEntity(
                id = "r2",
                name = "Direct LAN CIDR",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "192.168.50.0/24",
                action = RuleAction.DIRECT.name,
                isEnabled = true
            )
        )

        // Even when FakeDNS rewrote the resolved IP to 198.18.0.42, rule matches originalDomain/originalIp!
        val flowWithFakeDns = OriginalDestinationFlow(
            originalDomain = "portal.bank.ir",
            originalIp = "91.98.100.12",
            resolvedOrFakeIp = "198.18.0.42",
            port = 443,
            protocol = OsConstants.IPPROTO_TCP
        )
        assertTrue(SplitTunnelEngine.isFakeDnsIp(flowWithFakeDns.resolvedOrFakeIp))
        val decision = SplitTunnelEngine.evaluatePreFakeDnsRouting(
            flow = flowWithFakeDns,
            routingMode = RoutingMode.RULE_BASED,
            rules = rules,
            blockQuicUdp = true
        )
        assertEquals(RuleAction.DIRECT, decision.action)
        assertEquals("Direct Local Bank", decision.matchedRuleName)
        assertTrue(decision.evaluatedOriginalDestination)
        assertTrue(decision.hadFakeDnsRewrite)

        // UID Isolation Policy: fail-closed when UID unresolved and strictUidIsolation=true
        assertEquals(
            UidFlowDecision.DENY_UNRESOLVED_UID,
            SplitTunnelEngine.evaluateUidFlowPolicy(
                uid = Process.INVALID_UID,
                selfUid = 10120,
                mode = AppSplitTunnelMode.ALLOWLIST,
                allowedUids = setOf(10200),
                blockedUids = emptySet(),
                strictUidIsolation = true
            )
        )
        assertEquals(
            UidFlowDecision.DENY_EXCLUDED_UID,
            SplitTunnelEngine.evaluateUidFlowPolicy(
                uid = 10300,
                selfUid = 10120,
                mode = AppSplitTunnelMode.ALLOWLIST,
                allowedUids = setOf(10200),
                blockedUids = emptySet(),
                strictUidIsolation = true
            )
        )
        assertEquals(
            UidFlowDecision.BYPASS_SELF,
            SplitTunnelEngine.evaluateUidFlowPolicy(
                uid = 10120,
                selfUid = 10120,
                mode = AppSplitTunnelMode.ALLOWLIST,
                allowedUids = emptySet(),
                blockedUids = emptySet(),
                strictUidIsolation = true
            )
        )
        assertEquals(
            UidFlowDecision.ALLOW,
            SplitTunnelEngine.evaluateUidFlowPolicy(
                uid = 10200,
                selfUid = 10120,
                mode = AppSplitTunnelMode.ALLOWLIST,
                allowedUids = setOf(10200),
                blockedUids = emptySet(),
                strictUidIsolation = true
            )
        )

        // IPv4 header parsing verification
        val ipv4TcpPacket = ByteArray(24).apply {
            this[0] = 0x45.toByte() // IPv4, IHL=20
            this[9] = OsConstants.IPPROTO_TCP.toByte()
            this[12] = 10; this[13] = 88; this[14] = 0; this[15] = 2
            this[16] = 198.toByte(); this[17] = 18; this[18] = 0; this[19] = 42
            this[20] = 0xC0.toByte(); this[21] = 0x01 // src port 49153
            this[22] = 0x01; this[23] = 0xBB.toByte() // dst port 443
        }
        val parsedHeader = SplitTunnelEngine.parseIpv4FlowHeader(ipv4TcpPacket, ipv4TcpPacket.size)
        assertNotNull(parsedHeader)
        assertEquals("10.88.0.2", parsedHeader!!.sourceIp)
        assertEquals("198.18.0.42", parsedHeader.destIp)
        assertEquals(49153, parsedHeader.sourcePort)
        assertEquals(443, parsedHeader.destPort)
    }

    @Test
    fun subscriptionFetcherSendsCustomUserAgentAndSettingsDataStorePersistsOpSec() = runBlocking {
        var capturedUserAgent: String? = null
        val interceptClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                capturedUserAgent = req.header("User-Agent")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("socks://198.51.100.10:1080#UA-Verified".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val fetcher = SubscriptionFetcher(interceptClient)
        val res = fetcher.fetch(
            url = "https://sub.example.com/list",
            subscriptionId = "sub-1",
            groupName = "CustomUA",
            topN = 10,
            userAgent = "HiddifyNext/5.9.0"
        )
        assertTrue(res is SubscriptionFetchResult.Success)
        assertEquals("HiddifyNext/5.9.0", capturedUserAgent)

        // Verify DataStore round-trip for Phase 3 settings
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)
        store.setDnsPoolConfig(
            strategy = DnsPoolStrategy.SEQUENTIAL_FALLBACK,
            integrityFilterEnabled = true,
            dotServer = "9.9.9.9:853",
            dotEnabled = true,
            tcpEnabled = false,
            udpEnabled = true
        )
        store.setSplitTunnelConfig(
            mode = AppSplitTunnelMode.BLOCKLIST,
            packagesCsv = "com.android.chrome, org.telegram.messenger",
            strictUidIsolation = true
        )
        store.setPrivacyOpSecConfig(
            flagSecureEnabled = true,
            excludeFromRecents = true,
            appLockEnabled = true,
            subscriptionUserAgent = "ClashMeta/1.19"
        )

        val loaded = store.settingsFlow.first()
        assertEquals(DnsPoolStrategy.SEQUENTIAL_FALLBACK, loaded.dnsPoolStrategy)
        assertEquals("9.9.9.9:853", loaded.dnsDotServer)
        assertFalse(loaded.dnsTcpEnabled)
        assertEquals(AppSplitTunnelMode.BLOCKLIST, loaded.appSplitTunnelMode)
        assertEquals("com.android.chrome, org.telegram.messenger", loaded.splitTunnelPackages)
        assertTrue(loaded.strictUidIsolationEnabled)
        assertTrue(loaded.flagSecureEnabled)
        assertTrue(loaded.excludeFromRecents)
        assertTrue(loaded.appLockEnabled)
        assertEquals("ClashMeta/1.19", loaded.subscriptionUserAgent)
    }

    private fun buildMockDnsAResponse(txId: Int, rcode: Int, ipBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x81) // QR=1, RD=1
        out.write(0x80 or (rcode and 0x0F)) // RA=1, rcode
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x01) // ANCOUNT=1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        // Question: 3www7example3com0 + QTYPE A + QCLASS IN
        out.write(byteArrayOf(3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte()))
        out.write(byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte()))
        out.write(byteArrayOf(3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0))
        out.write(0x00); out.write(0x01) // QTYPE=A
        out.write(0x00); out.write(0x01) // QCLASS=IN
        // Answer: pointer 0xC00C, TYPE=A, CLASS=IN, TTL=60, RDLENGTH=4, RDATA=ipBytes
        out.write(0xC0); out.write(0x0C)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C)
        out.write(0x00); out.write(0x04)
        out.write(ipBytes)
        return out.toByteArray()
    }
}
