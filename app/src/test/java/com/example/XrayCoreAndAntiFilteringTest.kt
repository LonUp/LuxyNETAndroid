package com.example

import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.xraycore.CleanIpScanner
import com.example.xraycore.LuxyVpnCore
import com.example.xraycore.XrayConfigCompiler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XrayCoreAndAntiFilteringTest {

    @Test
    fun xrayConfigCompilerIncludesFragmentNoiseAndFakeDns() {
        val node = ServerProfileEntity(
            name = "CDN-XHTTP",
            protocol = ProxyProtocol.VLESS.name,
            address = "104.16.10.20",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = TransportType.XHTTP.name,
            security = SecurityType.TLS.name,
            sni = "cdn.example.org",
            path = "/xhttp"
        )
        val settings = StabilitySettings(
            antiCensorshipPreset = AntiCensorshipPreset.STRICT_DPI_BYPASS,
            tlsFragmentEnabled = true,
            fragmentPackets = "1-3",
            fragmentLength = "3-10",
            fragmentInterval = "10-20",
            noiseEnabled = true,
            noisePacketSize = "20-50",
            fakeDnsEnabled = true,
            sniffingEnabled = true,
            blockQuicUdp = true,
            utlsFingerprint = "random",
            xhttpMode = "packet-up",
            xhttpPaddingBytes = "100-1000"
        )

        val compiledJson = XrayConfigCompiler.compile(node, settings, LuxyVpnDatabase.defaultRoutingRules())
        val root = JSONObject(compiledJson)

        assertTrue(root.has("fakedns"))
        val outbounds = root.getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("proxy"))
        assertTrue(tags.contains("fragment-out"))
        assertTrue(tags.contains("noise-out"))

        val proxySockopt = outbounds.getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("sockopt")
        assertEquals("fragment-out", proxySockopt.getString("dialerProxy"))
    }

    @Test
    fun cleanIpScannerExpandsCidrAndRangeParsing() {
        val ips = CleanIpScanner.expandCandidates("104.16.0.0/24", maxSamples = 10)
        assertEquals(10, ips.size)
        assertTrue(ips.all { it.startsWith("104.16.0.") })

        val range = LuxyVpnCore.parseRangePair("10-20", 5, 10)
        assertEquals(10, range.first)
        assertEquals(20, range.last)
    }

    @Test
    fun maxStealthCompilesCipherSuitesFinalMaskEchAndHappyEyeballs() {
        val node = ServerProfileEntity(
            name = "Luxy-CDN",
            protocol = ProxyProtocol.VLESS.name,
            address = "172.67.10.5",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = TransportType.WS.name,
            security = SecurityType.TLS.name,
            sni = "stealth.example.org",
            fingerprint = "unsafe",
            cipherSuites = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256",
            finalMask = "header-custom",
            echConfigList = "AEX+TEST==",
            echForceQuery = "half"
        )
        val settings = StabilitySettings(
            antiCensorshipPreset = AntiCensorshipPreset.MAX_STEALTH,
            tlsFragmentEnabled = true,
            fragmentPackets = "1-3",
            fragmentLength = "5-12",
            fragmentInterval = "8-18",
            fragmentHost = "alt-sni.example.org",
            fragmentMaxSplit = "64-128",
            noiseEnabled = true,
            noiseType = "str",
            noiseApplyTo = "ip",
            echEnabled = true,
            happyEyeballsEnabled = true,
            happyEyeballsTryDelayMs = 200
        )

        val compiledJson = XrayConfigCompiler.compile(node, settings, emptyList())
        val root = JSONObject(compiledJson)
        val outbounds = root.getJSONArray("outbounds")
        val proxyStream = outbounds.getJSONObject(0).getJSONObject("streamSettings")

        val tls = proxyStream.getJSONObject("tlsSettings")
        assertEquals("unsafe", tls.getString("fingerprint"))
        assertEquals("TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256", tls.getString("cipherSuites"))
        assertEquals("AEX+TEST==", tls.getString("echConfigList"))
        assertEquals("half", tls.getString("echForceQuery"))

        val finalMask = proxyStream.getJSONObject("finalmask")
        assertEquals("header-custom", finalMask.getJSONArray("tcp").getJSONObject(0).getString("type"))

        val sockopt = proxyStream.getJSONObject("sockopt")
        assertEquals("fragment-out", sockopt.getString("dialerProxy"))
        assertEquals(200, sockopt.getJSONObject("happyEyeballs").getInt("tryDelayMs"))

        val fragOutbound = outbounds.getJSONObject(1)
        assertEquals("fragment-out", fragOutbound.getString("tag"))
        assertEquals("64-128", fragOutbound.getJSONObject("settings").getJSONObject("fragment").getString("maxSplit"))
        assertEquals("alt-sni.example.org", fragOutbound.getJSONObject("settings").getJSONObject("fragment").getString("host"))
        assertEquals("noise-out", fragOutbound.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))

        val noiseOutbound = outbounds.getJSONObject(2)
        assertEquals("noise-out", noiseOutbound.getString("tag"))
        assertEquals("str", noiseOutbound.getJSONObject("settings").getJSONArray("noise").getJSONObject(0).getString("type"))

        val chain = LuxyVpnCore.describeDialerChain(settings, node)
        assertTrue(chain.contains("FinalMask(header-custom)"))
        assertTrue(chain.contains("ECH("))
        assertTrue(chain.contains("HappyEyeballs"))
    }
}
