package com.coorlay.airplay

import java.util.concurrent.ArrayBlockingQueue

/**
 * Buffers for decrypted video frames. The receive thread takes them ([obtain]); the decoder gives them back
 * ([recycle]) once MediaCodec has copied the frame.
 *
 * Dalvik grows the heap with a GC_FOR_ALLOC pause of 30-50 ms on the allocating thread. Every buffer used to
 * be as large as the largest keyframe (~160 KB) and was made on the first frames of a stream, so the video
 * started with about 15 such pauses in two seconds, and again when a larger keyframe came after a call
 * (1.0.3-4-debug on the unit, 2026-10-21). Frames average 6-23 KB: the regular buffers are smaller and made
 * up front, when the stream is set up; keyframes above [regularSize] use a few buffers of their own.
 */
internal class ScreenFramePool(
    private val regularSize: Int = REGULAR_SIZE,
    regularCount: Int = REGULAR_COUNT,
    largeCount: Int = LARGE_COUNT,
    private val largeStep: Int = LARGE_STEP,
) {
    private val regular = ArrayBlockingQueue<ByteArray>(regularCount)
    private val large = ArrayBlockingQueue<ByteArray>(largeCount)
    /** Largest frame seen: new large buffers take this size, so a keyframe stops replacing them one by one. */
    private var largest = 0

    /** Buffers made after the pool was set up, each a possible GC pause; read on the receive thread. */
    var created = 0
        private set

    val recycle: (ByteArray) -> Unit = { buffer -> (if (buffer.size > regularSize) large else regular).offer(buffer) }

    init {
        repeat(regularCount) { regular.offer(ByteArray(regularSize)) }
    }

    /** A buffer of at least [minimum] bytes; receive thread only. */
    fun obtain(minimum: Int): ByteArray {
        if (minimum <= regularSize) return regular.poll() ?: create(regularSize)
        if (minimum > largest) largest = minimum
        while (true) {
            val buffer = large.poll() ?: break
            if (buffer.size >= minimum) return buffer
            // Smaller than this keyframe: dropped, and the new one below has the largest size seen.
        }
        return create((largest + largeStep - 1) / largeStep * largeStep)
    }

    private fun create(size: Int): ByteArray {
        created++
        return ByteArray(size)
    }

    companion object {
        const val REGULAR_SIZE = 64 * 1024
        /** In flight at once: the decoder queue and MediaCodec's input; 24 x 64 KB is made when the stream is set up. */
        const val REGULAR_COUNT = 24
        const val LARGE_COUNT = 4
        const val LARGE_STEP = 32 * 1024
    }
}
