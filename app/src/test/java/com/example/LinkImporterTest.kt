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
    private val id = "11111111-2222-3333-4444-555555555555"
    private val vless = "vless://$id@vless.example:443?security=tls&type=ws&sni=cdn.example&path=%2Fws#Server"

    @Test fun importsOnlyActualStableProtocols() {
        val vmess = JSONObject().put("add", "vmess.example").put("port", "443")
            .put("id", id).put("net", "grpc").put("tls", "tls").put("scy", "auto")
        val ss = Base64Compat.encodeToString("aes-256-gcm:p+a:ss".toByteArray())
        val links = listOf(vless, "vmess://" + Base64Compat.encodeToString(vmess.toString().toByteArray()),
            "trojan://p%2Bass@trojan.example:443?sni=cdn.example",
            "ss://$ss@ss.example:8388", "socks://user:pass@socks.example:1080")
        val results = LinkImporter.handleMany(links.joinToString("\n"))
        assertEquals(5, results.size)
        assertTrue(results.all { it is LinkImporter.Result.Nodes })
        val nodes = results.flatMap { (it as LinkImporter.Result.Nodes).nodes }
        assertEquals(listOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "SOCKS"), nodes.map { it.protocol })
        assertEquals("/ws", nodes[0].path)
        assertEquals("p+ass", nodes[2].uuidOrPassword)
        assertEquals("p+a:ss", nodes[3].uuidOrPassword)
        assertEquals("user:pass", nodes[4].uuidOrPassword)
    }

    @Test fun importsTlsSocksWithoutDroppingAuthentication() {
        val result = LinkImporter.handle("socks5://u%2Bser:p%40ss@host.example:443?security=tls&sni=tls.example")
            as LinkImporter.Result.Nodes
        val node = result.nodes.single()
        assertEquals("u+ser:p@ss", node.uuidOrPassword)
        assertEquals(SecurityType.TLS.name, node.security)
        assertEquals("tls.example", node.sni)
    }

    @Test fun rejectsForkFeaturesUnknownTransportsAndInvalidPorts() {
        listOf(
            "hy2://pass@host.example:443", "awg://key@host.example:443", "masque://key@host.example:443",
            "vless://$id@host.example:443?security=none",
            "vless://$id@host.example:443?security=tls&fm=header-custom",
            "vless://$id@host.example:443?security=tls&ech=config",
            "vless://$id@host.example:443?security=tls&type=not-a-network",
            "vless://$id@host.example:0?security=tls",
            "vless://$id@[::1]:bad?security=tls",
            "ss://YWVzLTI1Ni1nY206cA@host.example:443?plugin=obfs",
            "clash://proxies:", "sing-box://{}"
        ).forEach { assertTrue(it, LinkImporter.handle(it) is LinkImporter.Result.Unsupported) }
    }

    @Test fun expandsBase64ListsAndRetainsVisibleRejections() {
        val encoded = Base64Compat.encodeToString("$vless\nhy2://p@bad.example:443".toByteArray())
        val results = LinkImporter.handleMany(encoded)
        assertEquals(2, results.size)
        assertTrue(results[0] is LinkImporter.Result.Nodes)
        assertTrue(results[1] is LinkImporter.Result.Unsupported)
    }

    @Test fun prettyPrintedXrayJsonIsOneDocumentAndExtractsEveryEndpoint() {
        val raw = """[
          {"remarks":"One","outbounds":[
            {"protocol":"freedom","tag":"direct"},
            {"protocol":"socks","settings":{"servers":[
              {"address":"one.example","port":1080,"users":[{"user":"first","pass":"p"},{"user":"second","pass":"q"}]},
              {"address":"two.example","port":1081}]}}
          ]},
          {"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"three.example","port":443,"password":" p "}]},
            "streamSettings":{"network":"grpc","security":"tls","tlsSettings":{"serverName":"tls.example"},"grpcSettings":{"serviceName":"service","authority":"grpc.example"}}}]}
        ]"""
        val results = LinkImporter.handleMany(raw)
        assertEquals(1, results.size)
        val nodes = (results.single() as LinkImporter.Result.Nodes).nodes
        assertEquals(4, nodes.size)
        assertEquals(listOf("first:p", "second:q", "", " p "), nodes.map { it.uuidOrPassword })
        assertEquals("service", nodes.last().serviceName)
        assertEquals(TransportType.GRPC.name, nodes.last().transport)
        assertEquals("grpc.example", nodes.last().hostHeader)
    }

    @Test fun foreignJsonAndUnsupportedOutboundsAreNotFakeSuccesses() {
        listOf("""{"outbounds":[{"type":"vless","server":"host.example","server_port":443,"uuid":"$id"}]}""",
            """{"proxies":[{"type":"ss","server":"host.example"}]}""", "{broken json}").forEach {
            assertTrue(LinkImporter.handleMany(it).single() is LinkImporter.Result.Unsupported)
        }
        val mixed = """{"outbounds":[
          {"protocol":"socks","settings":{"servers":[{"address":"host.example","port":1080}]}},
          {"protocol":"hysteria2","settings":{"address":"bad.example","port":443,"auth":"pass"}}
        ]}"""
        val result = LinkImporter.handle(mixed) as LinkImporter.Result.Nodes
        assertEquals(1, result.nodes.size)
        assertEquals(1, result.failures.size)
    }

    @Test fun registryDoesNotAdvertiseUnsupportedFormats() {
        assertEquals(setOf("http", "https", "vless", "vmess", "trojan", "ss", "socks", "socks5"),
            LinkImporter.supportedSchemes.toSet())
        assertFalse(LinkImporter.isCompilable(ProxyProtocol.HYSTERIA2))
        assertTrue(LinkImporter.isCompilable(ProxyProtocol.SOCKS))
    }

    @Test fun grpcShareLinksPreserveServiceAndAuthority() {
        val uri = "vless://$id@host.example:443?security=tls&type=grpc&serviceName=service&authority=grpc.example"
        val vlessNode = (LinkImporter.handle(uri) as LinkImporter.Result.Nodes).nodes.single()
        assertEquals("service", vlessNode.serviceName)
        assertEquals("grpc.example", vlessNode.hostHeader)
        val vmess = JSONObject().put("add", "host.example").put("port", "443").put("id", id)
            .put("net", "grpc").put("type", "gun").put("path", "service").put("host", "grpc.example")
        val vmessNode = (LinkImporter.handle("vmess://" + Base64Compat.encodeToString(vmess.toString().toByteArray()))
            as LinkImporter.Result.Nodes).nodes.single()
        assertEquals("service", vmessNode.serviceName)
        assertEquals("grpc.example", vmessNode.hostHeader)
    }
}
