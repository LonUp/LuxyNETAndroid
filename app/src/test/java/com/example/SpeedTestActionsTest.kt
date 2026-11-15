package com.example

import com.example.core.SpeedProbeRunner
import com.example.core.SpeedTestEngine
import com.example.data.model.ConnectionState
import com.example.data.model.SPEED_TEST_MULTI_THREAD
import com.example.data.model.SpeedTestAction
import com.example.data.model.SpeedTestMode
import com.example.data.model.SpeedTestStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpeedTestActionsTest {

    private class RecordingRunner(
        private val downloadMbps: Double? = 48.0,
        private val uploadMbps: Double? = 24.0,
        private val pingFails: Boolean = false
    ) : SpeedProbeRunner {
        var pingCalls = 0
        var downloadCalls = 0
        var uploadCalls = 0

        override suspend fun measurePing204(mode: SpeedTestMode): Double {
            pingCalls++
            if (pingFails) throw IOException("ping blocked")
            return 25.0
        }

        override suspend fun measureDownloadWindow(
            mode: SpeedTestMode,
            durationMs: Long,
            onSample: suspend (Long, Long, Double) -> Unit
        ): Pair<Long, Long> {
            downloadCalls++
            val mbps = downloadMbps ?: throw IOException("download blocked")
            onSample(250L, 1_500_000L, mbps)
            return 30_000_000L to 5_000L
        }

        override suspend fun measureUploadWindow(
            mode: SpeedTestMode,
            durationMs: Long,
            onSample: suspend (Long, Long, Double) -> Unit
        ): Pair<Long, Long> {
            uploadCalls++
            val mbps = uploadMbps ?: throw IOException("upload blocked")
            onSample(250L, 750_000L, mbps)
            return 15_000_000L to 5_000L
        }
    }

    @Test
    fun pingOnlySkipsEveryThroughputPhase() = runTest {
        val runner = RecordingRunner()
        val emissions = SpeedTestEngine.runSpeedTest(
            mode = SpeedTestMode.DIRECT,
            action = SpeedTestAction.PING,
            threads = SPEED_TEST_MULTI_THREAD,
            connectionState = ConnectionState.DISCONNECTED,
            activeNode = null,
            runner = runner
        ).toList()

        assertEquals(0, runner.downloadCalls)
        assertEquals(0, runner.uploadCalls)
        assertTrue(runner.pingCalls > 0)

        val result = emissions.last().second!!
        assertEquals(SpeedTestAction.PING, result.action)
        assertEquals(25.0, result.pingMs, 0.01)
        assertEquals(0.0, result.downloadMbps, 0.01)
        assertEquals(0.0, result.uploadMbps, 0.01)
        assertEquals(SpeedTestStage.COMPLETE, emissions.last().first.stage)
    }

    @Test
    fun downloadOnlySkipsPingAndUpload() = runTest {
        val runner = RecordingRunner()
        val emissions = SpeedTestEngine.runSpeedTest(
            mode = SpeedTestMode.DIRECT,
            action = SpeedTestAction.DOWNLOAD,
            threads = SPEED_TEST_MULTI_THREAD,
            connectionState = ConnectionState.DISCONNECTED,
            activeNode = null,
            runner = runner
        ).toList()

        assertEquals(0, runner.pingCalls)
        assertEquals(0, runner.uploadCalls)
        assertEquals(1, runner.downloadCalls)

        val stages = emissions.map { it.first.stage }.distinct()
        assertFalse(stages.contains(SpeedTestStage.PING))
        assertFalse(stages.contains(SpeedTestStage.UPLOAD))

        val result = emissions.last().second!!
        assertEquals(SpeedTestAction.DOWNLOAD, result.action)
        assertEquals(SPEED_TEST_MULTI_THREAD, result.threads)
        assertEquals(48.0, result.downloadMbps, 0.01)
        assertEquals(0.0, result.uploadMbps, 0.01)
    }

    @Test
    fun uploadOnlySkipsPingAndDownload() = runTest {
        val runner = RecordingRunner()
        val emissions = SpeedTestEngine.runSpeedTest(
            mode = SpeedTestMode.DIRECT,
            action = SpeedTestAction.UPLOAD,
            threads = SPEED_TEST_MULTI_THREAD,
            connectionState = ConnectionState.DISCONNECTED,
            activeNode = null,
            runner = runner
        ).toList()

        assertEquals(0, runner.pingCalls)
        assertEquals(0, runner.downloadCalls)
        assertEquals(1, runner.uploadCalls)

        val result = emissions.last().second!!
        assertEquals(SpeedTestAction.UPLOAD, result.action)
        assertEquals(24.0, result.uploadMbps, 0.01)
        assertTrue(emissions.all { it.first.threads == SPEED_TEST_MULTI_THREAD })
    }

    @Test
    fun aBlockedDownloadBecomesAWarningInsteadOfKillingTheRun() = runTest {
        val runner = RecordingRunner(downloadMbps = null)
        val emissions = SpeedTestEngine.runSpeedTest(
            mode = SpeedTestMode.DIRECT,
            action = SpeedTestAction.FULL,
            threads = 1,
            connectionState = ConnectionState.DISCONNECTED,
            activeNode = null,
            runner = runner
        ).toList()

        val result = emissions.last().second!!
        assertEquals(0.0, result.downloadMbps, 0.01)
        assertEquals(24.0, result.uploadMbps, 0.01)
        assertEquals(25.0, result.pingMs, 0.01)
        assertTrue(result.warnings.any { it.contains("Download failed") })
        assertNotNull(emissions.lastOrNull { it.second != null })
    }

    @Test
    fun aRunWhereEveryPhaseFailsSurfacesAnError() = runTest {
        val runner = RecordingRunner(downloadMbps = null, uploadMbps = null, pingFails = true)
        val failure = runCatching {
            SpeedTestEngine.runSpeedTest(
                mode = SpeedTestMode.DIRECT,
                action = SpeedTestAction.FULL,
                threads = 1,
                connectionState = ConnectionState.DISCONNECTED,
                activeNode = null,
                runner = runner
            ).toList()
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("Download failed"))
    }
}
