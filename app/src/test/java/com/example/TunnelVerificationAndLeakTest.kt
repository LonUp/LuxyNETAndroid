package com.example

import com.example.core.ExitCountryVerifier
import com.example.core.TunnelLeakChecker
import com.example.data.model.ErrorCategory
import com.example.xraycore.SocksProxiedDns
import com.example.xraycore.TunnelConnectivityCheck
import com.example.xraycore.XrayEngine
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelVerificationAndLeakTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun socksDnsQueriesAAndAaaaThroughLiteralProxyTargetsAndUsesFallbackResolver() {
        FakeSocksDnsProxy(failPrimaryDns = true).use { fakeProxy ->
            val dns = SocksProxiedDns(
                proxy = socksProxy(fakeProxy.port),
                timeoutMs = 1_000
            )

            val addresses = dns.lookup("verification.example")
            fakeProxy.awaitConnections(4)

            assertEquals(setOf("1.1.1.1", "8.8.8.8"), fakeProxy.destinations.map { it.first }.toSet())
            assertTrue(fakeProxy.destinations.all { it.second == 53 })
            assertEquals(
                setOf("203.0.113.10", InetAddress.getByName("2001:db8::10").hostAddress),
                addresses.map { it.hostAddress }.toSet()
            )
        }
    }

    @Test
    fun socksDnsFailureNeverFallsBackToSystemDns() {
        FakeSocksDnsProxy(closeDnsConnection = true).use { fakeProxy ->
            val dns = SocksProxiedDns(
                proxy = socksProxy(fakeProxy.port),
                timeoutMs = 1_000
            )

            val failure = runCatching { dns.lookup("localhost") }.exceptionOrNull()
            fakeProxy.awaitConnections(4)

            assertTrue(failure is UnknownHostException)
            assertEquals(
                listOf("1.1.1.1", "8.8.8.8", "1.1.1.1", "8.8.8.8"),
                fakeProxy.destinations.map { it.first }
            )
        }
    }

    @Test
    fun connectivityCheckReportsReachabilityAndOnlyClaimsSocksDnsVerification() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = TunnelConnectivityCheck(
            endpoints = listOf(server.url("/generate_204").toString()),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        ).check()

        assertTrue(result.reachable)
        assertNotNull(result.latencyMs)
        assertEquals(ErrorCategory.NONE, result.errorCategory)
        assertFalse(result.dnsVerified)
    }

    @Test
    fun exitVerificationCacheSupportsRefreshIsolationAndInvalidation() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("203.0.113.7\n")) }
        val verifier = ExitCountryVerifier(
            endpoints = listOf(server.url("/ip").toString()),
            proxy = Proxy.NO_PROXY
        )
        val key = "profile-1|wifi-a"

        val first = verifier.verifyExit(key)
        val cached = verifier.verifyExit(key)
        assertNotNull(first)
        assertEquals(false, first?.fromCache)
        assertEquals(true, cached?.fromCache)
        assertEquals(1, server.requestCount)

        val otherNetwork = verifier.verifyExit("profile-1|wifi-b")
        assertNotNull(otherNetwork)
        assertEquals(2, server.requestCount)

        val refreshed = verifier.verifyExit(key, forceRefresh = true)
        assertNotNull(refreshed)
        assertEquals(false, refreshed?.fromCache)
        assertEquals(3, server.requestCount)

        verifier.invalidateCache(key)
        val afterInvalidation = verifier.verifyExit(key)
        assertNotNull(afterInvalidation)
        assertEquals(false, afterInvalidation?.fromCache)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun leakCheckerCombinesDnsPostureAndReportsPlatformFlagsHonestly() = runBlocking {
        val protected = TunnelLeakChecker(proxy = Proxy.NO_PROXY, dnsProbe = { true }).check(
            hasIpv6TunRoute = true,
            hasVpnDnsOnly = true,
            alwaysOnEnabled = true,
            lockdownEnabled = false
        )
        assertTrue(protected.dnsVerified)
        assertTrue(protected.ipv6Routed)
        assertTrue(protected.alwaysOnEnabled)
        assertFalse(protected.lockdownEnabled)
        assertTrue(protected.detail.contains("does not assert system-wide leak protection"))

        val missingVpnDnsPosture = TunnelLeakChecker(proxy = Proxy.NO_PROXY, dnsProbe = { true }).check(
            hasIpv6TunRoute = false,
            hasVpnDnsOnly = false,
            alwaysOnEnabled = false,
            lockdownEnabled = true
        )
        assertFalse(missingVpnDnsPosture.dnsVerified)
        assertFalse(missingVpnDnsPosture.ipv6Routed)
        assertFalse(missingVpnDnsPosture.alwaysOnEnabled)
        assertTrue(missingVpnDnsPosture.lockdownEnabled)

        val failedDns = TunnelLeakChecker(proxy = Proxy.NO_PROXY, dnsProbe = { false }).check(
            hasIpv6TunRoute = true,
            hasVpnDnsOnly = true,
            alwaysOnEnabled = false,
            lockdownEnabled = false
        )
        assertFalse(failedDns.dnsVerified)
    }

    @Test
    fun parsesAndSumsOnlyProxyOutboundTrafficStats() {
        val stats = """
            proxy-out,downlink,100;
            proxy-out,uplink,40;
            direct-out,downlink,900;
            proxy-out,downlink,200;
            proxy-out,uplink,60;
            proxy-out,unknown,30;
            proxy-out,downlink,not-a-number
        """.trimIndent()

        assertEquals(300L to 100L, XrayEngine.parseOutboundTrafficStats(stats))
        assertEquals(0L to 0L, XrayEngine.parseOutboundTrafficStats(null))
    }

    private fun socksProxy(port: Int): Proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    )

    /** Minimal local SOCKS5 tunnel endpoint that emulates DNS-over-TCP without external networking. */
    private class FakeSocksDnsProxy(
        private val failPrimaryDns: Boolean = false,
        private val closeDnsConnection: Boolean = false
    ) : Closeable {
        private val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
            soTimeout = 5_000
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val handledConnections = CopyOnWriteArrayList<Unit>()
        val destinations = CopyOnWriteArrayList<Pair<String, Int>>()
        val port: Int get() = listener.localPort

        init {
            executor.execute {
                try {
                    while (!listener.isClosed) {
                        listener.accept().use(::handleConnection)
                    }
                } catch (_: Exception) {
                    // Closing the fixture ends its accept loop.
                }
            }
        }

        fun awaitConnections(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (handledConnections.size < count && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("SOCKS proxy did not receive the expected DNS queries", count, handledConnections.size)
        }

        private fun handleConnection(socket: Socket) {
            socket.soTimeout = 3_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val version = input.readUnsignedByte()
            val methodCount = input.readUnsignedByte()
            repeat(methodCount) { input.readUnsignedByte() }
            if (version != 5) throw AssertionError("Expected SOCKS5 handshake")
            output.write(byteArrayOf(5, 0))
            output.flush()

            input.readUnsignedByte() // version
            input.readUnsignedByte() // CONNECT
            input.readUnsignedByte() // reserved
            val addressType = input.readUnsignedByte()
            val address = when (addressType) {
                1 -> ByteArray(4).also(input::readFully)
                4 -> ByteArray(16).also(input::readFully)
                3 -> ByteArray(input.readUnsignedByte()).also(input::readFully)
                else -> throw AssertionError("Unexpected SOCKS address type: $addressType")
            }
            val remotePort = input.readUnsignedShort()
            val destination = if (addressType == 1 || addressType == 4) {
                InetAddress.getByAddress(address).hostAddress
            } else {
                String(address, Charsets.US_ASCII)
            }
            destinations.add(destination to remotePort)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            handledConnections.add(Unit)

            if (closeDnsConnection) return
            val primaryFailed = failPrimaryDns && destination == "1.1.1.1"
            if (primaryFailed) return

            val queryLength = input.readUnsignedShort()
            val query = ByteArray(queryLength).also(input::readFully)
            val response = dnsAnswer(query)
            output.writeShort(response.size)
            output.write(response)
            output.flush()
        }

        private fun dnsAnswer(query: ByteArray): ByteArray {
            var offset = 12
            while (true) {
                val length = query[offset].toInt() and 0xff
                offset += 1
                if (length == 0) break
                offset += length
            }
            val type = ((query[offset].toInt() and 0xff) shl 8) or (query[offset + 1].toInt() and 0xff)
            val address = when (type) {
                1 -> byteArrayOf(203.toByte(), 0, 113, 10)
                28 -> InetAddress.getByName("2001:db8::10").address
                else -> throw AssertionError("Unexpected DNS type $type")
            }
            val response = ByteArrayOutputStream().apply {
                val headerAndQuestion = query.copyOf().apply {
                    this[2] = 0x81.toByte()
                    this[3] = 0x80.toByte()
                    this[6] = 0
                    this[7] = 1
                    this[8] = 0
                    this[9] = 0
                    this[10] = 0
                    this[11] = 0
                }
                write(headerAndQuestion)
                val answer = DataOutputStream(this)
                answer.writeShort(0xc00c)
                answer.writeShort(type)
                answer.writeShort(1)
                answer.writeInt(60)
                answer.writeShort(address.size)
                answer.write(address)
                answer.flush()
            }.toByteArray()
            return response
        }

        override fun close() {
            runCatching { listener.close() }
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
