package com.coorlay.coolray

import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import com.coorlay.UiText.tr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BluetoothEnableTest : InstrumentationTestCase() {
    fun testHungEnableHasDeadlineRejectsDuplicateAndIgnoresLateResult() {
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val requests = AtomicInteger()
        val callbacks = AtomicInteger()
        var enabler: BluetoothEnabler? = null
        var result: BluetoothEnableResult? = null
        instrumentation.runOnMainSync {
            enabler = BluetoothEnabler(Handler(Looper.getMainLooper()), Executor { action ->
                Thread(action, "test-hung-bt-enable").apply { isDaemon = true; start() }
            }, request = {
                requests.incrementAndGet()
                release.await(2, TimeUnit.SECONDS)
                returned.countDown()
                BluetoothEnableResult(true)
            })
            enabler!!.start(100) { result = it; callbacks.incrementAndGet(); done.countDown() }
        }
        try {
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertNull(result?.accepted)
            assertTrue(result?.error.orEmpty().contains(tr("не ответила", "did not answer")))
            instrumentation.runOnMainSync {
                enabler!!.start(100) {
                    assertNull(it.accepted)
                    assertTrue(it.error.orEmpty().contains(tr("ещё выполняется", "still running")))
                }
            }
            assertEquals(1, requests.get())
        } finally { release.countDown() }
        assertTrue(returned.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        instrumentation.waitForIdleSync()
        assertEquals("A late enable result must not start Wi-Fi CarPlay", 1, callbacks.get())
    }

    fun testCancelledQueuedEnableDoesNotPowerOnBluetooth() {
        val queued = mutableListOf<Runnable>()
        val requests = AtomicInteger()
        val callbacks = AtomicInteger()
        instrumentation.runOnMainSync {
            val enabler = BluetoothEnabler(Handler(Looper.getMainLooper()), Executor { queued += it }, request = {
                requests.incrementAndGet(); BluetoothEnableResult(true)
            })
            enabler.start { callbacks.incrementAndGet() }
            enabler.cancel()
        }
        queued.single().run()
        instrumentation.waitForIdleSync()
        assertEquals(0, requests.get())
        assertEquals(0, callbacks.get())
    }

    fun testRejectedAndPermissionDeniedEnableRemainFailures() {
        val completed = CountDownLatch(2)
        val results = mutableListOf<BluetoothEnableResult>()
        for (request in listOf<() -> BluetoothEnableResult>(
            { BluetoothEnableResult(false, "rejected") },
            { throw SecurityException("permission denied") },
        )) {
            instrumentation.runOnMainSync {
                BluetoothEnabler(Handler(Looper.getMainLooper()), Executor { it.run() }, request)
                    .start { results += it; completed.countDown() }
            }
            instrumentation.waitForIdleSync()
        }
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(false, results[0].accepted)
        assertNull(results[1].accepted)
        assertTrue(results[1].error.orEmpty().contains("SecurityException"))
    }

    fun testEnableCompletionRunsOnMainThread() {
        val done = CountDownLatch(1)
        var onMainThread = false
        var result: BluetoothEnableResult? = null
        instrumentation.runOnMainSync {
            BluetoothEnabler(Handler(Looper.getMainLooper()), Executor { action ->
                Thread(action, "test-bt-worker").apply { isDaemon = true; start() }
            }, request = { BluetoothEnableResult(true) }).start {
                onMainThread = Looper.myLooper() == Looper.getMainLooper()
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(onMainThread)
        assertEquals(true, result?.accepted)
    }
}
