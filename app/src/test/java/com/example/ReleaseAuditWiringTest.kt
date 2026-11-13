package com.example

import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ProxyProtocol
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.StabilitySettings
import com.example.vpn.UidFlowDecision
import com.example.xraycore.AdaptiveRoutingEngine
import com.example.xraycore.LuxyCoreRuntime
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.MultiResolverDnsEngine
import com.example.xraycore.XrayConfigCompiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseAuditWiringTest {

    @After
    fun tearDown() {
        LuxyVpnCore.stopCoreRuntime()
    }

    @Test
    fun coreRuntimeStartsHotReloadsSamePortAndStopsCleanly() {
        val baseNode = ServerProfileEntity(
            id = "node-1",
            name = "VLESS Edge",
            protocol = ProxyProtocol.VLESS.name,
            address = "198.51.100.10",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555"
        )
        val settings0 = AdaptiveRoutingEngine.applyStageToSettings(StabilitySettings(), 0)
        val json0 = XrayConfigCompiler.compile(baseNode, settings0, emptyList())

        val startStatus = LuxyVpnCore.startCoreRuntime(
            compiledJson = json0,
            node = baseNode,
            settings = settings0,
            rules = emptyList(),
            preferredPort = 0
        )
        assertTrue(startStatus.isRunning)
        assertTrue(startStatus.socksPort > 0)
        assertEquals("vless", startStatus.primaryOutboundProtocol)

        // Hot-reload to Stage 4 DNSTT fallback WITHOUT dropping the listening SOCKS5 port
        val dnsttNode = ServerProfileEntity(
            id = "dnstt-1",
            name = "DNSTT Fallback",
            protocol = ProxyProtocol.DNSTT.name,
            address = "1.1.1.1",
            port = 53,
            uuidOrPassword = "key",
            publicKey = "pub99",
            hostHeader = "t.example.org"
        )
        val settings4 = AdaptiveRoutingEngine.applyStageToSettings(StabilitySettings(), 4)
        val json4 = XrayConfigCompiler.compile(dnsttNode, settings4, emptyList())

        val reloadedStatus = LuxyVpnCore.hotReloadCoreRuntime(
            compiledJson = json4,
            node = dnsttNode,
            settings = settings4,
            rules = emptyList()
        )
        assertTrue(reloadedStatus.isRunning)
        assertEquals(startStatus.socksPort, reloadedStatus.socksPort)
        assertEquals(startStatus.generation + 1, reloadedStatus.generation)
        assertEquals("dnstt", reloadedStatus.primaryOutboundProtocol)

        // SOCKS5 client handshake + RuleAction.BLOCK verification via live loopback port
        val blockRules = listOf(
            RoutingRuleEntity(
                id = "blk-1",
                name = "Block Ads",
                matchType = RuleMatchType.DOMAIN_SUFFIX.name,
                pattern = "blocked.example",
                action = RuleAction.BLOCK.name
            )
        )
        LuxyVpnCore.hotReloadCoreRuntime(
            compiledJson = XrayConfigCompiler.compile(dnsttNode, settings4, blockRules),
            node = dnsttNode,
            settings = settings4,
            rules = blockRules
        )

        Socket("127.0.0.1", reloadedStatus.socksPort).use { client ->
            client.soTimeout = 3000
            val out = client.getOutputStream()
            val inp = DataInputStream(client.getInputStream())
            // Greeting: VER=5, NMETHODS=1, NOAUTH=0
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greetReply = ByteArray(2).also { inp.readFully(it) }
            assertEquals(0x05, greetReply[0].toInt() and 0xFF)
            assertEquals(0x00, greetReply[1].toInt() and 0xFF)

            // CONNECT to blocked.example:443 -> expect REP=0x02 (not allowed by ruleset)
            val host = "ads.blocked.example".toByteArray(Charsets.UTF_8)
            val req = ByteArray(4 + 1 + host.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = host.size.toByte()
            System.arraycopy(host, 0, req, 5, host.size)
            req[5 + host.size] = 0x01
            req[6 + host.size] = 0xBB.toByte()
            out.write(req)
            out.flush()

            val connectReply = ByteArray(10).also { inp.readFully(it) }
            assertEquals(0x05, connectReply[0].toInt() and 0xFF)
            assertEquals(0x02, connectReply[1].toInt() and 0xFF)
        }

        LuxyVpnCore.stopCoreRuntime()
        val stopped = LuxyVpnCore.getCoreRuntimeStatus()
        assertFalse(stopped.isRunning)
        assertEquals(-1, stopped.socksPort)
    }

    @Test
    fun tunPacketBridgeForwardsUdpDnsAndInjectsChecksummedIpv4Reply() {
        val dnsQuery = MultiResolverDnsEngine.buildDnsQueryPacket("example.com", txId = 0x1357, qType = 1)
        val udpLen = 8 + dnsQuery.size
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)
        packet[0] = 0x45.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 64
        packet[9] = 17 // UDP
        // src = 10.88.0.2, dst = 1.1.1.1
        packet[12] = 10; packet[13] = 88; packet[14] = 0; packet[15] = 2
        packet[16] = 1; packet[17] = 1; packet[18] = 1; packet[19] = 1
        // srcPort = 54321, dstPort = 53
        packet[20] = 0xD4.toByte(); packet[21] = 0x31
        packet[22] = 0x00; packet[23] = 53
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, packet, 28, dnsQuery.size)

        val tunOut = ByteArrayOutputStream()
        val fakeReply = byteArrayOf(0x13, 0x57, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)

        val verdict = LuxyCoreRuntime.forwardTunPacket(
            packet = packet,
            length = packet.size,
            tunOutput = tunOut,
            ownerResolver = null,
            allowedUids = emptySet(),
            blockedUids = emptySet(),
            selfUid = 1000,
            settingsOverride = StabilitySettings(appSplitTunnelMode = AppSplitTunnelMode.DISABLED),
            rulesOverride = emptyList(),
            rawDnsResolver = { q, _ ->
                assertEquals("example.com", MultiResolverDnsEngine.extractQueryDomain(q))
                fakeReply
            }
        )

        assertEquals(UidFlowDecision.BYPASS_SELF, verdict.uidDecision)
        assertTrue(verdict.dnsForwarded)

        val replyBytes = tunOut.toByteArray()
        assertEquals(20 + 8 + fakeReply.size, replyBytes.size)
        // Reversed IP endpoints: src = 1.1.1.1, dst = 10.88.0.2
        assertEquals(1, replyBytes[12].toInt() and 0xFF)
        assertEquals(10, replyBytes[16].toInt() and 0xFF)
        assertEquals(88, replyBytes[17].toInt() and 0xFF)
        // Ports swapped: srcPort = 53, dstPort = 54321
        assertEquals(53, ((replyBytes[20].toInt() and 0xFF) shl 8) or (replyBytes[21].toInt() and 0xFF))
        assertEquals(54321, ((replyBytes[22].toInt() and 0xFF) shl 8) or (replyBytes[23].toInt() and 0xFF))
        // Verify IPv4 header checksum sums to 0xFFFF
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((replyBytes[i].toInt() and 0xFF) shl 8) or (replyBytes[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }
}
