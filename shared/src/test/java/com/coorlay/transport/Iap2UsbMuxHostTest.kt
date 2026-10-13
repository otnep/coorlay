package com.coorlay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Iap2UsbMuxHostTest {
    @Test fun ordinaryVersionReplyCanConnectToLockdown() {
        connect(listOf(versionReply()))
    }

    @Test fun capturedIosVersionReplyWithFourExtraBytesCanConnectToLockdown() {
        // Issue #100: length=20, but the USB transfer carries 24 bytes.
        connect(listOf(versionReply() + byteArrayOf(0x49, 0x28, 0x73, 0)))
    }

    @Test fun fragmentedVersionReplyStillDiscardsItsBufferedTrailer() {
        val captured = versionReply() + byteArrayOf(0x6b, 0x28, 0x73, 0)
        connect(listOf(captured.copyOfRange(0, 8), captured.copyOfRange(8, captured.size)))
    }

    @Test fun staleTcpBeforeVersionReplyDoesNotBreakHandshake() {
        val stale = muxFrame(6, ByteArray(20))
        connect(listOf(stale + versionReply() + byteArrayOf(0x49, 0x28, 0x73, 0)))
    }

    private fun connect(replies: List<ByteArray>) {
        val pipe = ScriptedPipe(replies)
        val host = Iap2UsbMuxHost.open(pipe, readTimeoutMillis = 50)
        try {
            host.connect(timeoutMillis = 1_000).close()
            assertEquals(20, pipe.writes[0].size)
            assertEquals(2, u32(pipe.writes[1], 0))
            assertEquals(17, pipe.writes[1].size)
            assertTrue(pipe.writes.any { u32(it, 0) == 6 })
        } finally {
            host.close()
        }
        assertTrue(pipe.closed)
    }

    private class ScriptedPipe(private val replies: List<ByteArray>) : UsbMuxIo {
        val writes = mutableListOf<ByteArray>()
        val reads = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false

        override fun write(data: ByteArray, timeoutMillis: Int) {
            writes.add(data.copyOf())
            when (u32(data, 0)) {
                0 -> replies.forEach(reads::add)
                6 -> if ((data[29].toInt() and 0xff) == 2) {
                    val tcp = ByteArray(20)
                    // The phone sends SYN-ACK from lockdownd back to the host's source port.
                    data.copyInto(tcp, 0, 18, 20)
                    data.copyInto(tcp, 2, 16, 18)
                    put32(tcp, 4, 41)
                    put32(tcp, 8, 1)
                    tcp[12] = 0x50
                    tcp[13] = 0x12
                    val response = muxFrame(6, tcp)
                    // Normal TCP framing must keep fragments buffered after SETUP.
                    reads.add(response.copyOfRange(0, 12))
                    reads.add(response.copyOfRange(12, response.size))
                }
            }
        }

        override fun read(timeoutMillis: Long): ByteArray? =
            reads.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun close() { closed = true }
    }

    companion object {
        private fun versionReply(): ByteArray = muxFrame(0, ByteArray(4)).also { put32(it, 8, 2) }

        private fun muxFrame(protocol: Int, payload: ByteArray): ByteArray =
            ByteArray(16 + payload.size).also {
                put32(it, 0, protocol)
                put32(it, 4, it.size)
                payload.copyInto(it, 16)
            }

        private fun put32(bytes: ByteArray, offset: Int, value: Int) {
            repeat(4) { bytes[offset + it] = (value ushr (24 - 8 * it)).toByte() }
        }

        private fun u32(bytes: ByteArray, offset: Int): Int =
            (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[offset + i].toInt() and 0xff) }
    }
}
