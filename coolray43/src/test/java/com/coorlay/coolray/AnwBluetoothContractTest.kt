package com.coorlay.coolray

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AnwBluetoothContractTest {
    @Test fun extractedBinderContractDeliversEarlyDataAndDisposesOnlyOwnedSocket() = worker {
        val radio = Radio()
        val uuid = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val client = AnwBluetoothTransport.forTest(radio)
        try {
            assertEquals(12, client.androidState())
            assertEquals("01:23:45:67:89:AB", client.localAddress())
            assertEquals("iPhone", client.pairedDevices().single().name)
            val stream = client.connect("11:23:45:67:89:AB", uuid, 2000)
            assertArrayEquals(AnwBluetoothTransport.guidBytes(uuid), radio.uuid)
            assertArrayEquals(byteArrayOf(8, 9), stream.recv(10, 0))
            stream.send(byteArrayOf(1, 2, 3))
            assertEquals(listOf<Byte>(1, 2, 3), radio.written)
            stream.close()
        } finally { client.close(); AnwBluetoothTransport.awaitIdle() }
        assertEquals(listOf(4), radio.disconnected)
        assertEquals(1, radio.unregistered)
        assertFalse(radio.codes.any { it == 2 || it == 224 || it == 63 })
        assertEquals(listOf(0), radio.sdkLog) // quiet by default: switches off what a detailed start turned on
        assertTrue(radio.codes.indexOf(126) in 0 until radio.codes.indexOf(225))
    }
    @Test fun uuidLayoutsMatchTheAnwGuidOrder() {
        val uuid = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("00000000decafadedecadeafdecacafe", hex(AnwBluetoothTransport.bigEndianBytes(uuid)))
        assertEquals("00000000cadedefadecadeafdecacafe", hex(AnwBluetoothTransport.guidBytes(uuid)))
    }
    @Test fun rejectedConnectionWithAllocatedHandleStillCleansUp() = worker {
        val radio = Radio(reject = true)
        val client = AnwBluetoothTransport.forTest(radio)
        try {
            try { client.connect("11:23:45:67:89:AB", UUID.randomUUID(), 1000); fail() } catch (_: Exception) { }
        } finally { client.close(); AnwBluetoothTransport.awaitIdle() }
        assertEquals(listOf(4), radio.disconnected)
        assertEquals(1, radio.unregistered)
    }
    @Test fun settingsProbeWaitsForAnInFlightWriteWithoutEndingTheSocket() = worker {
        val radio = Radio(writeDelayMs = 120)
        val client = AnwBluetoothTransport.forTest(radio)
        try {
            val stream = client.connect("11:23:45:67:89:AB", UUID.randomUUID(), 1000)
            val send = FutureTask { stream.send(byteArrayOf(1)) }
            Thread(send, "anw-active-write").start()
            assertTrue(radio.writeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(12, client.androidState())
            send.get(1, TimeUnit.SECONDS)
            assertTrue(radio.disconnected.isEmpty())
            assertArrayEquals(byteArrayOf(8, 9), stream.recv(10, 0))
        } finally { client.close(); AnwBluetoothTransport.awaitIdle() }
        assertEquals(listOf(4), radio.disconnected)
    }
    private fun worker(action: () -> Unit) {
        val future = FutureTask { action() }
        Thread(future, "anw-contract-test").start()
        future.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
    private class Radio(val reject: Boolean = false, val writeDelayMs: Long = 0) : Binder() {
        val writeEntered = CountDownLatch(1)
        val codes = mutableListOf<Int>()
        val disconnected = mutableListOf<Int>()
        val sdkLog = mutableListOf<Int>()
        val written = mutableListOf<Byte>()
        var uuid = byteArrayOf()
        var unregistered = 0
        var ready = false
        var callback: IBinder? = null
        init { attachInterface(null, AnwBluetoothTransport.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply!!.writeString(AnwBluetoothTransport.DESCRIPTOR); return true }
            data.enforceInterface(AnwBluetoothTransport.DESCRIPTOR)
            codes += code; reply!!.writeNoException()
            when (code) {
                3 -> reply.writeInt(1)
                7 -> reply.writeString("01:23:45:67:89:AB")
                16 -> {
                    assertEquals(1, data.readInt()); repeat(3) { assertEquals(64, data.readInt()) }
                    reply.writeInt(1); reply.writeIntArray(intArrayOf(1))
                    reply.writeStringArray(arrayOf("iPhone")); reply.writeStringArray(arrayOf("11:23:45:67:89:AB"))
                    reply.writeIntArray(intArrayOf(0))
                }
                233 -> reply.writeInt(if (ready) 1 else 0)
                222 -> { ready = true; reply.writeInt(1) }
                231 -> callback = data.readStrongBinder()
                126 -> sdkLog += data.readInt()
                225 -> {
                    assertEquals(1, data.readInt()); assertEquals("11:23:45:67:89:AB", data.readString())
                    uuid = data.createByteArray()!!; assertEquals(0, data.readInt())
                    assertEquals(0L, data.readLong()); assertEquals(0L, data.readLong()); assertEquals(1, data.readInt())
                    val request = Parcel.obtain(); val answer = Parcel.obtain()
                    try {
                        request.writeInterfaceToken(AnwBluetoothTransport.DATA_CALLBACK)
                        request.writeInt(4); request.writeByteArray(byteArrayOf(8, 9)); request.writeInt(2)
                        callback!!.transact(1, request, answer, 0); answer.readException()
                    } finally { request.recycle(); answer.recycle() }
                    reply.writeInt(if (reject) -1 else 1); reply.writeIntArray(intArrayOf(4))
                }
                23 -> { assertEquals(17, data.readInt()); assertEquals(4, data.readInt()); reply.writeInt(1) }
                227 -> {
                    writeEntered.countDown()
                    if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
                    assertEquals(4, data.readInt()); val bytes = data.createByteArray()!!
                    assertEquals(bytes.size, data.readInt()); assertEquals(1, data.readInt())
                    val count = minOf(2, bytes.size); written += bytes.take(count)
                    reply.writeInt(1); reply.writeIntArray(intArrayOf(count))
                }
                226 -> { disconnected += data.readInt(); reply.writeInt(1) }
                232 -> { assertEquals(callback, data.readStrongBinder()); unregistered++ }
                else -> error("Unexpected transaction $code")
            }
            return true
        }
    }
}
