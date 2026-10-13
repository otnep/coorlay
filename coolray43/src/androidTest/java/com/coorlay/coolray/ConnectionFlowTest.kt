package com.coorlay.coolray

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises failure cases that occurred on the factory head unit, on real API-18 Handler/Dalvik. */
class ConnectionFlowTest : InstrumentationTestCase() {
    fun testUnresponsiveBluetoothServiceTimesOutAndIgnoresLateReply() {
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = AtomicInteger()
        var result: BluetoothSnapshot? = null
        instrumentation.runOnMainSync {
            val probe = BluetoothProbe(Handler(Looper.getMainLooper()), Executor { action ->
                Thread(action, "test-blocked-bluetooth").apply { isDaemon = true; start() }
            }, read = {
                release.await(2, TimeUnit.SECONDS)
                BluetoothSnapshot(BluetoothAdapter.STATE_ON)
            }, timeoutMillis = 100)
            probe.start { result = it; calls.incrementAndGet(); done.countDown() }
        }
        try {
            assertTrue("UI deadline did not fire", done.await(2, TimeUnit.SECONDS))
            assertNotNull(result?.error)
            assertNull(result?.state)
        } finally { release.countDown() }
        Thread.sleep(150)
        instrumentation.waitForIdleSync()
        assertEquals("Late binder reply must not launch a connection", 1, calls.get())
    }

    fun testStopDiscardsAnInFlightBluetoothReply() {
        val queued = mutableListOf<Runnable>()
        val calls = AtomicInteger()
        instrumentation.runOnMainSync {
            val probe = BluetoothProbe(Handler(Looper.getMainLooper()), Executor { queued += it },
                read = { BluetoothSnapshot(BluetoothAdapter.STATE_ON) })
            probe.start { calls.incrementAndGet() }
            probe.cancel()
        }
        queued.single().run()
        instrumentation.waitForIdleSync()
        assertEquals(0, calls.get())
    }

    fun testOffAndTurningOnRemainDistinctFromUsableBluetooth() {
        val completed = CountDownLatch(2)
        val states = mutableListOf<Int?>()
        for (state in listOf(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_ON)) {
            instrumentation.runOnMainSync {
                val probe = BluetoothProbe(Handler(Looper.getMainLooper()), Executor { it.run() },
                    read = { BluetoothSnapshot(state) })
                probe.start { states += it.state; completed.countDown() }
            }
            instrumentation.waitForIdleSync()
        }
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(10, 11), states)
    }

    fun testExportContainsDurableHistoryAndDiagnosticsButRedactsSecrets() {
        val context = instrumentation.targetContext
        val journal = File(context.filesDir, "connection-journal.txt")
        val original = if (journal.exists()) journal.readBytes() else null
        val folder = File(context.cacheDir, "export-test").apply { mkdirs() }
        var report: File? = null
        try {
            journal.writeText("test USB discovery\npassword=must-not-export\n" + "a".repeat(64) + "\n")
            report = DiagnosticLogExporter.save(context, folder, BluetoothSnapshot(BluetoothAdapter.STATE_OFF))
            val text = report.readText()
            assertTrue(text.contains("test USB discovery"))
            assertTrue(text.contains("USB Android:"))
            assertTrue(text.contains("OFF (10)"))
            assertTrue(text.contains("/proc/mounts:"))
            assertFalse(text.contains("must-not-export"))
            assertFalse(text.contains("a".repeat(64)))
        } finally {
            report?.delete()
            if (original != null) journal.writeBytes(original) else journal.delete()
            folder.delete()
        }
    }

    fun testUnwritableDestinationReportsFailureWithoutLeavingPartialFile() {
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "not-a-directory.txt")
        folder.writeText("existing file")
        try {
            try {
                DiagnosticLogExporter.save(context, folder, null)
                fail("Export must reject a non-directory destination")
            } catch (_: IllegalArgumentException) { }
            assertEquals("existing file", folder.readText())
        } finally { folder.delete() }
    }
}
