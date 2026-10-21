package com.coorlay.media

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusSignal

/**
 * Opus for Android 4.3, which has no MediaCodec Opus codec. Wireless CarPlay needs Opus: offered only PCM,
 * the iPhone sets up no CarPlay audio over Wi-Fi and falls back to Bluetooth (DiPlay ea898e3).
 *
 * The codecs run on libopus (jni/opus, [NativeOpus]) when the app's native library loads. Concentus, Opus
 * in pure Java (vendored from DiPlay 0.2.16), is the fallback and what the JVM unit tests run: on the
 * Coolray's Dalvik it took 55-180 ms for one 20 ms voice packet (2026-10-12) and Siri lagged behind it.
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
    /** False keeps Concentus where libopus loads too (benchmarks). */
    preferNative: Boolean = true,
) : java.io.Closeable {
    private var handle = if (preferNative && NativeOpus.available) {
        NativeOpus.encoderCreate(sampleRate, CHANNELS, bitrate, complexity)
    } else 0L
    private val concentus = if (handle != 0L) null else
        OpusEncoder(sampleRate, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(bitrate)
            setComplexity(complexity)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
    private val output = ByteArray(MAX_PACKET_BYTES)

    /** "libopus" or "Concentus", for the journal. */
    val backend: String = if (concentus == null) NativeOpus.NAME else CONCENTUS

    /** Samples in one 20 ms frame at [sampleRate]. */
    val frameSamples: Int = sampleRate * FRAME_MILLIS / 1000
    val frameBytes: Int = frameSamples * CHANNELS * 2

    /** Encodes one 20 ms little-endian 16-bit mono frame into one Opus packet. */
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size == frameBytes) { "Opus frame must be $frameBytes bytes, got ${pcm.size}" }
        val encoder = concentus
        val length = if (encoder != null) {
            checked(encoder::resetState) { encoder.encode(pcm, 0, frameSamples, output, 0, output.size) }
        } else synchronized(this) {
            check(handle != 0L) { "Opus encoder closed" }
            NativeOpus.checkResult(NativeOpus.encode(handle, pcm, frameSamples, output), "encode")
        }
        return output.copyOf(length)
    }

    /** Frees the libopus state; encode fails after this. Safe from any thread, more than once. */
    @Synchronized override fun close() {
        if (handle != 0L) { NativeOpus.encoderDestroy(handle); handle = 0L }
    }

    protected fun finalize() = close()

    companion object {
        const val CHANNELS = 1
        const val FRAME_MILLIS = 20
        /**
         * The Coolray CPU is slow. On the API-18 emulator a 16 kHz 20 ms frame took 3.2 ms at complexity 0
         * and 1, 4.3 ms at 3 and 6.1 ms at 5 in Concentus (OpusDalvikTest); 1 costs the same as 0 and
         * sounds better.
         */
        const val DEFAULT_COMPLEXITY = 1
        private const val MAX_PACKET_BYTES = 1_275
        private val SUPPORTED_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

        fun supportsRate(rate: Int) = rate in SUPPORTED_RATES
    }
}

/** Decodes the iPhone's Opus packets (one per RTP payload) into 16-bit little-endian PCM. */
class SoftwareOpusDecoder(
    val sampleRate: Int,
    val channels: Int,
    /** False keeps Concentus where libopus loads too (benchmarks). */
    preferNative: Boolean = true,
) : java.io.Closeable {
    private var handle = if (preferNative && NativeOpus.available) NativeOpus.decoderCreate(sampleRate, channels) else 0L
    private val concentus = if (handle != 0L) null else OpusDecoder(sampleRate, channels)
    // Reused for every packet: per-packet arrays feed Dalvik's GC_FOR_ALLOC pauses. libopus writes the
    // little-endian PCM straight into pcm; Concentus decodes to samples first.
    private val samples = ShortArray(if (concentus != null) MAX_FRAME_SAMPLES * channels else 0)
    private var pcm = ByteArray(if (concentus != null) 0 else MAX_FRAME_SAMPLES * channels * 2)

    /** "libopus" or "Concentus", for the journal. */
    val backend: String = if (concentus == null) NativeOpus.NAME else CONCENTUS

    /**
     * Decodes [length] bytes from [offset]; returns the number of PCM bytes now in [output] (valid until
     * the next call).
     */
    fun decode(packet: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= packet.size) { "Opus packet out of range" }
        val decoder = concentus ?: return decodeNative(packet, offset, length, MAX_FRAME_SAMPLES)
        return toPcm(checked(decoder::resetState) { decoder.decode(packet, offset, length, samples, 0, MAX_FRAME_SAMPLES, false) })
    }

    /**
     * Opus packet-loss concealment for one lost 20 ms packet; returns the PCM bytes in [output]. A lost
     * packet otherwise left 20 ms of nothing, heard as a click in a navigation prompt.
     */
    fun conceal(): Int {
        val decoder = concentus ?: return decodeNative(null, 0, 0, sampleRate / 50)
        return toPcm(checked(decoder::resetState) { decoder.decode(null, 0, 0, samples, 0, sampleRate / 50, false) })
    }

    @Synchronized private fun decodeNative(packet: ByteArray?, offset: Int, length: Int, maxSamples: Int): Int {
        check(handle != 0L) { "Opus decoder closed" }
        return NativeOpus.checkResult(NativeOpus.decode(handle, packet, offset, length, pcm, maxSamples), "decode") *
            channels * 2
    }

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

    /** Frees the libopus state; decoding fails after this. Safe from any thread, more than once. */
    @Synchronized override fun close() {
        if (handle != 0L) { NativeOpus.decoderDestroy(handle); handle = 0L }
    }

    protected fun finalize() = close()

    private companion object {
        /** The longest Opus packet, 120 ms, at 48 kHz. */
        const val MAX_FRAME_SAMPLES = 5_760
    }
}

private const val CONCENTUS = "Concentus"

/** libopus 1.5.2 through jni/opus_jni.c. The JVM cannot load it; the codecs use Concentus there. */
internal object NativeOpus {
    const val NAME = "libopus"

    val available: Boolean = try {
        System.loadLibrary("coorlay_opus")
        true
    } catch (_: Throwable) {
        false
    }

    /** [result], or an exception naming the libopus error code it is. */
    fun checkResult(result: Int, what: String): Int {
        check(result >= 0) { "libopus $what failed: ${ERRORS.getOrElse(-result) { "error $result" }}" }
        return result
    }

    private val ERRORS = listOf("OK", "BAD_ARG", "BUFFER_TOO_SMALL", "INTERNAL_ERROR", "INVALID_PACKET",
        "UNIMPLEMENTED", "INVALID_STATE", "ALLOC_FAIL")

    @JvmStatic external fun decoderCreate(rate: Int, channels: Int): Long
    @JvmStatic external fun decode(handle: Long, packet: ByteArray?, offset: Int, length: Int, pcm: ByteArray, maxSamples: Int): Int
    @JvmStatic external fun decoderDestroy(handle: Long)
    @JvmStatic external fun encoderCreate(rate: Int, channels: Int, bitrate: Int, complexity: Int): Long
    @JvmStatic external fun encode(handle: Long, pcm: ByteArray, frameSamples: Int, out: ByteArray): Int
    @JvmStatic external fun encoderDestroy(handle: Long)
}
