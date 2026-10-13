package com.coorlay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmResamplerTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, s -> bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
    }

    private fun samples(bytes: ByteArray) = IntArray(bytes.size / 2) { i ->
        (bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)
    }

    @Test fun upsamples16kTo48kAcrossBlocksWithoutDrift() {
        val resampler = PcmResampler(16_000, 48_000, 1)
        var total = 0
        val first = samples(resampler.process(pcm(0, 300, -300), 6))
        assertEquals(listOf(0, 100, 200, 300, 100, -100), first.toList())
        total += first.size
        val second = samples(resampler.process(pcm(-300, 0), 4))
        assertEquals(listOf(-300, -300, -300, -300, -200, -100), second.toList())
        total += second.size
        repeat(1000) { total += resampler.process(ByteArray(320 * 2), 640).size / 2 }
        // 5 + 320000 input frames at 3x: only the frames not yet interpolated stay pending.
        assertEquals((5 + 320_000) * 3 - 3, total)
    }

    @Test fun keepsNegativeFullScaleValues() {
        val out = samples(PcmResampler(8_000, 16_000, 1).process(pcm(-32768, -32768, 32767), 6))
        assertEquals(listOf(-32768, -32768, -32768, -1), out.toList())
    }
}
