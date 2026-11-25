package com.example.xraycore

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * stop()'s bookkeeping and offload contract, exercised without the native core (the
 * bundled libgojni ships only arm ABIs, so no fake CoreController can be constructed on
 * the test JVM). These tests pin:
 *  - stop() advances attempt bookkeeping and returns without dispatching anything when
 *    no core is active (idle stop is a cheap no-op, never executor work);
 *  - the executor seam is swappable and defaults to the daemon pool.
 * The shutdown-offload branch itself (snapshot controller, release startLock, dispatch
 * stopLoop to the executor) is three statements outside the lock, structurally identical
 * to the birth()/start() executor use, and additionally covered on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class XrayEngineStopOffloadTest {

    @After
    fun restore() {
        XrayEngine.controller = null
        XrayEngine.stopExecutor = XrayEngine.defaultStopExecutorForTest
    }

    @Test
    fun stopWithNoControllerAdvancesBookkeepingAndQueuesNothing() {
        val dispatched = CopyOnWriteArrayList<Runnable>()
        val neverRunsInline = Executor { task -> dispatched.add(Runnable { task.run() }) }
        XrayEngine.stopExecutor = neverRunsInline

        XrayEngine.stop(9L)

        assertNull(XrayEngine.controller)
        assertEquals("idle stop must not touch the executor", 0, dispatched.size)
    }

    @Test
    fun idleStopIsCheapEvenThroughTheDefaultDaemonExecutor() {
        val before = XrayEngine.lastCoreError()
        XrayEngine.stop(12L)
        XrayEngine.stop(1L)
        assertEquals(before, XrayEngine.lastCoreError())
        assertNull(XrayEngine.controller)
    }

    @Test
    fun executorSeamSwapsAndRestoresToTheDaemonPool() {
        assertTrue(XrayEngine.stopExecutor === XrayEngine.defaultStopExecutorForTest)
        val custom = Executor { }
        XrayEngine.stopExecutor = custom
        assertSame(custom, XrayEngine.stopExecutor)
        XrayEngine.stopExecutor = XrayEngine.defaultStopExecutorForTest
        assertSame(XrayEngine.defaultStopExecutorForTest, XrayEngine.stopExecutor)
    }
}
