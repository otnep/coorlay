package com.coorlay.transport

import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NcmRxBufferTest {
    private val aligned = ByteArray(512 - 28) { it.toByte() } // NTB of exactly one 512-byte packet
    private val small = byteArrayOf(1, 2, 3, 4, 5)

    private fun ntb(frame: ByteArray, sequence: Int) = Ntb16Codec.build(frame, sequence).let {
        // Ntb16Codec.build appends the pad itself; tests add or omit it explicitly.
        if (it.size % 512 == 1) it.copyOf(it.size - 1) else it
    }

    private fun NcmRxBuffer.feed(vararg chunks: ByteArray) {
        for (chunk in chunks) {
            val offset = readOffset()
            chunk.copyInto(bytes, offset)
            received(chunk.size)
        }
    }

    private fun NcmRxBuffer.drain(): List<ByteArray> {
        val frames = ArrayDeque<ByteArray>()
        while (parse(frames)) Unit
        return frames.toList()
    }

    @Test
    fun anAlignedBlockIsParsedWithoutWaitingForItsPad() {
        val rx = NcmRxBuffer(512, 131072, 16384)
        rx.feed(ntb(aligned, 1))
        assertEquals(1, rx.drain().size)
        // The pad then arrives in its own completion, followed by the next block.
        rx.feed(byteArrayOf(0), ntb(small, 2))
        val next = rx.drain()
        assertEquals(1, next.size)
        assertArrayEquals(small, next.single())
    }

    @Test
    fun aZeroLengthPacketInsteadOfThePadLeavesTheNextHeaderIntact() {
        val rx = NcmRxBuffer(512, 131072, 16384)
        rx.feed(ntb(aligned, 1) + ntb(small, 2))
        val frames = rx.drain()
        assertEquals(2, frames.size)
        assertArrayEquals(small, frames[1])
    }

    @Test
    fun padAndBlocksSplitAcrossCompletions() {
        val rx = NcmRxBuffer(512, 131072, 16384)
        val wire = ntb(aligned, 1) + byteArrayOf(0) + ntb(small, 2) + ntb(small, 3)
        var frames = 0
        for (chunk in wire.toList().chunked(100)) {
            rx.feed(chunk.toByteArray())
            frames += rx.drain().size
        }
        assertEquals(3, frames)
    }

    @Test
    fun anUnalignedBlockDoesNotExpectAPad() {
        val rx = NcmRxBuffer(512, 131072, 16384)
        rx.feed(ntb(small, 1))
        assertEquals(1, rx.drain().size)
        rx.feed(ByteArray(12))
        try {
            rx.drain()
            fail("a zero byte after an unaligned block is not a pad but a broken header")
        } catch (expected: IphoneUsbException.Protocol) {
        }
    }

    @Test
    fun aNonZeroPadIsRejected() {
        val rx = NcmRxBuffer(512, 131072, 16384)
        rx.feed(ntb(aligned, 1), byteArrayOf(7))
        val frames = ArrayDeque<ByteArray>()
        assertTrue(rx.parse(frames))
        try {
            rx.parse(frames)
            fail("pad must be zero")
        } catch (expected: IphoneUsbException.Protocol) {
        }
        assertFalse(frames.isEmpty())
    }
}
