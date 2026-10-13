package com.coorlay.coolray

import android.test.AndroidTestCase
import android.util.Log
import com.coorlay.media.SoftwareOpusDecoder
import com.coorlay.media.SoftwareOpusEncoder

/** Concentus must load on the API-18 Dalvik verifier and keep up with 20 ms frames there. */
class OpusDalvikTest : AndroidTestCase() {
    /** Costs of the encoder complexity and decoder rate on Dalvik, for choosing the defaults. */
    fun testCostOfOpusSettings() {
        val pcm = ByteArray(640)
        for (i in 0 until 320) { val v = (Math.sin(i / 3.0) * 6000).toInt(); pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte() }
        for (complexity in listOf(0, 1, 3, 5)) {
            val encoder = SoftwareOpusEncoder(16_000, 32_000, complexity)
            repeat(50) { encoder.encode(pcm) }
            val started = System.nanoTime()
            repeat(250) { encoder.encode(pcm) }
            Log.i("OpusDalvikTest", "encode complexity=$complexity avg=%.2fms".format((System.nanoTime() - started) / 1e6 / 250))
        }
        val packet = SoftwareOpusEncoder(16_000, 32_000).encode(pcm)
        for (rate in listOf(48_000, 24_000, 16_000)) {
            val decoder = SoftwareOpusDecoder(rate, 1)
            repeat(50) { decoder.decode(packet, 0, packet.size) }
            val started = System.nanoTime()
            repeat(250) { decoder.decode(packet, 0, packet.size) }
            Log.i("OpusDalvikTest", "decode rate=$rate avg=%.2fms".format((System.nanoTime() - started) / 1e6 / 250))
        }
    }

    fun testSoftwareOpusRunsInRealTimeOnDalvik() {
        val encoder = SoftwareOpusEncoder(16_000, 32_000)
        val decoder = SoftwareOpusDecoder(48_000, 1)
        val pcm = ByteArray(encoder.frameBytes)
        val frames = 250 // 5 s
        var encodeNs = 0L
        var decodeNs = 0L
        repeat(frames) { frame ->
            for (i in 0 until pcm.size / 2) {
                val value = (Math.sin(2 * Math.PI * 300 * (frame * 320 + i) / 16_000.0) * 6000).toInt()
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            val started = System.nanoTime()
            val packet = encoder.encode(pcm)
            val encoded = System.nanoTime()
            assertEquals(1920, decoder.decode(packet, 0, packet.size))
            decodeNs += System.nanoTime() - encoded
            encodeNs += encoded - started
        }
        val line = "Opus on Dalvik: encode avg=%.2fms decode avg=%.2fms per 20 ms frame".format(
            encodeNs / 1e6 / frames, decodeNs / 1e6 / frames)
        Log.i("OpusDalvikTest", line)
        // Both together must stay well inside the 20 ms a frame lasts.
        assertTrue(line, (encodeNs + decodeNs) / frames < 10_000_000L)
    }
}
