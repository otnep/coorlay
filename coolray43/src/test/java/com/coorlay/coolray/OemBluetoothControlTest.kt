package com.coorlay.coolray

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Synthetic peers use the transaction layout extracted from the supplied OEM APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OemBluetoothControlTest {
    @Test fun initializesSettingsBeforeOneEnableAndConfirmsActualOn() {
        val settings = RadioSettings(readyAfter = 2)
        val result = OemBluetoothControl.requestForTest(Manager(settings), timeoutMs = 2000)
        assertNull(result.error)
        assertEquals(true, result.ready)
        assertEquals(true, result.enabled)
        assertEquals(302, result.state)
        assertEquals(true, result.enableAccepted)
        assertEquals(false, result.sppAvailable)
        assertEquals(1, settings.enables.get())
        assertEquals(1, settings.registers.get())
        assertEquals(1, settings.unregisters.get())
        assertTrue(settings.readyReads.get() >= 2)
    }

    @Test fun wirelessCarPlayTurnsTheRadioOffAndConfirmsActualOff() {
        val settings = RadioSettings(initiallyOn = true)
        val result = OemBluetoothControl.requestForTest(Manager(settings), timeoutMs = 2000, enable = false)
        assertNull(result.error)
        assertEquals(false, result.enabled)
        assertEquals(300, result.state)
        assertEquals(1, settings.disables.get())
        assertEquals(0, settings.enables.get())
    }

    @Test fun aRefusedTurnOffDuringACallIsReportedAndNotRetriedInPlace() {
        val settings = RadioSettings(initiallyOn = true, acceptEnable = false)
        val result = OemBluetoothControl.requestForTest(Manager(settings), timeoutMs = 2000, enable = false)
        assertNotNull(result.error)
        assertEquals(false, result.enableAccepted)
        assertEquals(1, settings.disables.get())
    }

    @Test fun alreadyEnabledRadioDoesNotReceiveAnotherPowerRequest() {
        val settings = RadioSettings(initiallyOn = true)
        val result = OemBluetoothControl.requestForTest(Manager(settings))
        assertNull(result.error)
        assertEquals(true, result.enabled)
        assertEquals(0, settings.enables.get())
        assertNull(result.enableAccepted)
        assertEquals(1, settings.unregisters.get())
    }

    @Test fun acceptedRequestWithoutStateChangeDoesNotReportSuccess() {
        val settings = RadioSettings(changeState = false)
        val result = OemBluetoothControl.requestForTest(Manager(settings), timeoutMs = 550)
        assertNotNull(result.error)
        assertEquals(true, result.enableAccepted)
        assertEquals(false, result.enabled)
        assertEquals(300, result.state)
        assertEquals(1, settings.enables.get())
        awaitUnregistered(settings)
    }

    @Test fun rejectedEnableIsReportedAndCallbackIsRemoved() {
        val settings = RadioSettings(acceptEnable = false)
        val result = OemBluetoothControl.requestForTest(Manager(settings))
        assertNotNull(result.error)
        assertEquals(false, result.enableAccepted)
        assertEquals(false, result.enabled)
        assertEquals(1, settings.unregisters.get())
    }

    @Test fun cancellationAfterRegistrationNeverPowersTheRadio() {
        val cancelled = AtomicBoolean()
        val settings = RadioSettings(onRegister = { cancelled.set(true) })
        val result = OemBluetoothControl.requestForTest(Manager(settings), cancelled = cancelled::get)
        assertNotNull(result.error)
        assertEquals(0, settings.enables.get())
        awaitUnregistered(settings)
    }

    @Test fun unexpectedDescriptorNeverRunsSettingsTransactions() {
        val settings = RadioSettings()
        val manager = Manager(settings, descriptor = "unrelated.service")
        val result = OemBluetoothControl.requestForTest(manager)
        assertNotNull(result.error)
        assertEquals(0, settings.registers.get())
        assertEquals(0, settings.enables.get())
    }

    @Test fun stuckBinderIsBoundedAndRejectsAdditionalWorkersUntilItReturns() {
        val gate = CountDownLatch(1)
        val settings = RadioSettings(readyGate = gate)
        try {
            val begin = System.nanoTime()
            val result = OemBluetoothControl.requestForTest(Manager(settings), timeoutMs = 150)
            assertNotNull(result.error)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500)
            assertNotNull(OemBluetoothControl.requestForTest(Manager(RadioSettings())).error)
            assertEquals(0, settings.enables.get())
        } finally {
            gate.countDown()
            awaitUnregistered(settings)
        }
        // The cancelled call may finish its final guard release just after unregister.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var result: OemBluetoothControl.Result
        do {
            result = OemBluetoothControl.requestForTest(Manager(RadioSettings(initiallyOn = true)))
            if (result.error != null) Thread.sleep(10)
        } while (result.error != null && System.nanoTime() < deadline)
        assertNull(result.error)
        assertEquals(0, settings.enables.get())
    }

    @Test fun callbackAcknowledgesEventsWithoutApprovingPairingOrLoggingIdentities() {
        val log = mutableListOf<String>()
        val closed = AtomicBoolean()
        val callback = OemBluetoothControl.SettingsCallback(closed, log::add)
        fun send(code: Int, fill: (Parcel) -> Unit = {}) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(OemBluetoothControl.CALLBACK); fill(data)
                assertTrue(callback.transact(code, data, reply, 0)); reply.readException()
            } finally { data.recycle(); reply.recycle() }
        }
        send(2) { it.writeInt(300); it.writeInt(302) }
        send(11) { it.writeString("private-peer"); it.writeStrongBinder(null) }
        assertTrue(log.any { it.contains("300 -> 302") })
        assertFalse(log.joinToString().contains("private-peer"))
        val count = log.size
        closed.set(true)
        send(1)
        assertEquals(count, log.size)
    }

    private fun awaitUnregistered(settings: RadioSettings) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (settings.unregisters.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, settings.unregisters.get())
    }

    private class Manager(private val settings: IBinder, descriptor: String = OemBluetoothControl.MANAGER) : Binder() {
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in listOf(8, 9)) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(OemBluetoothControl.MANAGER)
            assertEquals(0, flags)
            reply!!.writeNoException()
            reply.writeStrongBinder(if (code == 8) settings else null)
            return true
        }
    }

    private class RadioSettings(
        private val initiallyOn: Boolean = false,
        private val readyAfter: Int = 1,
        private val acceptEnable: Boolean = true,
        private val changeState: Boolean = true,
        private val onRegister: () -> Unit = {},
        private val readyGate: CountDownLatch? = null,
    ) : Binder() {
        val registers = AtomicInteger(); val unregisters = AtomicInteger()
        val enables = AtomicInteger(); val disables = AtomicInteger(); val readyReads = AtomicInteger()
        private var on = initiallyOn
        private var callback: IBinder? = null
        init { attachInterface(null, OemBluetoothControl.SETTINGS) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(OemBluetoothControl.SETTINGS)
            assertEquals(0, flags)
            reply!!.writeNoException()
            when (code) {
                2 -> { callback = data.readStrongBinder(); assertNotNull(callback)
                    registers.incrementAndGet(); onRegister(); reply.writeInt(1) }
                3 -> { assertSame(callback, data.readStrongBinder()); unregisters.incrementAndGet(); reply.writeInt(1) }
                1 -> { assertEquals(1, registers.get()); readyGate?.await()
                    reply.writeInt(if (readyReads.incrementAndGet() >= readyAfter) 1 else 0) }
                5 -> { val wanted = data.readInt() == 1; assertTrue(readyReads.get() >= readyAfter)
                    if (wanted) enables.incrementAndGet() else disables.incrementAndGet()
                    if (acceptEnable && changeState) on = wanted
                    reply.writeInt(if (acceptEnable) 1 else 0) }
                18 -> reply.writeInt(if (on) 1 else 0)
                19 -> reply.writeInt(if (on) 302 else 300)
                else -> error("Unexpected OEM transaction $code")
            }
            return true
        }
    }
}
