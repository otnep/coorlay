package com.coorlay.coolray

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AnwSocketStreamTest {
    @Test fun callbackBoundariesAndForeignSocketsDoNotChangeTheByteStream() {
        val stream = AnwSocketStream(3, { data, _ -> data.size }, { true }, {})
        stream.receive(9, byteArrayOf(99), 1)
        stream.receive(3, byteArrayOf(1, 2, 3, 77), 3)
        stream.receive(3, byteArrayOf(4), 1)
        assertArrayEquals(byteArrayOf(1, 2), stream.recv(2, 0))
        assertArrayEquals(byteArrayOf(3), stream.recv(2, 0))
        assertArrayEquals(byteArrayOf(4), stream.recv(2, 0))
        assertNull(stream.recv(2, 0))
        stream.close(); assertArrayEquals(byteArrayOf(), stream.recv(2, 10))
    }
    @Test fun partialWritesSendEveryByteOnce() {
        val written = mutableListOf<Byte>()
        val stream = AnwSocketStream(3, { data, _ -> written += data.take(2); minOf(2, data.size) }, { true }, {})
        val input = ByteArray(20_000) { it.toByte() }
        stream.send(input)
        assertArrayEquals(input, written.toByteArray())
    }
    @Test fun stalledOrInvalidWritesAndMalformedCallbacksFail() {
        val stream = AnwSocketStream(3, { _, _ -> 0 }, { true }, {})
        try { stream.send(byteArrayOf(1)); fail() } catch (_: IOException) { }
        stream.receive(3, byteArrayOf(1), 2)
        try { stream.recv(2, 0); fail() } catch (_: IOException) { }
    }
    @Test fun overflowNeverSilentlyDropsIap2Data() {
        val stream = AnwSocketStream(3, { data, _ -> data.size }, { true }, {})
        stream.receive(3, ByteArray(65_536), 65_536)
        stream.receive(3, byteArrayOf(1), 1)
        try { stream.recv(2, 0); fail() } catch (_: IOException) { }
    }
    @Test fun closeWakesReaderAndDisconnectsOnlyOnce() {
        val ended = CountDownLatch(1)
        var disconnects = 0
        val stream = AnwSocketStream(3, { data, _ -> data.size }, { true }, { disconnects++ })
        val thread = Thread { assertArrayEquals(byteArrayOf(), stream.recv(2, 10_000)); ended.countDown() }
        thread.start(); stream.close(); stream.close()
        assertTrue(ended.await(1, TimeUnit.SECONDS)); assertEquals(1, disconnects)
    }
}
