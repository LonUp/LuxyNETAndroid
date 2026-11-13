package com.example

import com.example.core.ProxyUriParser
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUriParserTest {

    @Test
    fun parseVlessRealityUri() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.org:443?encryption=none&security=reality&sni=cdn.example.org&fp=chrome&pbk=abc123&sid=01ab&type=tcp&flow=xtls-rprx-vision#Tokyo%20Node"
        val parsed = ProxyUriParser.parseSingle(uri)
        assertNotNull(parsed)
        assertEquals(ProxyProtocol.VLESS.name, parsed!!.protocol)
        assertEquals("example.org", parsed.address)
        assertEquals(443, parsed.port)
        assertEquals(SecurityType.REALITY.name, parsed.security)
        assertEquals("cdn.example.org", parsed.sni)
        assertEquals("abc123", parsed.publicKey)
        assertEquals("01ab", parsed.shortId)
        assertEquals("Tokyo Node", parsed.name)
    }

    @Test
    fun parseTrojanWsUri() {
        val uri = "trojan://secretpass@gateway.example.net:8443?security=tls&type=ws&path=%2Fws&host=ws.example.net#Frankfurt"
        val parsed = ProxyUriParser.parseSingle(uri)
        assertNotNull(parsed)
        assertEquals(ProxyProtocol.TROJAN.name, parsed!!.protocol)
        assertEquals("secretpass", parsed.uuidOrPassword)
        assertEquals(TransportType.WS.name, parsed.transport)
        assertEquals("/ws", parsed.path)
        assertEquals("Frankfurt", parsed.name)
    }

    @Test
    fun parseHysteria2AndBatch() {
        val batch = """
            hysteria2://myauth@hy.example.com:443?sni=hy.example.com&insecure=1#HY2
            socks://user:pass@127.0.0.1:1080#LocalSocks
        """.trimIndent()
        val list = ProxyUriParser.parseBatch(batch)
        assertEquals(2, list.size)
        assertEquals(ProxyProtocol.HYSTERIA2.name, list[0].protocol)
        assertTrue(list[0].allowInsecure)
        assertEquals(ProxyProtocol.SOCKS.name, list[1].protocol)
    }

    @Test
    fun parseMaxStealthExtendedFieldsAndRoundTrip() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@cdn.example.com:443" +
            "?encryption=none&security=tls&type=ws&sni=edge.example.com&fp=unsafe" +
            "&cs=TLS_AES_128_GCM_SHA256%3ATLS_CHACHA20_POLY1305_SHA256" +
            "&fm=header-custom&ech=AEX%2BAQID&echForceQuery=full&pqv=pqkey123#StealthNode"
        val parsed = ProxyUriParser.parseSingle(uri)
        assertNotNull(parsed)
        assertEquals("unsafe", parsed!!.fingerprint)
        assertEquals("TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256", parsed.cipherSuites)
        assertEquals("header-custom", parsed.finalMask)
        assertEquals("AEX+AQID", parsed.echConfigList)
        assertEquals("full", parsed.echForceQuery)
        assertEquals("pqkey123", parsed.mldsa65Verify)

        val regenerated = ProxyUriParser.toShareUri(parsed.copy(rawUri = ""))
        val reparsed = ProxyUriParser.parseSingle(regenerated)
        assertNotNull(reparsed)
        assertEquals("unsafe", reparsed!!.fingerprint)
        assertEquals(parsed.cipherSuites, reparsed.cipherSuites)
        assertEquals("header-custom", reparsed.finalMask)
        assertEquals("AEX+AQID", reparsed.echConfigList)
        assertEquals("full", reparsed.echForceQuery)
        assertEquals("pqkey123", reparsed.mldsa65Verify)
    }
}
