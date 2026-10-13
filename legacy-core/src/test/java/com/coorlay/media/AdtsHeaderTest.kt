package com.coorlay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AdtsHeaderTest {
    @Test fun matchesTheAllocatingAdtsFrame() {
        for ((rate, channels, size) in listOf(Triple(44_100, 2, 371), Triple(48_000, 2, 1), Triple(24_000, 1, 2040))) {
            val header = ByteArray(7)
            writeAdtsHeader(header, size, rate, channels)
            assertArrayEquals(MediaCodecSupport.adtsFrame(ByteArray(size), rate, channels).copyOf(7), header)
        }
    }
}
