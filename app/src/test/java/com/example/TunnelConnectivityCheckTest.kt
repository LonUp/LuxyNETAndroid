package com.example

import com.example.xraycore.TunnelConnectivityCheck
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelConnectivityCheckTest {
    private lateinit var slowOrFailing: MockWebServer
    private lateinit var healthy: MockWebServer

    @Before
    fun setUp() {
        slowOrFailing = MockWebServer().apply { start() }
        healthy = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { slowOrFailing.shutdown() }
        runCatching { healthy.shutdown() }
    }

    @Test
    fun concurrentRaceSucceedsQuicklyWhenOneEndpointStalls() = runBlocking {
        slowOrFailing.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(5, TimeUnit.SECONDS))
        healthy.enqueue(MockResponse().setResponseCode(204))

        val check = TunnelConnectivityCheck(
            endpoints = listOf(
                slowOrFailing.url("/generate_204").toString(),
                healthy.url("/generate_204").toString()
            ),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        )

        val startNs = System.nanoTime()
        assertTrue(check.isReachable())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        assertTrue("Expected fast concurrent win, took ${elapsedMs}ms", elapsedMs < 1_500L)
    }

    @Test
    fun returnsFalseWhenAllEndpointsFail() = runBlocking {
        slowOrFailing.enqueue(MockResponse().setResponseCode(503))
        healthy.enqueue(MockResponse().setResponseCode(200))

        val check = TunnelConnectivityCheck(
            endpoints = listOf(
                slowOrFailing.url("/generate_204").toString(),
                healthy.url("/generate_204").toString()
            ),
            proxy = Proxy.NO_PROXY,
            overallTimeoutMs = 2_000L,
            callTimeoutMs = 1_500L
        )

        assertFalse(check.isReachable())
    }
}
