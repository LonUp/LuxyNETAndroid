package com.example

import com.example.core.EncryptedConfigVault
import com.example.core.ProxyUriParser
import com.example.core.RawXrayJsonSanitizer
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ChainingMode
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2WarpChainingDpiTest {

    @Test
    fun parsesAndRoundTripsWireGuardAwgWarpAndMasqueUris() {
        val awgUri = "awg://privKeyAwg123=@162.159.192.5:2408?publickey=pubKeyCf456=&address=172.16.0.2/32&reserved=12,34,56&mtu=1380&jc=6&jmin=50&jmax=100&s1=15&s2=25&h1=101&h2=202&h3=303&h4=404&shaping=sip#AWG-Edge"
        val warpUri = "warp://warpPriv789=@188.114.96.3:1701?publickey=warpPub000=&reserved=1,2,3#WARP-Lite"
        val masqueUri = "masque://masqueTokenXYZ@162.159.193.10:443?sni=consumer-masque.cloudflareclient.com&publickey=masqPub=&address=172.16.0.5/32&shaping=quic&fm=header-utp#MASQUE-Edge"

        val parsed = ProxyUriParser.parseBatch("$awgUri\n$warpUri\n$masqueUri")
        assertEquals(3, parsed.size)

        val awg = parsed[0]
        assertEquals(ProxyProtocol.WIREGUARD.name, awg.protocol)
        assertEquals(TransportType.WG.name, awg.transport)
        assertEquals("162.159.192.5", awg.address)
        assertEquals(2408, awg.port)
        assertEquals("privKeyAwg123=", awg.uuidOrPassword)
        assertEquals("pubKeyCf456=", awg.publicKey)
        assertEquals("12,34,56", awg.shortId)
        assertEquals("1380", awg.flow)
        assertEquals("sip", awg.encryption)
        val awgParams = ProxyUriParser.parseAwgQuery(awg.serviceName)
        assertNotNull(awgParams)
        assertEquals(6, awgParams!!.jc)
        assertEquals(50, awgParams.jmin)
        assertEquals(100, awgParams.jmax)
        assertEquals(15, awgParams.s1)
        assertEquals(25, awgParams.s2)
        assertEquals(101L, awgParams.h1)
        assertEquals(404L, awgParams.h4)

        val awgShare = ProxyUriParser.toShareUri(awg.copy(rawUri = ""))
        assertTrue(awgShare.startsWith("awg://"))
        val reParsedAwg = ProxyUriParser.parseSingle(awgShare)!!
        assertEquals(awg.address, reParsedAwg.address)
        assertEquals(6, ProxyUriParser.parseAwgQuery(reParsedAwg.serviceName)?.jc)

        val warp = parsed[1]
        assertEquals(ProxyProtocol.WIREGUARD.name, warp.protocol)
        assertEquals(1701, warp.port)
        assertEquals("1,2,3", warp.shortId)

        val masque = parsed[2]
        assertEquals(ProxyProtocol.MASQUE.name, masque.protocol)
        assertEquals(TransportType.MASQUE.name, masque.transport)
        assertEquals(SecurityType.TLS.name, masque.security)
        assertEquals("consumer-masque.cloudflareclient.com", masque.sni)
        assertEquals("header-utp", masque.finalMask)
        assertEquals("quic", masque.encryption)

        val masqueShare = ProxyUriParser.toShareUri(masque.copy(rawUri = ""))
        assertTrue(masqueShare.startsWith("masque://"))
        val reParsedMasque = ProxyUriParser.parseSingle(masqueShare)!!
        assertEquals("162.159.193.10", reParsedMasque.address)
        assertEquals("consumer-masque.cloudflareclient.com", reParsedMasque.sni)
    }

    @Test
    fun compilesAmneziaWgAndMasqueOutboundsAndSanitizesJson() {
        val awgNode = ProxyUriParser.parseSingle(
            "awg://wgSecretKey=@162.159.192.20:4500?publickey=wgPubKey=&reserved=9,8,7&jc=8&jmin=60&jmax=120&s1=20&s2=30&h1=11&h2=22&h3=33&h4=44&shaping=dns#AWG-4500"
        )!!
        val settings = StabilitySettings(
            mixedCaseSni = true,
            tlsPaddingEnabled = true,
            finalMaskFamily = "udp4",
            tcpHandshakeTimeoutMs = 5500,
            tlsHandshakeTimeoutMs = 6500
        )
        val compiledAwg = XrayConfigCompiler.compile(awgNode, settings, emptyList())
        val rootAwg = JSONObject(compiledAwg)
        val wgOutbound = rootAwg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", wgOutbound.getString("protocol"))
        val wgSettings = wgOutbound.getJSONObject("settings")
        assertEquals("wgSecretKey=", wgSettings.getString("secretKey"))
        assertEquals(8, wgSettings.getInt("jc"))
        assertEquals(60, wgSettings.getInt("jmin"))
        assertEquals(120, wgSettings.getInt("jmax"))
        assertEquals(20, wgSettings.getInt("s1"))
        assertEquals(30, wgSettings.getInt("s2"))
        assertEquals(11L, wgSettings.getLong("h1"))
        assertEquals("dns", wgSettings.getString("shaping"))
        assertEquals("162.159.192.20:4500", wgSettings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))

        val masqueNode = ProxyUriParser.parseSingle(
            "masque://masqPriv=@162.159.193.5:443?sni=masque.cloudflareclient.com&publickey=masqPub=&shaping=random&fm=header-dtls#Masque"
        )!!
        val compiledMasque = XrayConfigCompiler.compile(masqueNode, settings, emptyList())
        val rootMasque = JSONObject(compiledMasque)
        val masqueOutbound = rootMasque.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("masque", masqueOutbound.getString("protocol"))
        assertEquals("random", masqueOutbound.getJSONObject("settings").getString("shaping"))
        val masqueStream = masqueOutbound.getJSONObject("streamSettings")
        assertEquals("masque", masqueStream.getString("network"))
        val tls = masqueStream.getJSONObject("tlsSettings")
        assertTrue(tls.getBoolean("padding"))
        assertEquals("rfc7685", tls.getString("tlsPadding"))
        val mixedSni = tls.getString("serverName")
        assertEquals("masque.cloudflareclient.com", mixedSni.lowercase())
        assertFalse(mixedSni == "masque.cloudflareclient.com")
        assertTrue(masqueStream.getJSONObject("finalmask").has("udp4"))
        assertEquals(5500, masqueStream.getJSONObject("sockopt").getInt("tcpHandshakeTimeout"))
        assertEquals(6500, masqueStream.getJSONObject("sockopt").getInt("tlsHandshakeTimeout"))

        // Verify RawXrayJsonSanitizer extracts compiled wireguard & masque outbounds back cleanly
        val extractedAwg = RawXrayJsonSanitizer.sanitize(compiledAwg).extractedNodes
        assertEquals(1, extractedAwg.size)
        assertEquals(ProxyProtocol.WIREGUARD.name, extractedAwg.first().protocol)
        val extractedMasque = RawXrayJsonSanitizer.sanitize(compiledMasque).extractedNodes
        assertEquals(1, extractedMasque.size)
        assertEquals(ProxyProtocol.MASQUE.name, extractedMasque.first().protocol)
    }

    @Test
    fun bidirectionalMultiHopChainingAndRealityFragmentCompileCorrectly() {
        val primaryNode = ServerProfileEntity(
            id = "primary-vless",
            name = "Primary-VLESS",
            protocol = ProxyProtocol.VLESS.name,
            address = "198.51.100.10",
            port = 443,
            uuidOrPassword = "uuid-primary-1111",
            transport = TransportType.TCP.name,
            security = SecurityType.REALITY.name,
            sni = "reality.example.com",
            publicKey = "pbk-primary",
            shortId = "0102"
        )
        val relayNode = ServerProfileEntity(
            id = "relay-trojan",
            name = "Relay-Trojan",
            protocol = ProxyProtocol.TROJAN.name,
            address = "203.0.113.99",
            port = 8443,
            uuidOrPassword = "pass-relay-secret",
            transport = TransportType.WS.name,
            security = SecurityType.TLS.name,
            sni = "relay.example.net"
        )

        // 1. WARP_TO_PROXY
        val warpToProxyJson = JSONObject(
            XrayConfigCompiler.compile(
                node = primaryNode,
                settings = StabilitySettings(
                    chainingMode = ChainingMode.WARP_TO_PROXY,
                    warpEndpoint = "162.159.192.9:2408",
                    warpPrivateKey = "warp-priv",
                    warpPublicKey = "warp-pub",
                    realityFragmentEnabled = true,
                    tlsFragmentEnabled = true
                ),
                rules = emptyList()
            )
        )
        val w2pOutbounds = warpToProxyJson.getJSONArray("outbounds")
        assertEquals("proxy", w2pOutbounds.getJSONObject(0).getString("tag"))
        assertEquals(
            "warp-out",
            w2pOutbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy")
        )
        assertTrue(
            w2pOutbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("realitySettings").getBoolean("fragment")
        )
        assertEquals("warp-out", w2pOutbounds.getJSONObject(1).getString("tag"))
        assertEquals("wireguard", w2pOutbounds.getJSONObject(1).getString("protocol"))

        // 2. PROXY_TO_WARP
        val proxyToWarpJson = JSONObject(
            XrayConfigCompiler.compile(
                node = relayNode,
                settings = StabilitySettings(
                    chainingMode = ChainingMode.PROXY_TO_WARP,
                    warpEndpoint = "188.114.96.4:8854",
                    warpPrivateKey = "warp-priv-2",
                    warpPublicKey = "warp-pub-2"
                ),
                rules = emptyList()
            )
        )
        val p2wOutbounds = proxyToWarpJson.getJSONArray("outbounds")
        assertEquals("proxy", p2wOutbounds.getJSONObject(0).getString("tag"))
        assertEquals("wireguard", p2wOutbounds.getJSONObject(0).getString("protocol"))
        assertEquals(
            "relay-proxy-out",
            p2wOutbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy")
        )
        assertEquals("relay-proxy-out", p2wOutbounds.getJSONObject(1).getString("tag"))
        assertEquals("trojan", p2wOutbounds.getJSONObject(1).getString("protocol"))

        // 3. NODE_TO_NODE + REALITY Fragment
        val n2nJson = JSONObject(
            XrayConfigCompiler.compile(
                node = primaryNode,
                settings = StabilitySettings(
                    chainingMode = ChainingMode.NODE_TO_NODE,
                    relayNodeId = relayNode.id,
                    realityFragmentEnabled = true,
                    tlsFragmentEnabled = true
                ),
                rules = emptyList(),
                relayNode = relayNode
            )
        )
        val n2nOutbounds = n2nJson.getJSONArray("outbounds")
        assertEquals("proxy", n2nOutbounds.getJSONObject(0).getString("tag"))
        assertEquals(
            "relay-out",
            n2nOutbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy")
        )
        assertEquals("relay-out", n2nOutbounds.getJSONObject(1).getString("tag"))
        assertEquals(
            "fragment-out",
            n2nOutbounds.getJSONObject(1).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy")
        )

        val dialerChain = LuxyVpnCore.describeDialerChain(
            StabilitySettings(
                chainingMode = ChainingMode.WARP_TO_PROXY,
                mixedCaseSni = true,
                tlsPaddingEnabled = true,
                realityFragmentEnabled = true
            ),
            primaryNode
        )
        assertTrue(dialerChain.contains("WARP → Proxy"))
        assertTrue(dialerChain.contains("MixedSNI"))
        assertTrue(dialerChain.contains("TLSPad(7685)"))
        assertTrue(dialerChain.contains("+REALITY"))
    }

    @Test
    fun redactsLockedWireGuardKeysReservedAndChainedRelayDetails() {
        val unlockedRelay = ServerProfileEntity(
            id = "unlocked-relay",
            name = "Public-Relay",
            protocol = ProxyProtocol.VLESS.name,
            address = "198.51.100.50",
            port = 443,
            uuidOrPassword = "public-uuid"
        )
        val lockedAwg = ProxyUriParser.parseSingle(
            "awg://TopSecretWgPrivKey999=@162.159.192.88:2408?publickey=TopSecretPeerPub888=&reserved=77,88,99&jc=5#Locked-AWG"
        )!!.copy(isLocked = true, vaultKeyScheme = EncryptedConfigVault.SCHEME_X25519)

        val lockedRelay = ServerProfileEntity(
            id = "locked-relay",
            name = "Locked-Relay",
            protocol = ProxyProtocol.TROJAN.name,
            address = "203.0.113.222",
            port = 9443,
            uuidOrPassword = "ultra-secret-trojan-password",
            sni = "secret-relay-sni.internal",
            isLocked = true,
            vaultKeyScheme = EncryptedConfigVault.SCHEME_PBKDF2
        )

        val redactedInspector = XrayConfigCompiler.compileRedactedForInspector(
            node = lockedAwg,
            settings = StabilitySettings(
                chainingMode = ChainingMode.NODE_TO_NODE,
                relayNodeId = lockedRelay.id
            ),
            rules = emptyList(),
            relayNode = lockedRelay
        )

        assertFalse(redactedInspector.contains("TopSecretWgPrivKey999="))
        assertFalse(redactedInspector.contains("TopSecretPeerPub888="))
        assertFalse(redactedInspector.contains("162.159.192.88"))
        assertFalse(redactedInspector.contains("77"))
        assertFalse(redactedInspector.contains("203.0.113.222"))
        assertFalse(redactedInspector.contains("9443"))
        assertFalse(redactedInspector.contains("ultra-secret-trojan-password"))
        assertFalse(redactedInspector.contains("secret-relay-sni.internal"))
        assertTrue(redactedInspector.contains(EncryptedConfigVault.REDACTED_VALUE))
        assertTrue(redactedInspector.contains("LOCKED_NO_REVEAL"))

        // Also check when primary is unlocked but relay is locked
        val mixedInspector = XrayConfigCompiler.compileRedactedForInspector(
            node = unlockedRelay,
            settings = StabilitySettings(chainingMode = ChainingMode.NODE_TO_NODE, relayNodeId = lockedRelay.id),
            rules = emptyList(),
            relayNode = lockedRelay
        )
        assertFalse(mixedInspector.contains("ultra-secret-trojan-password"))
        assertFalse(mixedInspector.contains("203.0.113.222"))
        assertTrue(mixedInspector.contains("LOCKED_NO_REVEAL"))
    }

    @Test
    fun warpEndpointScannerSupportsStandardPortsAndCandidateExpansion() {
        val expectedPorts = listOf(2408, 1701, 500, 4500, 854, 890, 908, 1002, 2371, 4198, 8854)
        assertEquals(expectedPorts, CleanIpScanner.WARP_STANDARD_PORTS)

        val expanded = CleanIpScanner.expandWarpEndpoints(
            input = "162.159.192.0/24",
            ports = listOf(2408, 854, 4198),
            maxEndpoints = 12
        )
        assertEquals(12, expanded.size)
        assertTrue(expanded.any { it.second == 2408 })
        assertTrue(expanded.any { it.second == 854 })
        assertTrue(expanded.any { it.second == 4198 })
    }
}
