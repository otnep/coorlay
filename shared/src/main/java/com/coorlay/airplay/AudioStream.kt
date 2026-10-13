package com.coorlay.airplay

import com.coorlay.media.StreamThreadPriority
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /**
     * Rate the stream is decoded and played at. [sampleRate] stays 48 kHz for Opus (microphone path), but
     * decoding a 16 kHz navigation prompt to 48 kHz tripled the work on the Coolray CPU and the prompt
     * crackled (0.0.45b-9), so 16 kHz Opus decodes at 16 kHz. 24 and 48 kHz Opus decode at 48 kHz: Concentus
     * turns super-wideband/fullband SILK and hybrid packets into silence or noise at any other output rate
     * (OpusDecodeRateTest), and Siri over Wi-Fi (0x20000000) was only a squeak at 24 kHz (tester, 2026-10-13).
     */
    val decodeRate: Int = sampleRate,
    /** The stream's RTP clock, reported in /feedback: Opus counts at 16/24/48 kHz whatever it is decoded at. */
    val clockRate: Int = decodeRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}

        /** Whether [onPacket] is wanted for good packets: it costs a copy of every datagram. */
        val wantsPackets: Boolean get() = false
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = try { bindAnyPort() } catch (error: Exception) { data.close(); throw error }
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        onDiagnostic(StreamThreadPriority.audio())
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        // Reused for every datagram; only the RTP array handed to the renderer is new (it is queued there).
        val packet = DatagramPacket(buffer, buffer.size)
        val opener = AirPlayCrypto.Opener(key)
        try {
            while (!closed.get()) {
                packet.setLength(buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val length = packet.length
                stats.received(
                    size = length,
                    sequence = if (length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val packetNumber = receivedPackets.incrementAndGet()
                if (length < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=$length",
                        )
                    }
                    listener.onPacket(
                        buffer.copyOf(length),
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val sealedEnd = length - NONCE_LEN
                val sealedLength = sealedEnd - RTP_HEADER_LEN
                val sample = readU32Be(buffer, 4)
                // Header and plaintext go straight into the RTP array the renderer gets.
                val rtp = ByteArray(RTP_HEADER_LEN + sealedLength - TAG_LEN)
                try {
                    val plain = opener.open(buffer, 4, RTP_HEADER_LEN - 4, RTP_HEADER_LEN, sealedLength,
                        sealedEnd, rtp, RTP_HEADER_LEN)
                    check(plain == rtp.size - RTP_HEADER_LEN) { "Unexpected audio plaintext length $plain" }
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    val wire = buffer.copyOf(length)
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                buffer.copyInto(rtp, 0, 0, RTP_HEADER_LEN)
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=$length payloadBytes=${rtp.size - RTP_HEADER_LEN} " +
                            "payloadHead=${rtp.copyOfRange(RTP_HEADER_LEN, minOf(rtp.size, RTP_HEADER_LEN + 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                if (listener.wantsPackets) listener.onPacket(buffer.copyOf(length), rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } finally { stats.flush(ended = true) }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(airPlaySocketAddress())
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val TAG = "coorlay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> opusRate(bits).let { clock ->
                AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType, opusDecodeRate(clock), clock)
            }
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    /**
     * The Opus format's own rate. Observed RTP clocks (2026-10-12): 0x10000000 navigation advances
     * 320 per 20 ms, 0x20000000 Siri 480, 0x40000000 alert 960.
     */
    fun opusRate(bits: Long): Int = when {
        bits and 0x40000000L != 0L -> 48_000
        bits and 0x20000000L != 0L -> 24_000
        bits and 0x10000000L != 0L -> 16_000
        else -> 48_000
    }

    /** Only wideband (16 kHz) Opus decodes correctly below 48 kHz in Concentus; see [AudioFormat.decodeRate]. */
    fun opusDecodeRate(clockRate: Int): Int = if (clockRate == 16_000) 16_000 else 48_000

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
