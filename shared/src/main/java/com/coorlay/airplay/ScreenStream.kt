package com.coorlay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/** One receive thread owns each opener; implementations must authenticate header and body. */
fun interface ScreenFrameDecryptor {
    /** Opens the first [length] bytes of [body]; the receiver reuses [body] for the next frame. */
    fun open(counter: Long, header: ByteArray, body: ByteArray, length: Int): ByteArray

    fun open(counter: Long, header: ByteArray, body: ByteArray): ByteArray = open(counter, header, body, body.size)

    /** Opens the first [length] bytes of [body] into [output] (at least length - 16 bytes); returns the plaintext size. */
    fun openInto(counter: Long, header: ByteArray, body: ByteArray, length: Int, output: ByteArray): Int {
        val plain = open(counter, header, body, length)
        plain.copyInto(output)
        return plain.size
    }
}

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val frameDecryptor: ScreenFrameDecryptor? = null,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        /**
         * A frame in the first [length] bytes of a pooled buffer; [recycle] returns the buffer once it is no longer
         * read. Listeners that do not override this get an exact copy.
         */
        fun onFrame(naluBytes: ByteArray, length: Int, senderNanos: Long, arrivalNanos: Long, recycle: (ByteArray) -> Unit) {
            val exact = naluBytes.copyOf(length)
            recycle(naluBytes)
            onFrame(exact, senderNanos, arrivalNanos)
        }
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    /**
     * Decrypted frames travel to the decoder in these buffers and come back once MediaCodec has copied them. A new
     * array per frame (1-1.5 MB/s) still caused every GC_FOR_ALLOC pause of 31-49 ms on the head unit (2026-10-12).
     * Made with the stream, before its first frame (see ScreenFramePool).
     */
    private val framePool = ScreenFramePool()
    private val processing = ScreenProcessingStats(onDiagnostic, created = { framePool.created })
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        try {
            bound.reuseAddress = true
            bound.bind(airPlaySocketAddress())
        } catch (error: Exception) {
            safeClose(bound)
            throw error
        }
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        // Reused for every frame (25-30 a second, tens of KB each): only the decrypted frame handed to the decoder
        // queue is new. Two fresh arrays per frame fed GC_FOR_ALLOC pauses of 36-42 ms (2026-10-12).
        val header = ByteArray(HEADER_LEN)
        var body = ByteArray(INITIAL_BODY)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                if (!readFully(input, header, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                if (body.size < bodySize) body = ByteArray(maxOf(bodySize, body.size * 2).coerceAtMost(MAX_BODY))
                if (!readFully(input, body, bodySize)) break
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, bodySize, arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, bodySize: Int, arrivalNanos: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val started = System.nanoTime()
                val payload = framePool.obtain(maxOf(bodySize - ScreenCodec.TAG_SIZE, 1))
                val length = if (bodySize >= ScreenCodec.TAG_SIZE) {
                    val counter = frameCounter.get()
                    (frameDecryptor?.openInto(counter, header, body, bodySize, payload)
                        ?: ScreenCodec.decryptFrame(key, counter, header, body.copyOf(bodySize)).also { it.copyInto(payload) }.size)
                        .also { frameCounter.incrementAndGet() }
                } else {
                    body.copyInto(payload, 0, 0, bodySize)
                    bodySize
                }
                val decrypted = System.nanoTime()
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodySize plain=$length " +
                        "head=${payload.copyOf(minOf(length, 16)).hexPrefix(16)}",
                    )
                }
                ScreenCodec.lengthPrefixedToAnnexBInPlace(payload, length)
                val converted = System.nanoTime()
                listener.onFrame(payload, length, ScreenCodec.senderNanos(header), arrivalNanos, framePool.recycle)
                processing.frame(bodySize, decrypted - started, converted - decrypted, System.nanoTime() - converted)
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body.copyOf(bodySize))
                Log.i(TAG, "video codec config codec=$codec body=$bodySize data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, output: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private companion object {
        const val TAG = "coorlay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val INITIAL_BODY = 256 * 1024
    }
}

/** Separate crypto/conversion/delivery time so an old CPU bottleneck is measurable. */
internal class ScreenProcessingStats(
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
    /** Frame buffers made so far; each window reports the new ones (poolNew), each a possible GC pause. */
    private val created: () -> Int = { 0 },
) {
    private var createdBefore = created()
    private var windowStart = nowNs()
    private var frames = 0
    private var bytes = 0L
    private var decryptNs = 0L
    private var decryptMaxNs = 0L
    private var convertMaxNs = 0L
    private var deliverMaxNs = 0L

    fun frame(size: Int, decrypt: Long, convert: Long, deliver: Long) {
        frames++; bytes += size
        decryptNs += decrypt; decryptMaxNs = maxOf(decryptMaxNs, decrypt)
        convertMaxNs = maxOf(convertMaxNs, convert); deliverMaxNs = maxOf(deliverMaxNs, deliver)
        val now = nowNs()
        if (now - windowStart < 5_000_000_000L) return
        report("Video processing frames=$frames bytes=$bytes decryptAvgUs=${decryptNs / frames / 1000} " +
            "decryptMaxUs=${decryptMaxNs / 1000} convertMaxUs=${convertMaxNs / 1000} deliveryMaxUs=${deliverMaxNs / 1000} " +
            "poolNew=${created() - createdBefore}")
        createdBefore = created()
        windowStart = now; frames = 0; bytes = 0L; decryptNs = 0L
        decryptMaxNs = 0L; convertMaxNs = 0L; deliverMaxNs = 0L
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds
     * (DiPlay 0.2.15). Consecutive frames are whole frame intervals apart; skipped frames leave multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        lengthPrefixedToAnnexBInPlace(payload, payload.size)
        return payload
    }

    /** [lengthPrefixedToAnnexB] for the first [size] bytes of a reused buffer. */
    fun lengthPrefixedToAnnexBInPlace(payload: ByteArray, size: Int) {
        if (size < 4 || payload.startsWithStartCode()) return

        var offset = 0
        while (offset + 4 <= size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > size) return
            offset += length
        }
        if (offset != size) return

        offset = 0
        while (offset + 4 <= size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
