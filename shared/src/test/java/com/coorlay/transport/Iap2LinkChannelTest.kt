package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Iap2LinkChannelTest {
    @Test fun navigationBurstWaitsForConsumerWithoutDroppingFrames() {
        val stream = PeerStream()
        val channel = Iap2LinkChannel.open(stream)
        try {
            assertTrue(channel.awaitReady(1_000))
            stream.offerBurst(150)
            awaitBackpressure(channel)
            assertEquals(64, channel.receiveQueueStats().pending)
            repeat(150) { index ->
                assertArrayEquals(byteArrayOf(index.toByte()), channel.recvControl(1_000))
            }
            assertNull(channel.recvControl(20))
            assertEquals(64, channel.receiveQueueStats().peak)
            assertTrue(channel.receiveQueueStats().pauses > 0)
        } finally { channel.close() }
        assertTrue(stream.closed)
    }

    @Test fun closeWakesPumpWaitingOnFullReceiveQueue() {
        val stream = PeerStream()
        val channel = Iap2LinkChannel.open(stream)
        try {
            assertTrue(channel.awaitReady(1_000))
            stream.offerBurst(100)
            awaitBackpressure(channel)
        } finally { channel.close() }
        assertTrue(stream.closed)
        assertNull(channel.recvControl(0))
    }

    @Test fun retransmittedLinkPacketIsAcknowledgedWithoutDuplicateDelivery() {
        val stream = PeerStream()
        val channel = Iap2LinkChannel.open(stream)
        try {
            assertTrue(channel.awaitReady(1_000))
            val packet = frame(1, 0x40, 10, byteArrayOf(42))
            stream.inbound.add(packet + packet + frame(2, 0x40, 10, byteArrayOf(43)))
            assertArrayEquals(byteArrayOf(42), channel.recvControl(1_000))
            assertArrayEquals(byteArrayOf(43), channel.recvControl(1_000))
            assertNull(channel.recvControl(30))
        } finally { channel.close() }
    }

    private fun awaitBackpressure(channel: Iap2LinkChannel) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (channel.receiveQueueStats().pauses == 0L && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        assertTrue("Pump must pause at the existing queue bound", channel.receiveQueueStats().pauses > 0)
    }

    private class PeerStream : BlockingDuplexByteStream {
        val inbound = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        init {
            val sync = Iap2LinkEngine.SynchronizationPayload(4, 16_384, 0, 0, 0, 0,
                listOf(Iap2LinkEngine.SessionDescriptor(10, 0, 2))).encode()
            inbound.add(Iap2LinkEngine.IAP2_MARKER + frame(0, 0x80, 0, sync) + frame(0, 0x40, 0, null))
        }
        fun offerBurst(count: Int) {
            val bytes = ByteArrayOutputStream()
            repeat(count) { bytes.write(frame(it + 1, 0x40, 10, byteArrayOf(it.toByte()))) }
            inbound.add(bytes.toByteArray())
        }
        override fun send(data: ByteArray) = Unit
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            if (closed) byteArrayOf() else inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { closed = true; inbound.offer(byteArrayOf()) }
    }

    companion object {
        private fun frame(sequence: Int, control: Int, session: Int, payload: ByteArray?): ByteArray {
            val length = 9 + if (payload == null) 0 else payload.size + 1
            val header = byteArrayOf(0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
                control.toByte(), sequence.toByte(), 0, session.toByte(), 0)
            header[8] = checksum(header)
            return if (payload == null) header else header + payload + checksum(payload)
        }
        private fun checksum(bytes: ByteArray): Byte = (-bytes.sumOf { it.toInt() and 0xff }).toByte()
    }
}
