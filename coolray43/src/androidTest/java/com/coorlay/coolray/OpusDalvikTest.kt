package com.coorlay.coolray

import android.test.AndroidTestCase
import android.util.Log
import com.coorlay.media.SoftwareOpusDecoder
import com.coorlay.media.SoftwareOpusEncoder

/**
 * libopus must load in the APK and keep up with 20 ms frames on API 18; Concentus, its fallback, must still
 * load on the Dalvik verifier. Both must read each other's packets.
 */
class OpusDalvikTest : AndroidTestCase() {
    fun testLibopusIsTheDefault() {
        assertEquals("libopus", SoftwareOpusDecoder(48_000, 2).backend)
        assertEquals("libopus", SoftwareOpusEncoder(16_000, 32_000).backend)
        assertEquals("Concentus", SoftwareOpusDecoder(48_000, 2, preferNative = false).backend)
    }

    /** Costs of the encoder complexity and decoder rate on Dalvik for both codecs, for choosing the defaults. */
    fun testCostOfOpusSettings() {
        val pcm = tone(320, 16_000, 0)
        for (native in listOf(true, false)) {
            val name = if (native) "libopus" else "Concentus"
            for (complexity in listOf(0, 1, 3, 5, 10)) {
                val encoder = SoftwareOpusEncoder(16_000, 32_000, complexity, preferNative = native)
                repeat(50) { encoder.encode(pcm) }
                val started = System.nanoTime()
                repeat(250) { encoder.encode(pcm) }
                Log.i(TAG, "$name encode complexity=$complexity avg=%.2fms".format((System.nanoTime() - started) / 1e6 / 250))
                encoder.close()
            }
            val packet = SoftwareOpusEncoder(16_000, 32_000, preferNative = native).encode(pcm)
            for (rate in listOf(48_000, 24_000, 16_000)) {
                val decoder = SoftwareOpusDecoder(rate, 1, preferNative = native)
                repeat(50) { decoder.decode(packet, 0, packet.size) }
                val started = System.nanoTime()
                repeat(250) { decoder.decode(packet, 0, packet.size) }
                Log.i(TAG, "$name decode rate=$rate avg=%.2fms".format((System.nanoTime() - started) / 1e6 / 250))
                decoder.close()
            }
        }
    }

    fun testSoftwareOpusRunsInRealTimeOnDalvik() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        val decoder = SoftwareOpusDecoder(48_000, 1)
        val frames = 250 // 5 s
        var encodeNs = 0L
        var decodeNs = 0L
        repeat(frames) { frame ->
            val pcm = tone(encoder.frameSamples, 16_000, frame * encoder.frameSamples)
            val started = System.nanoTime()
            val packet = encoder.encode(pcm)
            val encoded = System.nanoTime()
            assertEquals(1920, decoder.decode(packet, 0, packet.size))
            decodeNs += System.nanoTime() - encoded
            encodeNs += encoded - started
        }
        val line = "Opus on Dalvik (${encoder.backend}): encode avg=%.2fms decode avg=%.2fms per 20 ms frame".format(
            encodeNs / 1e6 / frames, decodeNs / 1e6 / frames)
        Log.i(TAG, line)
        // Both together must stay well inside the 20 ms a frame lasts.
        assertTrue(line, (encodeNs + decodeNs) / frames < 10_000_000L)
    }

    /** The iPhone's packets come from Apple's encoder: each codec must read the other's, at the iPhone's rates. */
    fun testCodecsReadEachOthersPackets() {
        for (native in listOf(true, false)) {
            val encoder = SoftwareOpusEncoder(16_000, 32_000, preferNative = native)
            val decoder = SoftwareOpusDecoder(16_000, 1, preferNative = !native)
            var level = 0.0
            repeat(50) { frame ->
                val packet = encoder.encode(tone(320, 16_000, frame * 320))
                assertEquals(640, decoder.decode(packet, 0, packet.size))
                if (frame >= 10) level = maxOf(level, rms(decoder.output, 640))
            }
            assertTrue("${encoder.backend} -> ${decoder.backend} rms=$level", level > 1_000)
        }
    }

    /**
     * Concentus turned super-wideband and fullband packets into silence at any rate but 48 kHz (1.0.1);
     * libopus decodes them at 16 kHz too.
     */
    fun testLibopusDecodesFullbandAt16kHz() {
        val encoder = SoftwareOpusEncoder(48_000, 64_000)
        val decoder = SoftwareOpusDecoder(16_000, 1)
        var level = 0.0
        repeat(50) { frame ->
            val packet = encoder.encode(tone(960, 48_000, frame * 960))
            assertEquals(640, decoder.decode(packet, 0, packet.size))
            if (frame >= 10) level = maxOf(level, rms(decoder.output, 640))
        }
        assertTrue("rms=$level", level > 1_000)
        assertEquals(640, decoder.conceal())
    }

    fun testClosedCodecsFailWithAnException() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        val decoder = SoftwareOpusDecoder(48_000, 1)
        val packet = encoder.encode(tone(320, 16_000, 0))
        encoder.close(); encoder.close()
        decoder.close(); decoder.close()
        try { encoder.encode(tone(320, 16_000, 0)); fail("encoded after close") } catch (_: IllegalStateException) { }
        try { decoder.decode(packet, 0, packet.size); fail("decoded after close") } catch (_: IllegalStateException) { }
        try { decoder.conceal(); fail("concealed after close") } catch (_: IllegalStateException) { }
    }

    fun testOutOfRangePacketIsRejectedBeforeLibopus() {
        val decoder = SoftwareOpusDecoder(48_000, 1)
        try { decoder.decode(ByteArray(10), 8, 5); fail("read past the packet") } catch (_: IllegalArgumentException) { }
        try { decoder.decode(ByteArray(3), 0, 3) } catch (_: IllegalStateException) { } // not Opus: an error, no crash
        decoder.close()
    }

    private fun tone(samples: Int, rate: Int, start: Int): ByteArray {
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (Math.sin(2 * Math.PI * 300 * (start + i) / rate.toDouble()) * 6000).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        return pcm
    }

    private fun rms(pcm: ByteArray, bytes: Int): Double {
        var squares = 0.0
        for (i in 0 until bytes / 2) {
            val sample = ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toDouble()
            squares += sample * sample
        }
        return Math.sqrt(squares / (bytes / 2))
    }

    private companion object {
        const val TAG = "OpusDalvikTest"
    }
}
