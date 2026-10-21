package com.coorlay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmGainTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, v -> bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
    }
    private fun samples(bytes: ByteArray) = (bytes.indices step 2).map {
        (bytes[it].toInt() and 0xff) or (bytes[it + 1].toInt() shl 8)
    }

    @Test fun quietSamplesAreDoubled() {
        val bytes = pcm(1000, -1000, 12000, -12288, 0)
        applyPcmGain(bytes, 0, bytes.size, 2f)
        assertEquals(listOf(2000, -2000, 24000, -24576, 0), samples(bytes))
    }

    @Test fun loudSamplesStayInRangeAndKeepOrder() {
        val bytes = pcm(16000, 24000, 32767, -32768)
        applyPcmGain(bytes, 0, bytes.size, 2f)
        val out = samples(bytes)
        assertTrue(out[0] in 24577..32767 && out[1] > out[0] && out[2] == 32767 && out[3] == -32767)
    }

    @Test fun onlyTheGivenRangeChanges() {
        val bytes = pcm(100, 100, 100)
        applyPcmGain(bytes, 2, 2, 2f)
        assertEquals(listOf(100, 200, 100), samples(bytes))
    }
}
