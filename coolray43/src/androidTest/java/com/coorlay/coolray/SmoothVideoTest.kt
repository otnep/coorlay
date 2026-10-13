package com.coorlay.coolray

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.test.AndroidTestCase
import android.util.Log
import android.view.Surface
import com.coorlay.media.AndroidMediaSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smooth video on the API-18 decoder path: frames that arrive in bursts (as over Wi-Fi) must still reach
 * the surface, in order and without stalling the decoder, and with smooth video on, about one frame
 * interval apart instead of in bunches.
 */
class SmoothVideoTest : AndroidTestCase() {
    fun testBurstyFramesReachTheSurfaceWithAndWithoutSmoothVideo() {
        val (config, frames) = encode(FRAME_COUNT)
        assertTrue("encoder produced ${frames.size} frames", frames.size >= FRAME_COUNT - 2)
        val plain = play(config, frames, smooth = false)
        val smooth = play(config, frames, smooth = true)
        Log.i(TAG, "plain ${summary(plain)}")
        Log.i(TAG, "smooth ${summary(smooth)}")
        assertTrue("plain rendered ${plain.size}", plain.size >= frames.size * 3 / 4)
        assertTrue("smooth rendered ${smooth.size}", smooth.size >= frames.size * 3 / 4)
        // After the delay has settled, smooth video spreads each 3-frame burst over about 100 ms.
        val settled = intervalsMs(smooth).drop(45)
        val bunched = settled.count { it < 8 }
        assertTrue("smooth bunched $bunched of ${settled.size}: ${summary(smooth)}", bunched * 4 < settled.size)
    }

    private fun summary(times: List<Long>): String {
        val intervals = intervalsMs(times)
        val sorted = intervals.sorted()
        if (sorted.isEmpty()) return "rendered=${times.size}"
        return "rendered=${times.size} intervalMs p10=${sorted[sorted.size / 10]} p50=${sorted[sorted.size / 2]} " +
            "p90=${sorted[sorted.size * 9 / 10]} under8ms=${intervals.count { it < 8 }}"
    }

    private fun intervalsMs(times: List<Long>) = times.zipWithNext { a, b -> (b - a) / 1_000_000 }

    /** Plays [frames] through the sink: bursts of three every 100 ms, with 30 fps iPhone frame times. */
    private fun play(config: ByteArray, frames: List<ByteArray>, smooth: Boolean): List<Long> {
        val output = FrameClock()
        val reports = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(context, WIDTH, HEIGHT, preferredDecoder = "",
            report = { reports += it; Log.i(TAG, "${if (smooth) "smooth" else "plain"}: $it") }, smoothVideo = smooth)
        try {
            sink.setSurface(output.surface)
            sink.onVideoConfig(MAIN_SCREEN, config)
            val senderBase = 5_000_000_000L
            frames.chunked(3).forEachIndexed { burst, group ->
                group.forEachIndexed { offset, frame ->
                    val index = burst * 3 + offset
                    sink.onVideoFrame(MAIN_SCREEN, frame.copyOf(), senderBase + index * FRAME_NS, System.nanoTime())
                }
                Thread.sleep(100)
            }
            Thread.sleep(1_500)
        } finally {
            sink.close()
            Thread.sleep(200)
            output.close()
        }
        assertFalse("decoder errors: $reports", reports.any { it.startsWith("H.264 decoder error") })
        return output.times.toList()
    }

    /** Consumes the decoder's output on a GL thread and records when each frame became available. */
    private class FrameClock {
        val times = CopyOnWriteArrayList<Long>()
        private val thread = HandlerThread("smooth-video-consumer").apply { start() }
        private val handler = Handler(thread.looper)
        private lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

        init {
            val ready = CountDownLatch(1)
            handler.post {
                val version = IntArray(2)
                EGL14.eglInitialize(display, version, 0, version, 1)
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
                val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                val pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                val names = IntArray(1)
                GLES20.glGenTextures(1, names, 0)
                texture = SurfaceTexture(names[0])
                texture.setOnFrameAvailableListener {
                    times += System.nanoTime()
                    handler.post { runCatching { texture.updateTexImage() } }
                }
                surface = Surface(texture)
                ready.countDown()
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "GL consumer did not start" }
        }

        fun close() {
            handler.post { surface.release(); texture.release() }
            thread.quitSafely()
        }
    }

    /** Encodes [count] frames of a moving bar: (avcC record, Annex B frames). */
    private fun encode(count: Int): Pair<ByteArray, List<ByteArray>> {
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val colorFormat = encoder.codecInfo.getCapabilitiesForType("video/avc").colorFormats.firstOrNull {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        } ?: error("no YUV420 input on ${encoder.name}")
        val format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val inputs = encoder.inputBuffers
        var outputs = encoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        val frames = ArrayList<ByteArray>()
        var codecConfig = ByteArray(0)
        val yuv = ByteArray(WIDTH * HEIGHT * 3 / 2)
        var submitted = 0
        var ended = false
        while (!ended) {
            if (submitted <= count) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (submitted == count) {
                        encoder.queueInputBuffer(index, 0, 0, submitted * FRAME_NS / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        java.util.Arrays.fill(yuv, 0, WIDTH * HEIGHT, 40)
                        java.util.Arrays.fill(yuv, WIDTH * HEIGHT, yuv.size, 128.toByte())
                        val bar = (submitted * 8) % WIDTH
                        for (y in 0 until HEIGHT) for (x in bar until minOf(bar + 24, WIDTH)) yuv[y * WIDTH + x] = 220.toByte()
                        inputs[index].clear(); inputs[index].put(yuv)
                        encoder.queueInputBuffer(index, 0, yuv.size, submitted * FRAME_NS / 1000, 0)
                    }
                    submitted++
                }
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs = encoder.outputBuffers
                out >= 0 -> {
                    val bytes = ByteArray(info.size)
                    outputs[out].position(info.offset); outputs[out].get(bytes)
                    if (frames.size < 2) Log.i(TAG, "encoder out flags=${info.flags} size=${info.size} head=" +
                        bytes.take(12).joinToString(" ") { "%02x".format(it) })
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) codecConfig += bytes
                    // Four-byte start codes, as ScreenStream hands the iPhone's frames on.
                    // The emulator's encoder hands out one bare NAL per buffer.
                    else if (bytes.isNotEmpty()) frames += ByteArrayOutputStream().apply {
                        val nals = splitAnnexB(bytes).ifEmpty { listOf(bytes) }
                        nals.forEach { write(byteArrayOf(0, 0, 0, 1)); write(it) }
                    }.toByteArray()
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                }
            }
        }
        encoder.stop(); encoder.release()
        val nals = splitAnnexB(codecConfig)
        val sps = nals.first { it[0].toInt() and 0x1f == 7 }
        val pps = nals.first { it[0].toInt() and 0x1f == 8 }
        val avcC = ByteArrayOutputStream().apply {
            write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
            write(sps.size shr 8); write(sps.size); write(sps)
            write(1); write(pps.size shr 8); write(pps.size); write(pps)
        }.toByteArray()
        return avcC to frames
    }

    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
        }
        return starts.mapIndexed { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else bytes.size
            while (end > start && bytes[end - 1].toInt() == 0) end--
            bytes.copyOfRange(start, end)
        }
    }

    private companion object {
        const val TAG = "SmoothVideoTest"
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FRAME_COUNT = 180
        const val FRAME_NS = 33_333_333L
        const val MAIN_SCREEN = 110
    }
}
