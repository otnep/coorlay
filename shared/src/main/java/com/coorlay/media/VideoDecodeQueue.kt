package com.coorlay.media

import android.view.Surface
import com.coorlay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
        /** Bytes of [nalus] that hold the frame; a pooled buffer is longer. */
        val length: Int = nalus.size,
        /** Gives a pooled [nalus] back once nothing reads it any more. */
        val recycle: ((ByteArray) -> Unit)? = null,
    ) : VideoJob {
        fun recycled() { recycle?.invoke(nalus) }
    }
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec, size: Int = bytes.size): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec, size)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // Queued frames and their bytes: offer() runs for every frame and must not walk or copy the queue.
    private var frames = 0
    private var bytes = 0L

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (frames >= maxFrames || bytes + job.length > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.length > maxBytes) { job.recycled(); return }
            frames++; bytes += job.length
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is VideoJob.Frame || job is VideoJob.Resync) {
                iterator.remove()
                if (job is VideoJob.Frame) { dequeued(job); job.recycled() }
            }
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? = pollNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMillis))

    /** Like [poll] with a finer timeout, for a worker that must wake in time to show a paced frame. */
    fun pollNanos(timeoutNanos: Long): VideoJob? =
        jobs.poll(timeoutNanos, TimeUnit.NANOSECONDS)?.also { if (it is VideoJob.Frame) dequeued(it) }

    @Synchronized private fun dequeued(frame: VideoJob.Frame) { frames--; bytes -= frame.length }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
