package com.coorlay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.coorlay.airplay.AudioCodecKind
import com.coorlay.airplay.MicrophoneConfig
import com.coorlay.airplay.MicrophoneCounters
import com.coorlay.airplay.MicrophonePacketizer
import com.coorlay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onStats: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var resampler: PcmResampler? = null
    private var opus: SoftwareOpusEncoder? = null
    private var frameBytes = config.frameBytes
    // Capture thread only, reused for every 20 ms packet: a new cipher and six arrays per packet (two more when
    // resampling a wired PCM stream) kept Dalvik's GC busy through every CarPlay call and Siri reply.
    private val sealer = MicrophonePacketizer.PacketSealer(config.key, config.payloadType)
    private val datagram = DatagramPacket(ByteArray(0), 0, config.host, config.port)
    private var wirePcm = ByteArray(0)

    /** Chosen capture path after a successful [start], or the failure reason otherwise. */
    @Volatile var status: String = "not started"
        private set

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        if (config.codec != AudioCodecKind.LPCM && config.codec != AudioCodecKind.OPUS) {
            fail("unsupported codec ${config.codec}")
            return false
        }
        val opusStream = config.codec == AudioCodecKind.OPUS
        val preferredSource = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        // The Coolray primary input (AUDIO_DEVICE_IN_BUILTIN_MIC) accepts only 8/16 kHz, while the
        // phone negotiates 48 kHz voice; capture at a rate the HAL opens and resample up.
        val rates = listOf(config.sampleRate, 16_000, 8_000).distinct()
        val sources = listOf(preferredSource, MediaRecorder.AudioSource.MIC).distinct()
        var nextRecorder: AudioRecord? = null
        var captureRate = 0
        var captureSource = 0
        val attempts = ArrayList<String>()
        search@ for (source in sources) {
            for (rate in rates) {
                val minBuffer = AudioRecord.getMinBufferSize(rate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) {
                    attempts += "src=$source rate=$rate minBuffer=$minBuffer"
                    continue
                }
                val captureFrameBytes = maxOf(1, rate * config.frameMillis / 1000) * config.channels * 2
                val bufferSize = maxOf(minBuffer * 2, captureFrameBytes * 4)
                val candidate = try {
                    AudioRecord(source, rate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT, bufferSize)
                } catch (_: SecurityException) {
                    fail("permission denied")
                    return false
                } catch (error: Exception) {
                    attempts += "src=$source rate=$rate error=${error.message}"
                    continue
                }
                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    attempts += "src=$source rate=$rate not initialized"
                    candidate.release()
                    continue
                }
                nextRecorder = candidate
                captureRate = rate
                captureSource = source
                break@search
            }
        }
        if (nextRecorder == null) {
            fail("no capture configuration: ${attempts.joinToString("; ")}")
            return false
        }
        if (opusStream) {
            // Opus takes the 16 kHz capture as is; no resampling to 48 kHz on this CPU.
            val encoderRate = if (SoftwareOpusEncoder.supportsRate(captureRate)) captureRate else config.sampleRate
            val encoder = try {
                SoftwareOpusEncoder(encoderRate, config.bitrate ?: DEFAULT_OPUS_BITRATE)
            } catch (error: Throwable) {
                nextRecorder.release()
                fail("software Opus encoder unavailable: ${error.javaClass.simpleName}: ${error.message}")
                return false
            }
            opus = encoder
            frameBytes = encoder.frameBytes
            resampler = if (captureRate != encoderRate) PcmResampler(captureRate, encoderRate, config.channels) else null
            status = "capture ${captureRate} Hz src=$captureSource -> Opus (${encoder.backend}) ${encoderRate} Hz " +
                "rtpClock=${config.opusClockRate} type=${config.audioType}"
        } else {
            resampler = if (captureRate != config.sampleRate) {
                PcmResampler(captureRate, config.sampleRate, config.channels)
            } else {
                null
            }
            status = "capture ${captureRate} Hz src=$captureSource -> ${config.sampleRate} Hz " +
                "type=${config.audioType}"
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                try { bind(com.coorlay.airplay.airPlaySocketAddress()) } catch (error: Exception) {
                    close()
                    throw error
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            nextRecorder.release()
            fail("socket creation failed: ${error.message}")
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        return try {
            nextRecorder.startRecording()
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started $status " +
                    "channels=${config.channels} frameMs=${config.frameMillis} port=${config.port}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            status = "recording failed: ${error.message}"
            release()
            false
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "microphone unavailable: $reason")
        status = reason
        running.set(false)
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        try {
            while (running.get()) {
                val count = recorder.read(readBuffer, 0, readBuffer.size)
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    continue
                }
                val converter = resampler
                val available = converter?.processInto(readBuffer, count) ?: count
                val samples = converter?.output ?: readBuffer
                var offset = 0
                while (offset < available && running.get()) {
                    val copied = minOf(frame.size - filled, available - offset)
                    samples.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val encoder = opus
        if (encoder != null) {
            val started = System.nanoTime()
            val length = try { encoder.encodeToOutput(frame) } catch (error: Exception) {
                if (encodeErrors++ < 3) Log.w(TAG, "microphone Opus encode failed", error)
                return
            }
            noteEncode(System.nanoTime() - started)
            // The RTP clock is the one the iPhone chose (24 kHz for Siri), not the capture rate.
            sendPacket(socket, counters, encoder.packet, length, config.rtpSamplesPerPacket)
            return
        }
        if (wirePcm.size < frame.size) wirePcm = ByteArray(frame.size)
        MicrophonePacketizer.toWirePcmInto(frame, frame.size, wirePcm)
        sendPacket(socket, counters, wirePcm, frame.size, config.samplesPerPacket)
    }

    private var encodeErrors = 0
    private var encodeFrames = 0
    private var encodeMaxNs = 0L
    private var encodeTotalNs = 0L

    /** Logs the software encoder's cost every 5 s: it runs on the capture thread of a slow CPU. */
    private fun noteEncode(ns: Long) {
        encodeFrames++
        encodeTotalNs += ns
        encodeMaxNs = maxOf(encodeMaxNs, ns)
        if (encodeFrames == ENCODE_STATS_FRAMES) {
            val line = "microphone Opus encode avg=%.1fms max=%.1fms frames=%d".format(java.util.Locale.US,
                encodeTotalNs / 1e6 / encodeFrames, encodeMaxNs / 1e6, encodeFrames)
            Log.i(TAG, line)
            onStats(line)
            encodeFrames = 0; encodeTotalNs = 0; encodeMaxNs = 0
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        bodyLength: Int,
        samples: Int,
    ) {
        val size = sealer.seal(counters, body, bodyLength, samples)
        val packet = sealer.packet
        try {
            datagram.setData(packet, 0, size)
            socket.send(datagram)
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=$size body=$bodyLength " +
                        "head=${packet.copyOf(minOf(size, 16)).toHexString()} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        // A capture thread still encoding gets an exception, not freed native memory (encoder's own lock).
        opus?.close()
    }

    private companion object {
        const val TAG = "coorlay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val DEFAULT_OPUS_BITRATE = 32_000
        /** 5 s of 20 ms frames. */
        const val ENCODE_STATS_FRAMES = 250
    }
}
