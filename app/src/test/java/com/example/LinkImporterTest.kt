package com.example

import com.example.core.Base64Compat
import com.example.data.import.LinkImporter
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkImporterTest {

    private val vmessLink: String by lazy {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", "vmess-node")
            put("add", "vmess.example.com")
            put("port", "443")
            put("id", "11111111-2222-3333-4444-555555555555")
            put("aid", "0")
            put("scy", "auto")
            put("net", "ws")
            put("type", "none")
            put("host", "cdn.example.com")
            put("path", "/ray")
            put("tls", "tls")
            put("sni", "cdn.example.com")
        }.toString()
        "vmess://" + Base64Compat.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private val ssLink: String by lazy {
        val userInfo = Base64Compat.encodeToString("aes-256-gcm:secret".toByteArray(Charsets.UTF_8))
        "ss://$userInfo@ss.example.com:8388#ss-node"
    }

    private val mieruLink: String by lazy {
        val json = JSONObject().apply {
            put("server", "mieru.example.com")
            put("port", 8443)
            put("password", "pw")
            put("method", "AES-128-GCM")
        }.toString()
        "mieru://" + Base64Compat.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** One valid link per registered alias — the 37-scheme contract. */
    private val samples: Map<String, String> = mapOf(
        "vless" to "vless://11111111-2222-3333-4444-555555555555@h1.example.com:443?type=ws&security=tls&sni=sni.example.com&path=%2Fws#vless-node",
        "vmess" to vmessLink,
        "trojan" to "trojan://secret@h2.example.com:443?sni=sni.example.com#trojan-node",
        "ss" to ssLink,
        "hy2" to "hy2://auth@h3.example.com:443?sni=sni.example.com#hy2-node",
        "hysteria2" to "hysteria2://auth@h4.example.com:443#hy2-full-node",
        "tuic" to "tuic://uuid:pass@h5.example.com:443?sni=sni.example.com#tuic-node",
        "wireguard" to "wireguard://privkey@h6.example.com:51820?publickey=pubkey#wg-node",
        "wg" to "wg://privkey@h7.example.com:51820?publickey=pubkey#wg-short",
        "hysteria" to "hysteria://auth@h8.example.com:443#hy1-node",
        "anytls" to "anytls://pass@h9.example.com:443?sni=sni.example.com#anytls-node",
        "socks" to "socks://user:pass@h10.example.com:1080#socks-node",
        "socks5" to "socks5://user:pass@h11.example.com:1080#socks5-node",
        "socks4" to "socks4://h12.example.com:1080#socks4-node",
        "ssh" to "ssh://user:pass@h13.example.com:22#ssh-node",
        "openconnect" to "openconnect://user@h14.example.com:443#oc-node",
        "anyconnect" to "anyconnect://user@h15.example.com:443#ac-node",
        "dnstt" to "dnstt://h16.example.com:53?publickey=pubkey#dnstt-node",
        "mdv" to "mdv://h17.example.com:53?publickey=pubkey#mdv-node",
        "mieru" to mieruLink,
        "awg" to "awg://privkey@h18.example.com:51820?publickey=pubkey&jc=4&h1=1#awg-node",
        "amneziawg" to "amneziawg://privkey@h19.example.com:51820?publickey=pubkey#awg-long",
        "sing-box" to "sing-box://import-remote-profile?url=https%3A%2F%2Fsub.example.com%2Fsing#sing",
        "v2fall" to "v2fall://sub?url=https%3A%2F%2Fsub.example.com%2Fv2f#v2fall",
        "v2rayng" to "v2rayng://install-sub?url=https%3A%2F%2Fsub.example.com%2Fng#ng",
        "hiddify" to "hiddify://install-config?url=https%3A%2F%2Fsub.example.com%2Fhid#hid",
        "happ" to "happ://add?url=https%3A%2F%2Fsub.example.com%2Fhapp#happ",
        "streisand" to "streisand://import?url=https%3A%2F%2Fsub.example.com%2Fstr#str",
        "clash" to "clash://install-config?url=https%3A%2F%2Fsub.example.com%2Fclash#clash",
        "clashmeta" to "clashmeta://install-config?url=https%3A%2F%2Fsub.example.com%2Fmeta#meta",
        "v2box" to "v2box://install-sub?url=https%3A%2F%2Fsub.example.com%2Fbox#box",
        "karing" to "karing://install-config?url=https%3A%2F%2Fsub.example.com%2Fkar#kar",
        "sn" to "sn://subscription?url=https%3A%2F%2Fsub.example.com%2Fsn#sn",
        "v2raytun" to "v2raytun://import?url=https%3A%2F%2Fsub.example.com%2Ftun#tun",
        "foxray" to "foxray://install?url=https%3A%2F%2Fsub.example.com%2Ffox#fox",
        "content" to "content://com.example.provider/config",
        "file" to "file:///sdcard/Download/config.json"
    )

    @Test
    fun registryListsExactlyTheThirtySevenReferenceSchemes() {
        assertEquals(37, LinkImporter.supportedSchemes.size)
        assertEquals(37, LinkImporter.supportedSchemes.distinct().size)
        assertEquals(LinkImporter.supportedSchemes.toSet(), samples.keys)
    }

    @Test
    fun everyRegisteredSchemeIsHandled() {
        for (scheme in LinkImporter.supportedSchemes) {
            val link = samples.getValue(scheme)
            val result = LinkImporter.handle(link)
            assertFalse("scheme '$scheme' was rejected", result is LinkImporter.Result.Unsupported)
        }
    }

    @Test
    fun vlessMapsEveryFieldTheCompilerReads() {
        val node = (LinkImporter.handle(samples.getValue("vless")) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals(ProxyProtocol.VLESS.name, node.protocol)
        assertEquals("h1.example.com", node.address)
        assertEquals(443, node.port)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuidOrPassword)
        assertEquals(TransportType.WS.name, node.transport)
        assertEquals(SecurityType.TLS.name, node.security)
        assertEquals("sni.example.com", node.sni)
        assertEquals("/ws", node.path)
        assertEquals("vless-node", node.name)
        assertTrue(node.rawUri.startsWith("vless://"))
    }

    @Test
    fun vmessDecodesBase64Json() {
        val node = (LinkImporter.handle(vmessLink) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals(ProxyProtocol.VMESS.name, node.protocol)
        assertEquals("vmess.example.com", node.address)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuidOrPassword)
        assertEquals(TransportType.WS.name, node.transport)
        assertEquals("cdn.example.com", node.hostHeader)
        assertEquals("/ray", node.path)
        assertEquals("vmess-node", node.name)
    }

    @Test
    fun shadowsocksDecodesSip002UserInfo() {
        val node = (LinkImporter.handle(ssLink) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals(ProxyProtocol.SHADOWSOCKS.name, node.protocol)
        assertEquals("aes-256-gcm", node.encryption)
        assertEquals("secret", node.uuidOrPassword)
        assertEquals(8388, node.port)
    }

    @Test
    fun mdvAliasMapsToMasterDns() {
        val node = (LinkImporter.handle(samples.getValue("mdv")) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals(ProxyProtocol.MASTERDNS.name, node.protocol)
    }

    @Test
    fun wrappersExtractTheSubscriptionUrl() {
        val expected = setOf("sing-box", "v2fall", "v2rayng", "hiddify", "happ", "streisand", "clash", "clashmeta", "v2box", "karing", "sn", "v2raytun", "foxray")
        for (scheme in expected) {
            val result = LinkImporter.handle(samples.getValue(scheme)) as LinkImporter.Result.Subscription
            assertTrue("$scheme url: ${result.url}", result.url.startsWith("https://sub.example.com/"))
        }
    }

    @Test
    fun contentAndFileBecomeDocuments() {
        val content = LinkImporter.handle(samples.getValue("content")) as LinkImporter.Result.Document
        assertEquals(LinkImporter.DocumentKind.URI, content.kind)
        val file = LinkImporter.handle(samples.getValue("file")) as LinkImporter.Result.Document
        assertEquals(LinkImporter.DocumentKind.URI, file.kind)
    }

    @Test
    fun plainSubscriptionUrlIsASubscription() {
        val result = LinkImporter.handle("https://example.com/sub?token=1") as LinkImporter.Result.Subscription
        assertEquals("https://example.com/sub?token=1", result.url)
    }

    @Test
    fun multiLinePasteYieldsOneResultPerLink() {
        val text = listOf(
            samples.getValue("vless"),
            samples.getValue("trojan"),
            "not a link at all"
        ).joinToString("\n")
        val results = LinkImporter.handleMany(text)
        assertEquals(3, results.size)
        assertEquals(2, results.count { it is LinkImporter.Result.Nodes })
        assertEquals(1, results.count { it is LinkImporter.Result.Unsupported })
    }

    @Test
    fun base64BodyExpandsIntoLinks() {
        val body = listOf(samples.getValue("vless"), samples.getValue("trojan")).joinToString("\n")
        val encoded = Base64Compat.encodeToString(body.toByteArray(Charsets.UTF_8))
        val doc = LinkImporter.handle(encoded) as LinkImporter.Result.Document
        assertEquals(LinkImporter.DocumentKind.PLAIN_LIST, doc.kind)
        assertEquals(2, LinkImporter.handleMany(doc.content).count { it is LinkImporter.Result.Nodes })
    }

    @Test
    fun garbageIsRejectedWithAReason() {
        val result = LinkImporter.handle("ftp://nope") as LinkImporter.Result.Unsupported
        assertTrue(result.reason.contains("unsupported"))
        val empty = LinkImporter.handle("") as LinkImporter.Result.Unsupported
        assertEquals("empty input", empty.reason)
    }

    @Test
    fun unsupportedProtocolsImportButAreMarkedUncompilable() {
        val node = (LinkImporter.handle(samples.getValue("tuic")) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals(ProxyProtocol.TUIC.name, node.protocol)
        assertFalse(LinkImporter.isCompilable(ProxyProtocol.TUIC))
        assertTrue(LinkImporter.isCompilable(ProxyProtocol.VLESS))
        assertTrue(LinkImporter.isCompilable(ProxyProtocol.HTTP))
    }

    @Test
    fun urlEncodedNamesAreDecoded() {
        val node = (LinkImporter.handle(
            "vless://uuid@h.example.com:443?type=tcp&security=tls#%D8%B3%D8%B1%D9%88%D8%B1%20%DB%B1"
        ) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals("سرور ۱", node.name)
    }
}
