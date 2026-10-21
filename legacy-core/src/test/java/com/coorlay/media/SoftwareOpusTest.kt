package com.coorlay.media

import com.coorlay.airplay.AudioCodecKind
import com.coorlay.airplay.AudioStreamCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SoftwareOpusTest {
    private fun tone(frameBytes: Int, rate: Int, frame: Int): ByteArray {
        val samples = frameBytes / 2
        return ByteArray(frameBytes).also { pcm ->
            for (i in 0 until samples) {
                val t = (frame * samples + i).toDouble() / rate
                val value = (sin(2 * PI * 440 * t) * 8000).toInt()
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
        }
    }

    @Test
    fun encodeToOutputGivesTheSamePacketsAsEncode() {
        val copying = SoftwareOpusEncoder(16_000, 32_000)
        val reusing = SoftwareOpusEncoder(16_000, 32_000)
        repeat(20) { frame ->
            val pcm = tone(copying.frameBytes, 16_000, frame)
            val expected = copying.encode(pcm)
            val length = reusing.encodeToOutput(pcm)
            assertEquals(expected.toList(), reusing.packet.copyOf(length).toList())
        }
    }

    @Test
    fun the16kHzCaptureEncodesTo20msPacketsThatDecodeAt48kHz() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        assertEquals(320, encoder.frameSamples)
        val decoder = SoftwareOpusDecoder(48_000, 1)
        var loudFrames = 0
        repeat(50) { frame ->
            val packet = encoder.encode(tone(encoder.frameBytes, 16_000, frame))
            assertTrue(packet.size in 1..400)
            val bytes = decoder.decode(packet, 0, packet.size)
            assertEquals(960 * 2, bytes)
            var peak = 0
            for (i in 0 until bytes / 2) {
                val sample = (decoder.output[i * 2].toInt() and 0xff) or (decoder.output[i * 2 + 1].toInt() shl 8)
                peak = maxOf(peak, abs(sample.toShort().toInt()))
            }
            if (peak > 3000) loudFrames++
        }
        // After the codec's start-up the 440 Hz tone comes back at about its level.
        assertTrue("loud frames $loudFrames", loudFrames > 40)
    }

    @Test
    fun opusStreamsDecodeAtTheirOwnRateWhileTheMicrophoneKeeps48kHz() {
        val navigation = AudioStreamCodec.fromFormatBits(0x10000000L, 101, "default")
        assertEquals(AudioCodecKind.OPUS, navigation.codec)
        assertEquals(48_000, navigation.sampleRate)
        assertEquals(16_000, navigation.decodeRate)
        assertEquals(16_000, navigation.clockRate)
        val siri = AudioStreamCodec.fromFormatBits(0x20000000L, 100, "speechRecognition")
        // Siri is reported on its 24 kHz clock but decoded at 48 kHz (Concentus garbles SWB below 48 kHz).
        assertEquals(48_000, siri.decodeRate)
        assertEquals(24_000, siri.clockRate)
        assertEquals(48_000, AudioStreamCodec.fromFormatBits(0x40000000L, 100, "alert").decodeRate)
        assertEquals(48_000, AudioStreamCodec.fromFormatBits(0x40000000L, 100, "alert").clockRate)
        assertEquals(44_100, AudioStreamCodec.fromFormatBits(0x400000L, 102).decodeRate)
    }

    @Test
    fun aNavigationPromptDecodesAt16kHzAndALostPacketIsConcealed() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        val decoder = SoftwareOpusDecoder(16_000, 1)
        repeat(20) { frame ->
            val packet = encoder.encode(tone(encoder.frameBytes, 16_000, frame))
            assertEquals(320 * 2, decoder.decode(packet, 0, packet.size))
        }
        assertEquals(320 * 2, decoder.conceal())
        var peak = 0
        for (i in 0 until 320) {
            val sample = (decoder.output[i * 2].toInt() and 0xff) or (decoder.output[i * 2 + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
        }
        // Concealment continues the tone instead of 20 ms of silence.
        assertTrue("concealed peak $peak", peak > 1000)
    }

    @Test
    fun decodesFromAnOffsetInsideAnRtpPacket() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        val packet = encoder.encode(tone(encoder.frameBytes, 16_000, 0))
        val rtp = ByteArray(12) + packet
        assertEquals(1920, SoftwareOpusDecoder(48_000, 1).decode(rtp, 12, packet.size))
    }

    @Test
    fun aFailedConcentusCheckResetsTheCodecAndReachesTheCallerAsAnException() {
        var resets = 0
        val error = try {
            checked({ resets++ }) { throw AssertionError("corr_QC[0] >= 0") }
            null
        } catch (caught: Exception) {
            caught
        }
        assertTrue(error is IllegalStateException && error.cause is AssertionError)
        assertEquals(1, resets)
        assertEquals(7, checked({ resets++ }) { 7 })
        assertEquals(1, resets)
    }
}
