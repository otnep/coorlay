package com.coorlay.media

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusSignal

/**
 * Opus in pure Java (Concentus, vendored from DiPlay 0.2.16) for Android 4.3, which has no MediaCodec
 * Opus codec. Wireless CarPlay needs Opus: offered only PCM, the iPhone sets up no CarPlay audio over
 * Wi-Fi and falls back to Bluetooth (DiPlay ea898e3). Free of Android types so it runs on the JVM.
 */

/**
 * Concentus checks the reference library's debug assertions in every build and throws AssertionError, an
 * Error that got past the callers' catch (Exception) and ended the app (DiPlay #865: a WhatsApp voice
 * message on an Android 8.1 head unit). A failed check now costs one frame: the codec starts the next one
 * from a clean state, and the caller sees an ordinary exception.
 */
internal inline fun <T> checked(reset: () -> Unit, block: () -> T): T = try {
    block()
} catch (error: AssertionError) {
    runCatching(reset)
    throw IllegalStateException("Opus internal check failed", error)
}
class SoftwareOpusEncoder(
    /** Capture rate fed to the encoder: 8, 12, 16, 24 or 48 kHz; Opus resamples internally. */
    val sampleRate: Int,
    bitrate: Int,
    complexity: Int = DEFAULT_COMPLEXITY,
) {
    private val encoder = OpusEncoder(sampleRate, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(complexity)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
    }
    private val output = ByteArray(MAX_PACKET_BYTES)

    /** Samples in one 20 ms frame at [sampleRate]. */
    val frameSamples: Int = sampleRate * FRAME_MILLIS / 1000
    val frameBytes: Int = frameSamples * CHANNELS * 2

    /** Encodes one 20 ms little-endian 16-bit mono frame into one Opus packet. */
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size == frameBytes) { "Opus frame must be $frameBytes bytes, got ${pcm.size}" }
        val length = checked(encoder::resetState) { encoder.encode(pcm, 0, frameSamples, output, 0, output.size) }
        return output.copyOf(length)
    }

    companion object {
        const val CHANNELS = 1
        const val FRAME_MILLIS = 20
        /**
         * The Coolray CPU is slow. On the API-18 emulator a 16 kHz 20 ms frame took 3.2 ms at complexity 0
         * and 1, 4.3 ms at 3 and 6.1 ms at 5 (OpusDalvikTest); 1 costs the same as 0 and sounds better.
         */
        const val DEFAULT_COMPLEXITY = 1
        private const val MAX_PACKET_BYTES = 1_275
        private val SUPPORTED_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

        fun supportsRate(rate: Int) = rate in SUPPORTED_RATES
    }
}

/** Decodes the iPhone's Opus packets (one per RTP payload) into 16-bit little-endian PCM. */
class SoftwareOpusDecoder(val sampleRate: Int, val channels: Int) {
    private val decoder = OpusDecoder(sampleRate, channels)
    // Reused for every packet: per-packet arrays feed Dalvik's GC_FOR_ALLOC pauses.
    private val samples = ShortArray(MAX_FRAME_SAMPLES * channels)
    private var pcm = ByteArray(0)

    /**
     * Decodes [length] bytes from [offset]; returns the number of PCM bytes now in [output] (valid until
     * the next call).
     */
    fun decode(packet: ByteArray, offset: Int, length: Int): Int =
        toPcm(checked(decoder::resetState) { decoder.decode(packet, offset, length, samples, 0, MAX_FRAME_SAMPLES, false) })

    /**
     * Opus packet-loss concealment for one lost 20 ms packet; returns the PCM bytes in [output]. A lost
     * packet otherwise left 20 ms of nothing, heard as a click in a navigation prompt.
     */
    fun conceal(): Int = toPcm(checked(decoder::resetState) { decoder.decode(null, 0, 0, samples, 0, sampleRate / 50, false) })

    private fun toPcm(decoded: Int): Int {
        val count = decoded * channels
        if (pcm.size < count * 2) pcm = ByteArray(count * 2)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        return count * 2
    }

    val output: ByteArray get() = pcm

    private companion object {
        /** The longest Opus packet, 120 ms, at 48 kHz. */
        const val MAX_FRAME_SAMPLES = 5_760
    }
}
