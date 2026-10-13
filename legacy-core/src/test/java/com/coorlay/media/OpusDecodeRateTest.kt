package com.coorlay.media

import com.coorlay.airplay.AudioStreamCodec
import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Concentus decodes super-wideband/fullband SILK and hybrid packets to silence or noise at any output rate
 * but 48 kHz (Siri over Wi-Fi was a squeak, 2026-10-13); wideband packets decode at every rate. The renderer
 * relies on [AudioStreamCodec.opusDecodeRate] picking a rate that works.
 */
class OpusDecodeRateTest {
    private class Result(val frequency: Double, val rms: Double)

    private fun roundTrip(encodeRate: Int, decodeRate: Int, frequency: Double): Result {
        val encoder = OpusEncoder(encodeRate, 1, OpusApplication.OPUS_APPLICATION_AUDIO).apply { bitrate = 20_000 }
        val decoder = SoftwareOpusDecoder(decodeRate, 1)
        val frame = encodeRate / 50
        val packet = ByteArray(1_275)
        var crossings = 0; var count = 0; var sumSquares = 0.0; var previous = 0
        for (index in 0 until 100) {
            val pcm = ShortArray(frame) { i ->
                (sin(2 * PI * frequency * (index * frame + i) / encodeRate) * 8000).toInt().toShort()
            }
            val length = encoder.encode(pcm, 0, frame, packet, 0, packet.size)
            val bytes = decoder.decode(packet, 0, length)
            if (index < 20) continue
            for (i in 0 until bytes / 2) {
                val sample = ((decoder.output[i * 2].toInt() and 0xff) or (decoder.output[i * 2 + 1].toInt() shl 8)).toShort().toInt()
                if ((previous < 0) != (sample < 0)) crossings++
                previous = sample; sumSquares += sample.toDouble() * sample; count++
            }
        }
        return Result(crossings / 2.0 / (count.toDouble() / decodeRate), sqrt(sumSquares / count))
    }

    @Test
    fun everyOpusClockDecodesAtTheChosenRate() {
        for (clock in listOf(16_000, 24_000, 48_000)) for (frequency in listOf(300.0, 1000.0, 3000.0)) {
            val result = roundTrip(clock, AudioStreamCodec.opusDecodeRate(clock), frequency)
            assertTrue("clock=$clock f=$frequency rms=${result.rms}", result.rms > 4500)
            assertTrue("clock=$clock f=$frequency est=${result.frequency}", abs(result.frequency - frequency) < frequency * 0.05)
        }
    }

    @Test
    fun concentusGarbles24kHzSpeechDecodedAt24kHz() {
        // The bug the 48 kHz choice avoids; if a newer Concentus fixes it, 24 kHz decoding can come back.
        assertTrue(roundTrip(24_000, 24_000, 300.0).rms < 100)
        assertEquals(48_000, AudioStreamCodec.opusDecodeRate(24_000))
        assertEquals(16_000, AudioStreamCodec.opusDecodeRate(16_000))
    }
}
