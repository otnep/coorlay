package com.coorlay.coolray

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.test.AndroidTestCase
import java.io.IOException
import java.util.UUID

/** Real Dalvik/API18 parcel, callback, timeout and late-handle cleanup; synthetic radio only. */
class AnwPlatformTest : AndroidTestCase() {
    fun testSocketCallbackAndPartialWritesAcrossApi18Binder() {
        val radio = Radio()
        val client = AnwBluetoothTransport.forTest(radio)
        try {
            val stream = client.connect("11:23:45:67:89:AB", UUID.fromString("00000000-deca-fade-deca-deafdecacafe"), 1000)
            assertEquals(listOf<Byte>(8, 9), stream.recv(16, 0)!!.toList())
            stream.send(byteArrayOf(1, 2, 3))
            assertEquals(listOf<Byte>(1, 2, 3), radio.written)
            assertNull(stream.recv(16, 0))
            stream.close(); assertEquals(0, stream.recv(16, 1)!!.size)
        } finally { client.close(); AnwBluetoothTransport.awaitIdle() }
        assertEquals(listOf(4), radio.disconnected)
        assertEquals(1, radio.unregistered)
    }

    fun testTimedOutConnectDisposesItsLateHandleWithoutRadioDeinit() {
        val radio = Radio(delayMs = 250)
        val client = AnwBluetoothTransport.forTest(radio)
        try {
            try { client.connect("11:23:45:67:89:AB", UUID.randomUUID(), 80); fail("Timeout expected") }
            catch (expected: IOException) { assertTrue(expected.message!!.contains("timeout")) }
        } finally { client.close(); AnwBluetoothTransport.awaitIdle() }
        assertEquals(listOf(4), radio.disconnected)
        assertEquals(1, radio.unregistered)
    }

    private class Radio(val delayMs: Long = 0) : Binder() {
        val written = mutableListOf<Byte>()
        val disconnected = mutableListOf<Int>()
        var unregistered = 0
        var callback: IBinder? = null
        init { attachInterface(null, AnwBluetoothTransport.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply!!.writeString(AnwBluetoothTransport.DESCRIPTOR); return true }
            data.enforceInterface(AnwBluetoothTransport.DESCRIPTOR); reply!!.writeNoException()
            when (code) {
                3 -> reply.writeInt(1)
                233 -> reply.writeInt(1)
                231 -> callback = data.readStrongBinder()
                225 -> {
                    check(data.readInt() == 1 && data.readString() == "11:23:45:67:89:AB")
                    check(data.createByteArray()!!.size == 16 && data.readInt() == 0)
                    check(data.readLong() == 0L && data.readLong() == 0L && data.readInt() == 1)
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val request = Parcel.obtain(); val response = Parcel.obtain()
                    try {
                        request.writeInterfaceToken(AnwBluetoothTransport.DATA_CALLBACK)
                        request.writeInt(4); request.writeByteArray(byteArrayOf(8, 9)); request.writeInt(2)
                        callback!!.transact(1, request, response, 0); response.readException()
                    } finally { request.recycle(); response.recycle() }
                    reply.writeInt(1); reply.writeIntArray(intArrayOf(4))
                }
                23 -> { check(data.readInt() == 17 && data.readInt() == 4); reply.writeInt(1) }
                227 -> {
                    check(data.readInt() == 4); val bytes = data.createByteArray()!!
                    check(data.readInt() == bytes.size && data.readInt() == 1)
                    val size = minOf(bytes.size, 2); written += bytes.take(size)
                    reply.writeInt(1); reply.writeIntArray(intArrayOf(size))
                }
                226 -> { disconnected += data.readInt(); reply.writeInt(1) }
                232 -> { check(data.readStrongBinder() == callback); unregistered++ }
                else -> error("Unexpected radio write $code")
            }
            return true
        }
    }
}
