package com.coorlay.media

import android.content.Context
import android.media.AudioFormat as PcmFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.coorlay.airplay.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Media backend restricted to API-16 buffer arrays and the legacy audio constructors. */
class AndroidMediaSink(context: Context, private val width: Int = 1920, private val height: Int = 640,
    private val preferredDecoder: String = "OMX.Freescale.std.video_decoder.avc.v3.hw-based",
    private val report: (String) -> Unit = {}, private val onVideoActive: () -> Unit = {},
    onMusicFocusLost: () -> Unit = {}, onMusicFocusRegained: () -> Unit = {},
    /** Smooth video: show main-screen frames at the iPhone's frame times plus a self-adjusting delay. */
    private val smoothVideo: Boolean = false) : MediaSink, Closeable {
    private val video = ConcurrentHashMap<Int, LegacyVideoDecoder>()
    private val audio = ConcurrentHashMap<AudioStreamId, LegacyAudioRenderer>()
    private val microphones = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val diagnostics = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recovery = ConcurrentHashMap<Int, () -> Unit>()
    private val focus = LegacyAudioFocus(context, report, onMusicFocusLost, onMusicFocusRegained)
    @Volatile private var surface: Surface? = null
    fun setSurface(value: Surface?) {
        surface = value
        video.values.forEach { it.surface(value) }
    }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery[type] = handler }
    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics[type] = handler }
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        require(codec == VideoCodec.H264) { "This head unit advertises H.264 only" }
    }
    private fun decoder(type: Int): LegacyVideoDecoder = synchronized(video) {
        video[type] ?: LegacyVideoDecoder(surface, width, height, preferredDecoder,
            { recovery[type]?.invoke() }, { line ->
                diagnostics[type]?.invoke(line)
                report(line)
                if (line == "first frame rendered") onVideoActive()
            }, pacingDelayNanos = if (smoothVideo && type == MAIN_SCREEN_TYPE) INITIAL_PACING_DELAY_NS else 0L,
        ).also { video[type] = it }
    }
    override fun onVideoConfig(type: Int, codecData: ByteArray) = decoder(type).configure(codecData)
    /** [naluBytes] passes to the decoder: ScreenStream allocates every frame and never touches it again. */
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) = decoder(type).frame(naluBytes)
    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        decoder(type).frame(naluBytes, senderNanos, arrivalNanos)
    override fun onVideoFrame(type: Int, naluBytes: ByteArray, length: Int, senderNanos: Long, arrivalNanos: Long,
        recycle: (ByteArray) -> Unit) = decoder(type).frame(naluBytes, senderNanos, arrivalNanos, length, recycle)
    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) video.remove(type)?.close()
    }
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) = synchronized(audio) {
        audio.remove(id)?.close()
        audio[id] = LegacyAudioRenderer(format, firstSample, focus, report)
        audioEverStarted = true
    }
    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audio[id]?.packet(rtp, sample)
    }
    override fun onAudioStopped(id: AudioStreamId) { audio.remove(id)?.close() }
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val mic = MicrophoneUplink(config, report)
        microphones.put(id, mic)?.close()
        if (mic.start()) report("Microphone: ${mic.status}")
        else { microphones.remove(id)?.close(); report("Microphone unavailable: ${mic.status}") }
    }
    override fun onMicrophoneStopped(id: AudioStreamId) { microphones.remove(id)?.close() }
    /** True when CarPlay music had yielded to another source and now owns the speakers again. */
    fun resumeMusic(reason: String): Boolean = focus.reclaim(reason)
    /** Whether another head-unit source currently owns the speakers instead of CarPlay music. */
    val musicYielded: Boolean get() = focus.parked
    /** Whether the iPhone currently sends a music stream over CarPlay. */
    val musicStreaming: Boolean get() = audio.keys.any { it.type == 102 || it.audioType == "media" }
    /** Whether the iPhone has opened any CarPlay audio stream in this session. */
    @Volatile var audioEverStarted = false
        private set
    override fun close() {
        video.values.forEach { it.close() }; video.clear()
        audio.values.forEach { it.close() }; audio.clear()
        microphones.values.forEach { it.close() }; microphones.clear()
        diagnostics.clear(); recovery.clear()
        focus.close()
    }
    private companion object {
        const val MAIN_SCREEN_TYPE = 110
        /** DiPlay's start: three frame intervals plus 40 ms, 140 ms at the 30 fps this head unit asks for. */
        const val INITIAL_PACING_DELAY_NS = 140_000_000L
    }
}

private class LegacyVideoDecoder(surface: Surface?, private val width: Int, private val height: Int,
    private val preferred: String, private val recover: () -> Unit, private val report: (String) -> Unit,
    pacingDelayNanos: Long = 0L) : Closeable {
    // Smooth video (DiPlay 0.2.15 FramePacer/PacingDelay). API 18 has no releaseOutputBuffer(index, timeNs),
    // so a decoded frame waits in [held] and the worker renders it when its display time comes.
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    private val held = java.util.ArrayDeque<HeldFrame>()
    private class HeldFrame(val index: Int, val targetNs: Long)
    private var maxHeld = 1
    // Per queued presentation time: when it was queued, and whether it is a paced frame's local time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Paced frames queued just before a pause in the iPhone's frames wait for that pause in the decoder.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val jobs = VideoDecodeQueue()
    private val chain = VideoReferenceChain()
    private val stats = VideoStats(logcat = false) // The reporter applies the USB diagnostics setting.
    @Volatile private var running = true
    private var surface = surface
    private var config: ByteArray? = null
    private var codec: MediaCodec? = null
    private var inputs: Array<ByteBuffer> = emptyArray()
    private var firstFrame = false
    private var lastRecovery = 0L
    private var configReported = false
    private var lastError = ""
    private var lastErrorReported = 0L
    private var lastResyncReported = 0L
    private var maxQueueAgeNs = 0L
    private var maxInputWaitNs = 0L
    private val decoderAttemptsReported = mutableSetOf<String>()
    private val outputInfo = MediaCodec.BufferInfo() // Worker thread only; drain() runs every 5 ms.
    private val worker = Thread({ run() }, "coolray-h264").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    fun surface(value: Surface?) { jobs.offer(VideoJob.SurfaceChanged(value)) }
    fun configure(bytes: ByteArray) { jobs.offer(VideoJob.Config(VideoCodec.H264, bytes.copyOf())) }
    // Not copied: up to ~1 MB/s of garbage, and Dalvik GC_FOR_ALLOC pauses stall audio and video alike.
    fun frame(bytes: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L, length: Int = bytes.size,
        recycle: ((ByteArray) -> Unit)? = null) {
        stats.onReceived(length)
        jobs.offer(VideoJob.Frame(bytes, senderNanos = senderNanos, arrivalNanos = arrivalNanos, length = length, recycle = recycle))
    }
    private fun run() {
        report(StreamThreadPriority.video())
        try {
            while (running) {
                try {
                    when (val job = jobs.pollNanos(pollTimeoutNanos())) {
                        is VideoJob.Config -> {
                            if (codec != null && config?.contentEquals(job.codecData) == true) continue
                            if (config?.contentEquals(job.codecData) != true) {
                                configReported = false
                                decoderAttemptsReported.clear()
                            }
                            config = job.codecData; reset(); create()
                        }
                        is VideoJob.SurfaceChanged -> if (surface !== job.surface) { surface = job.surface; reset(); create() }
                        is VideoJob.Frame -> try {
                            val age = System.nanoTime() - job.receivedNs
                            maxQueueAgeNs = maxOf(maxQueueAgeNs, age)
                            if (age > 300_000_000L) {
                                jobs.discardFrames(); resync("backlog ageMs=${age / 1_000_000}")
                            } else feed(job.nalus, job.length, localTimeOf(job))
                        } finally {
                            // MediaCodec has its own copy now (or the frame was dropped): the buffer goes back.
                            job.recycled()
                        }
                        is VideoJob.Resync -> resync("queue overflow")
                        null -> Unit
                    }
                    drain()
                    stats.logIfDue()?.let {
                        report("$it queueAgeMaxMs=${maxQueueAgeNs / 1_000_000} inputWaitMaxMs=${maxInputWaitNs / 1_000_000}")
                        maxQueueAgeNs = 0L; maxInputWaitNs = 0L
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (!running) break
                    val signature = "${error.javaClass.name}: ${error.message}"
                    val now = System.nanoTime()
                    if (signature != lastError || now - lastErrorReported >= 5_000_000_000L) {
                        lastError = signature; lastErrorReported = now
                        report("H.264 decoder error: $signature\n${Log.getStackTraceString(error)}")
                    }
                    resync("decoder exception", recreate = true)
                }
            }
        } catch (_: InterruptedException) { } finally { reset() }
    }
    private fun create() {
        val config = config ?: return
        val surface = surface ?: return
        val (sps, pps) = MediaCodecSupport.avcParameterSets(config)
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "Missing H.264 SPS/PPS" }
        if (!configReported) {
            configReported = true
            report("H.264 config profile=${sps.getOrNull(1)?.toInt()?.and(255)} " +
                "level=${sps.getOrNull(3)?.toInt()?.and(255)} spsBytes=${sps.size} ppsBytes=${pps.size} " +
                "requested=${width}x$height surfaceValid=${surface.isValid}")
        }
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
        val attempts = listOf(preferred, "", "OMX.google.h264.decoder").distinct()
        for (name in attempts) {
            var candidate: MediaCodec? = null
            val firstAttempt = decoderAttemptsReported.add(name)
            try {
                candidate = if (name.isEmpty()) MediaCodec.createDecoderByType("video/avc") else MediaCodec.createByCodecName(name)
                if (firstAttempt) {
                    val levels = runCatching {
                        candidate.codecInfo.getCapabilitiesForType("video/avc").profileLevels
                            .joinToString { "${it.profile}:${it.level}" }
                    }.getOrElse { "unavailable (${it.javaClass.simpleName}: ${it.message})" }
                    report("H.264 decoder capabilities name=${candidate.name} profileLevels=$levels")
                }
                candidate.configure(format, surface, null, 0)
                candidate.start()
                inputs = candidate.inputBuffers
                codec = candidate
                updateMaxHeld(candidate)
                report("H.264 decoder=${candidate.name}, ${width}x$height" +
                    if (pacingDelay != null) " smoothVideo maxHeld=$maxHeld" else "")
                return
            } catch (error: Exception) {
                if (firstAttempt) report("H.264 decoder initialization failed name=${name.ifEmpty { "default" }}: " +
                    "${error.javaClass.name}: ${error.message}\n${Log.getStackTraceString(error)}")
                runCatching { candidate?.release() }
            }
        }
        throw IllegalStateException("No H.264 decoder accepted ${width}x$height")
    }
    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L
    /** Wake in time for the next held frame, otherwise every 5 ms as before. */
    private fun pollTimeoutNanos(): Long {
        val next = held.peekFirst() ?: return POLL_NS
        return (next.targetNs - System.nanoTime() - RELEASE_EARLY_NS).coerceIn(0L, POLL_NS)
    }
    private fun feed(bytes: ByteArray, length: Int, presentNs: Long = 0L) {
        if (surface == null || config == null) return
        // ScreenStream already made Annex B in place; anything else is converted from an exact copy (rare).
        val startCode = length >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()
        val annex = if (startCode) bytes else MediaCodecSupport.toAnnexB(bytes.copyOf(length))
        val annexLength = if (startCode) length else annex.size
        if (!chain.accepts(annex, VideoCodec.H264, annexLength)) { requestRecovery(); return }
        if (codec == null) create()
        val codec = codec ?: return
        val started = System.nanoTime()
        val index = VideoInputPump.acquire({ running }, { drain() }, { codec.dequeueInputBuffer(10_000) })
        maxInputWaitNs = maxOf(maxInputWaitNs, System.nanoTime() - started)
        if (index < 0) { if (running) resync("input stalled", recreate = true); return }
        val buffer = inputs[index]
        buffer.clear()
        if (annexLength > buffer.remaining()) {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            throw IllegalStateException("H.264 frame exceeds decoder input buffer")
        }
        buffer.put(annex, 0, annexLength)
        // A paced frame carries its local time; MediaCodec returns it with the decoded output.
        val presentationUs = if (presentNs > 0) presentNs / 1000L else System.nanoTime() / 1000L
        codec.queueInputBuffer(index, 0, annexLength, presentationUs, 0)
        chain.onQueued()
        val queuedNow = System.nanoTime()
        if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
        lastQueuedNanos = queuedNow
        queuedPresentationUs[queuedSlot] = presentationUs
        queuedAtNanos[queuedSlot] = queuedNow
        queuedPaced[queuedSlot] = presentNs > 0
        queuedBeforePause[queuedSlot] = false
        if (presentNs > 0) {
            // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
            if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
            }
            lastQueuedLocalNanos = presentNs
            recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
            recentPacedSlots[0] = queuedSlot
        }
        queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
    }
    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1
    private fun drain() {
        val codec = codec ?: return
        releaseDue(codec)
        while (running) {
            val index = codec.dequeueOutputBuffer(outputInfo, 0)
            when {
                index >= 0 -> {
                    val now = System.nanoTime()
                    val slot = queuedSlotOf(outputInfo.presentationTimeUs)
                    val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                    // A frame queued before a still screen waited for later input: not decode time.
                    val heldOverGap = queued in 1 until resumedAtNanos
                    if (queued > 0 && !heldOverGap) stats.onDecodeLatency(now - queued)
                    val delay = pacingDelay
                    val paced = surface != null && delay != null && slot >= 0 && queuedPaced[slot]
                    val pauseHeld = paced && queuedBeforePause[slot]
                    val targetNs = if (paced && delay != null) {
                        val localNs = outputInfo.presentationTimeUs * 1000L
                        if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                        stats.onPacingDelay(delay.nanos)
                        localNs + delay.nanos
                    } else 0L
                    if (targetNs - now in RELEASE_EARLY_NS..MAX_PACING_AHEAD_NS) {
                        // The decoder needs free output buffers: show the oldest waiting frame early.
                        while (held.size >= maxHeld) { stats.onForced(); render(codec, held.pollFirst()!!.index) }
                        held.addLast(HeldFrame(index, targetNs))
                        stats.onHeld(held.size)
                    } else {
                        if (paced && !heldOverGap && !pauseHeld) stats.onLate()
                        // Frames must reach the screen in decode order.
                        while (held.isNotEmpty()) render(codec, held.pollFirst()!!.index)
                        render(codec, index)
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> report("H.264 output=${codec.outputFormat}")
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> if (pacingDelay != null) {
                    // Held indices stay valid; only the count of buffers to keep free changes.
                    updateMaxHeld(codec)
                    report("H.264 output buffers changed smoothVideo maxHeld=$maxHeld")
                }
                else -> return
            }
        }
    }
    /** Keeps two output buffers free for the decoder; it stalls without any. */
    private fun updateMaxHeld(codec: MediaCodec) {
        maxHeld = (codec.outputBuffers.size - 2).coerceIn(1, MAX_HELD_FRAMES)
    }
    /** Renders the held frames whose display time has come. */
    private fun releaseDue(codec: MediaCodec) {
        while (true) {
            val next = held.peekFirst() ?: return
            if (next.targetNs - System.nanoTime() > RELEASE_EARLY_NS) return
            render(codec, held.pollFirst()!!.index)
        }
    }
    private fun render(codec: MediaCodec, index: Int) {
        codec.releaseOutputBuffer(index, surface != null)
        if (surface != null) stats.onRendered()
        if (!firstFrame && surface != null) { firstFrame = true; report("first frame rendered") }
    }
    private fun requestRecovery() {
        if (!running) return
        val now = System.nanoTime()
        if (now - lastRecovery >= 1_000_000_000L) { lastRecovery = now; recover() }
    }
    private fun resync(reason: String, recreate: Boolean = false) {
        if (!running) return
        // Dropping unsubmitted references needs an IDR, not a new hardware codec.
        // That IDR resets H.264 reference pictures while retaining the expensive OMX instance.
        if (recreate) reset() else chain.reset()
        stats.onRecovery()
        val now = System.nanoTime()
        if (now - lastResyncReported >= 1_000_000_000L) {
            lastResyncReported = now
            report("H.264 recovery reason=$reason decoderKept=${codec != null}")
        }
        requestRecovery()
    }
    private fun reset() {
        // Held output buffers belong to the codec being released.
        held.clear()
        val old = codec; codec = null; inputs = emptyArray(); chain.reset(); firstFrame = false
        runCatching { old?.stop() }; runCatching { old?.release() }
    }
    override fun close() { running = false; worker.interrupt() }
    private companion object {
        const val POLL_NS = 5_000_000L
        // Rendered this much before its time: the worker wakes with millisecond jitter.
        const val RELEASE_EARLY_NS = 2_000_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        const val MAX_HELD_FRAMES = 6
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive iPhone frames this far apart are a pause in its frames: about four intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
    }
}

/**
 * Android audio focus for CarPlay audio. The Neusoft radio, USB and Bluetooth players request focus
 * when the MODE key switches source. On that permanent loss the music tracks are muted and the phone
 * is asked to pause; focus is reclaimed only when the phone plays audible music again.
 */
internal class LegacyAudioFocus(context: Context, private val report: (String) -> Unit,
    private val onLost: () -> Unit, private val onRegained: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** Track → whether it carries music (muted while another source owns the speakers). */
    private val tracks = mutableMapOf<AudioTrack, Boolean>()
    private var focusState = AudioManager.AUDIOFOCUS_GAIN
    private var holding = false
    private var lostAt = 0L
    /** True after a permanent loss, until the phone plays audible music again. */
    @Volatile var parked = false
        private set
    private val listener = AudioManager.OnAudioFocusChangeListener { event -> change(event) }

    private var closed = false

    @Synchronized private fun change(event: Int) {
        // A closed session must not react: its LOSS would pause the next session's iPhone.
        if (closed) return
        report("Audio focus: ${describe(event)}")
        focusState = event
        if (event == AudioManager.AUDIOFOCUS_LOSS) {
            // MODE switched to radio/USB/Bluetooth: stop CarPlay music instead of mixing both.
            holding = false
            parked = true
            lostAt = SystemClock.elapsedRealtime()
            manager.abandonAudioFocus(listener)
            onLost()
        }
        tracks.forEach { (track, music) -> applyVolume(track, music) }
    }

    private fun applyVolume(track: AudioTrack, music: Boolean) {
        val volume = volume(music)
        runCatching { track.setStereoVolume(volume, volume) }
    }

    /** Track volume for the current focus state. */
    @Synchronized fun volume(music: Boolean): Float = when {
        // Transient losses (chimes, prompts) leave music audible as before 0.0.23b: muting on each
        // one chopped the music. Only a permanent loss (MODE switched source) silences it.
        music && parked -> 0f
        focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> .2f
        else -> 1f
    }

    @Synchronized fun acquire(track: AudioTrack, music: Boolean) {
        if (closed) return // A renderer starting after the session closed must not take focus.
        // While parked, navigation prompts and calls still play; music waits for a reclaim.
        if (!holding && !parked) request()
        tracks[track] = music
        applyVolume(track, music)
    }

    @Synchronized fun release(track: AudioTrack) {
        // Focus stays held while the session lives: a paused iPhone tears its stream down, and
        // abandoning here handed the speakers straight back to the OEM radio. [close] releases it.
        tracks.remove(track)
    }

    /** Music from the phone became audible again: the driver chose CarPlay music once more. */
    @Synchronized fun musicAudible() {
        if (!parked || SystemClock.elapsedRealtime() - lostAt < RECLAIM_GRACE_MS) return
        reclaim("CarPlay music resumed")
    }

    /**
     * The driver returned to CarPlay: take the speakers back (the OEM radio/Bluetooth player
     * stops on its focus loss). False when CarPlay was not parked or Android refused focus.
     */
    @Synchronized fun reclaim(reason: String): Boolean {
        if (closed || !parked || !request()) return false
        parked = false
        report("Audio focus reclaimed: $reason")
        tracks.forEach { (track, music) -> applyVolume(track, music) }
        onRegained()
        return true
    }

    private fun request(): Boolean {
        val granted = manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) { holding = true; focusState = AudioManager.AUDIOFOCUS_GAIN }
        else report("Audio focus request denied")
        return granted
    }

    /** Session ended: give the speakers back even when no track held them (e.g. after a reclaim). */
    @Synchronized fun close() {
        closed = true
        parked = false
        tracks.clear()
        if (holding) {
            manager.abandonAudioFocus(listener)
            holding = false
        }
    }

    private fun describe(event: Int) = when (event) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS (another source)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> event.toString()
    }

    internal companion object {
        /** The pause request needs a moment; audio still in flight must not undo the switch. */
        const val RECLAIM_GRACE_MS = 3000L
    }
}

/** True when 16-bit little-endian PCM in [offset, offset + length) holds more than near-silence. */
internal fun pcmAudible(bytes: ByteArray, length: Int = bytes.size, threshold: Int = 64, offset: Int = 0): Boolean {
    var cursor = offset
    val end = offset + length
    while (cursor + 1 < end) {
        val sample = (bytes[cursor].toInt() and 0xff) or (bytes[cursor + 1].toInt() shl 8)
        if (sample > threshold || sample < -threshold) return true
        cursor += 2
    }
    return false
}

/**
 * Multiplies 16-bit little-endian PCM in place by [gain]. Above [PCM_KNEE] the rest of the range up to twice
 * full scale is squeezed into the headroom, so loud syllables flatten instead of clipping hard.
 */
internal fun applyPcmGain(bytes: ByteArray, offset: Int, length: Int, gain: Float) {
    var cursor = offset
    val end = offset + length
    while (cursor + 1 < end) {
        val sample = (bytes[cursor].toInt() and 0xff) or (bytes[cursor + 1].toInt() shl 8)
        var scaled = (sample * gain).toInt()
        val magnitude = if (scaled < 0) -scaled else scaled
        if (magnitude > PCM_KNEE) {
            val squeezed = minOf(32767, PCM_KNEE + (magnitude - PCM_KNEE) * (32767 - PCM_KNEE) / (65534 - PCM_KNEE))
            scaled = if (scaled < 0) -squeezed else squeezed
        }
        bytes[cursor] = scaled.toByte(); bytes[cursor + 1] = (scaled shr 8).toByte()
        cursor += 2
    }
}

private const val PCM_KNEE = 24576

/** The 7-byte MPEG-4 ADTS header of [MediaCodecSupport.adtsFrame], written into [target] without a new array. */
internal fun writeAdtsHeader(target: ByteArray, accessUnitSize: Int, sampleRate: Int, channels: Int) {
    val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(sampleRate)
    val channelConfig = channels.coerceIn(1, 7)
    val frameLength = accessUnitSize + 7
    target[0] = 0xff.toByte()
    target[1] = 0xf1.toByte()
    target[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
    target[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
    target[4] = ((frameLength ushr 3) and 0xff).toByte()
    target[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
    target[6] = 0xfc.toByte()
}

private class LegacyAudioRenderer(private val format: AudioFormat, private val firstSample: Int,
    private val focus: LegacyAudioFocus, private val report: (String) -> Unit) : Closeable {
    private val packets = LinkedBlockingQueue<Pair<ByteArray, Int>>(192)
    /** Music streams yield to other sources; prompts, Siri and calls keep playing. */
    private val music = format.payloadType == 102 || format.audioType == "media"
    @Volatile private var running = true
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var inputs: Array<ByteBuffer> = emptyArray()
    private var outputs: Array<ByteBuffer> = emptyArray()
    // Worker thread only: reused for every packet instead of fresh arrays per packet.
    private val outputInfo = MediaCodec.BufferInfo()
    private val adtsHeader = ByteArray(7)
    private var pcm = ByteArray(0)
    private var opus: SoftwareOpusDecoder? = null
    @Volatile private var statsMaxDecodeNs = 0L
    private var opusErrors = 0
    private val rate = format.decodeRate
    private val aac = format.codec == AudioCodecKind.AAC_LC
    /** Music and Opus voice pause and rebuild their reserve when the track runs dry instead of crackling. */
    private val rebuffering = aac || format.codec == AudioCodecKind.OPUS
    private val stepMs = if (aac) MUSIC_STEP_MS else VOICE_STEP_MS
    private val maxMs = if (aac) MUSIC_MAX_MS else VOICE_MAX_MS
    /**
     * Siri's reply comes much quieter than music and the car adds no voice gain of its own (user, 2026-10-18);
     * so does the other party in a call (user, 2026-10-21). Both play on the music volume, not the stock
     * phone's own call volume.
     */
    private val gain = when {
        format.audioType.equals("speechRecognition", ignoreCase = true) -> SIRI_GAIN
        format.audioType.equals("telephony", ignoreCase = true) -> CALL_GAIN
        else -> 1f
    }
    /**
     * Prompts, Siri and calls arrive unbuffered (audioLatencyMs 80). Started on the first 20 ms packet,
     * the track ran dry on every late packet or slow decode and the navigation voice crackled (0.0.45b-9),
     * so playback waits for [PREBUFFER_MS] of PCM. Music (AAC, audioLatencyMs 1000) started at once and every
     * Wi-Fi gap of 0.4-1.5 s was heard (2026-10-12): it waits for [MUSIC_START_MS], and after the track ran dry
     * while packets kept coming, for [MUSIC_STEP_MS] more each time, up to [MUSIC_MAX_MS]. Siri's 24 kHz Opus
     * decodes at 48 kHz (see AudioFormat.decodeRate), which costs the CPU more: [OPUS_48K_PREBUFFER_MS].
     * Opus voice ran dry on decode stalls and Siri still lagged (2026-10-18): it rebuilds its reserve the same
     * way, [VOICE_STEP_MS] more each time up to [VOICE_MAX_MS].
     */
    private var targetMs = when {
        aac -> MUSIC_START_MS
        format.codec == AudioCodecKind.OPUS && rate == 48_000 -> OPUS_48K_PREBUFFER_MS
        else -> PREBUFFER_MS
    }
    private fun bytesFor(ms: Int) = rate * format.channels * 2 * ms / 1000
    private var prebufferBytes = bytesFor(targetMs)
    private var framesWritten = 0L
    /** When the music track ran dry (0 = not dry); a packet soon after means a gap, not a pause. */
    private var dryAtNs = 0L
    @Volatile private var statsRebuffers = 0
    private var playing = false
    private var pendingBytes = 0
    private var lastTakenNs = 0L
    private var lastSeq = -1
    @Volatile private var statsConcealed = 0
    @Volatile private var statsDropped = 0
    private val worker = Thread({ run() }, "coolray-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    // Five-second counters, like the video stats: arrival gaps point at the iPhone/USB link,
    // a growing queue or slow writes point at this head unit.
    @Volatile private var statsPackets = 0
    @Volatile private var statsMaxGapNs = 0L
    @Volatile private var statsMaxQueue = 0
    @Volatile private var statsMaxWriteNs = 0L
    // Signal level before [gain], to compare Siri with music: sum of squares, samples and peak.
    @Volatile private var statsSquares = 0.0
    @Volatile private var statsSamples = 0L
    @Volatile private var statsPeak = 0
    @Volatile private var lastPacketNs = 0L
    private var statsWindowNs = System.nanoTime()
    fun packet(rtp: ByteArray, sample: Int) {
        val now = System.nanoTime()
        if (lastPacketNs != 0L) statsMaxGapNs = maxOf(statsMaxGapNs, now - lastPacketNs)
        lastPacketNs = now
        statsPackets++
        // AudioStream allocates every RTP packet and does not reuse it, so it is queued as is.
        if (!packets.offer(rtp to sample)) report("Audio receive queue overflow")
        statsMaxQueue = maxOf(statsMaxQueue, packets.size)
    }
    /** [final]: the stream ended; Siri replies and prompts are shorter than one five-second window. */
    private fun reportStatsIfDue(final: Boolean = false) {
        val now = System.nanoTime()
        val elapsed = now - statsWindowNs
        if (elapsed < 5_000_000_000L && !final) return
        if (statsPackets > 0) report("audio stats ${format.audioType} rx=%.1fpps maxGap=%dms queueMax=%d writeMax=%dms".format(
            java.util.Locale.US, statsPackets / (elapsed / 1e9), statsMaxGapNs / 1_000_000, statsMaxQueue, statsMaxWriteNs / 1_000_000) +
            (if (opus != null) " opusDecodeMax=%.1fms concealed=%d dropped=%d".format(java.util.Locale.US,
                statsMaxDecodeNs / 1e6, statsConcealed, statsDropped) else "") +
            (if (rebuffering) " reserve=%dms rebuffers=%d".format(java.util.Locale.US, targetMs, statsRebuffers) else "") +
            (if (statsSamples > 0) " level rms=%.1fdBFS peak=%.1fdBFS gain=%.1f".format(java.util.Locale.US,
                dbfs(Math.sqrt(statsSquares / statsSamples)), dbfs(statsPeak.toDouble()), gain) else "") +
            (if (final) " final" else ""))
        statsWindowNs = now; statsPackets = 0; statsMaxGapNs = 0; statsMaxQueue = 0; statsMaxWriteNs = 0; statsMaxDecodeNs = 0
        statsConcealed = 0; statsDropped = 0; statsRebuffers = 0; statsSquares = 0.0; statsSamples = 0; statsPeak = 0
    }
    private fun dbfs(value: Double) = if (value <= 0.0) -99.0 else 20 * Math.log10(value / 32768.0)
    private fun measure(bytes: ByteArray, start: Int, length: Int) {
        var squares = 0.0; var peak = statsPeak
        var cursor = start
        val end = start + length
        while (cursor + 1 < end) {
            val sample = (bytes[cursor].toInt() and 0xff) or (bytes[cursor + 1].toInt() shl 8)
            squares += sample.toDouble() * sample
            if (sample > peak) peak = sample else if (-sample > peak) peak = -sample
            cursor += 2
        }
        statsSquares += squares; statsSamples += length / 2; statsPeak = peak
    }
    private fun run() {
        report(StreamThreadPriority.audio())
        try {
            val channels = if (format.channels == 1) PcmFormat.CHANNEL_OUT_MONO else PcmFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(rate, channels, PcmFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "Unsupported audio sample rate" }
            // The buffer stays 200 ms above the prebuffer (music: its largest target), so writes before play()
            // never block.
            val audio = AudioTrack(AudioManager.STREAM_MUSIC, rate, channels, PcmFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, bytesFor((if (rebuffering) maxMs else targetMs) + 200)), AudioTrack.MODE_STREAM)
            track = audio
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
            focus.acquire(audio, music)
            if (format.codec == AudioCodecKind.AAC_LC) {
                val mediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", format.sampleRate, format.channels)
                mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                val value = (2 shl 11) or (MediaCodecSupport.aacFrequencyIndex(format.sampleRate) shl 7) or (format.channels shl 3)
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((value shr 8).toByte(), value.toByte())))
                val decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
                codec = decoder
                decoder.configure(mediaFormat, null, null, 0); decoder.start()
                inputs = decoder.inputBuffers; outputs = decoder.outputBuffers
            }
            if (format.codec == AudioCodecKind.OPUS) opus = SoftwareOpusDecoder(rate, format.channels)
            report("Audio ${format.audioType}: ${format.codec}${opus?.let { " (${it.backend})" } ?: ""}, " +
                "$rate Hz, ${format.channels} channels, prebuffer=${targetMs}ms")
            while (running) {
                val packet = packets.poll(10, TimeUnit.MILLISECONDS)
                val now = System.nanoTime()
                if (packet != null) {
                    lastTakenNs = now
                    if (dryAtNs != 0L) {
                        // Packets still come: the track ran dry in a gap, so the stream keeps a larger reserve.
                        if (now - dryAtNs < DRY_GAP_NS && targetMs < maxMs) {
                            targetMs = minOf(targetMs + stepMs, maxMs); prebufferBytes = bytesFor(targetMs)
                            report("Audio ${format.audioType} buffer ran dry; reserve now ${targetMs}ms")
                        }
                        dryAtNs = 0L
                    }
                }
                // A short chime or the end of a prompt: play what is there instead of waiting for more.
                else if (!playing && pendingBytes > 0 && now - lastTakenNs > (if (aac) MUSIC_IDLE_START_NS else IDLE_START_NS)) startPlayback()
                if (rebuffering && playing) checkDry(now)
                if (packet != null && packet.first.size > RTP_HEADER) {
                    // The payload is used in place after the 12-byte RTP header.
                    val rtp = packet.first
                    val size = rtp.size - RTP_HEADER
                    if (format.codec == AudioCodecKind.LPCM) {
                        // Network byte order to the little-endian PCM AudioTrack expects.
                        for (offset in RTP_HEADER until rtp.size - 1 step 2) {
                            val first = rtp[offset]; rtp[offset] = rtp[offset + 1]; rtp[offset + 1] = first
                        }
                        write(rtp, RTP_HEADER, size)
                    } else if (opus != null) {
                        val decoder = opus!!
                        val seq = ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
                        val step = (seq - lastSeq) and 0xffff
                        if (lastSeq >= 0 && (step == 0 || step >= 0x8000)) {
                            // Duplicate or late: its slot was already concealed or played.
                            statsDropped++
                        } else {
                            if (lastSeq >= 0 && step - 1 in 1..MAX_CONCEALED) repeat(step - 1) {
                                val bytes = runCatching { decoder.conceal() }.getOrDefault(0)
                                if (bytes > 0) { write(decoder.output, 0, bytes); statsConcealed++ }
                            }
                            lastSeq = seq
                            val started = System.nanoTime()
                            val bytes = try { decoder.decode(rtp, RTP_HEADER, size) } catch (error: Exception) {
                                if (opusErrors++ < 3) report("Opus decode failed: ${error.javaClass.simpleName}: ${error.message}")
                                0
                            }
                            statsMaxDecodeNs = maxOf(statsMaxDecodeNs, System.nanoTime() - started)
                            if (bytes > 0) write(decoder.output, 0, bytes)
                        }
                    } else {
                        val decoder = codec!!
                        val index = VideoInputPump.acquire({ running }, { drain() }, { decoder.dequeueInputBuffer(10_000) })
                        if (index >= 0) {
                            val buffer = inputs[index]; buffer.clear()
                            if (adtsHeader.size + size <= buffer.remaining()) {
                                // Same bytes as MediaCodecSupport.adtsFrame, without building a new array.
                                writeAdtsHeader(adtsHeader, size, format.sampleRate, format.channels)
                                buffer.put(adtsHeader); buffer.put(rtp, RTP_HEADER, size)
                                val elapsed = (packet.second.toLong() - firstSample.toLong()) and 0xffffffffL
                                decoder.queueInputBuffer(index, 0, adtsHeader.size + size, elapsed * 1_000_000L / format.sampleRate, 0)
                            } else decoder.queueInputBuffer(index, 0, 0, 0, 0)
                        }
                    }
                }
                drain()
                reportStatsIfDue()
            }
        } catch (_: InterruptedException) { } catch (error: Exception) { report("Audio error: ${error.message}") }
        finally {
            runCatching { reportStatsIfDue(final = true) }
            opus?.close()
            val old = codec; codec = null; runCatching { old?.stop() }; runCatching { old?.release() }
            track?.let { focus.release(it); runCatching { it.stop() }; runCatching { it.release() } }
            track = null
        }
    }
    private fun drain() {
        val decoder = codec ?: return
        val info = outputInfo
        while (running) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val buffer = outputs[index]
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    buffer.get(pcm, 0, info.size); write(pcm, 0, info.size)
                    decoder.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs = decoder.outputBuffers
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }
    private fun write(bytes: ByteArray, start: Int, length: Int) {
        if (music && focus.parked && pcmAudible(bytes, length, offset = start)) focus.musicAudible()
        measure(bytes, start, length)
        if (gain != 1f) applyPcmGain(bytes, start, length, gain)
        val started = System.nanoTime()
        val end = start + length
        var offset = start
        while (running && offset < end) {
            val count = track?.write(bytes, offset, end - offset) ?: return
            if (count <= 0) return
            offset += count
        }
        statsMaxWriteNs = maxOf(statsMaxWriteNs, System.nanoTime() - started)
        framesWritten += (offset - start) / (2 * format.channels)
        if (!playing) {
            pendingBytes += length
            if (pendingBytes >= prebufferBytes) startPlayback()
        }
    }
    /** Music and Opus voice: a track with nothing left is paused until the reserve has built up again. */
    private fun checkDry(now: Long) {
        val audio = track ?: return
        val played = audio.playbackHeadPosition.toLong() and 0xffffffffL
        if (framesWritten - played > 0) return
        runCatching { audio.pause() }
        playing = false; pendingBytes = 0; dryAtNs = now; statsRebuffers++
    }
    private fun startPlayback() {
        val audio = track ?: return
        runCatching { audio.play() }
        playing = true
    }
    override fun close() {
        running = false; worker.interrupt()
        // Stop unblocks a writer before the worker releases its track.
        runCatching { track?.pause() }; runCatching { track?.flush() }
    }
    private companion object {
        const val RTP_HEADER = 12
        /** Above the 55–120 ms Opus decode stalls seen on the unit; a prompt starts this much later. */
        const val PREBUFFER_MS = 100
        /** Opus decoded at 48 kHz (Siri over Wi-Fi): before 0.0.45b-10 it stuttered there with no prebuffer. */
        const val OPUS_48K_PREBUFFER_MS = 200
        /** No packet for this long: the stream paused or ended, so buffered audio plays at once. */
        const val IDLE_START_NS = 60_000_000L
        /** Music reserve: start, step after each dry track that was a gap, and ceiling (the iPhone allows 1000 ms). */
        const val MUSIC_START_MS = 300
        const val MUSIC_STEP_MS = 150
        const val MUSIC_MAX_MS = 800
        /** Opus voice reserve: step after each dry track that was a gap, and ceiling. */
        const val VOICE_STEP_MS = 100
        const val VOICE_MAX_MS = 500
        /** About +6 dB; the level in the stats line shows how far Siri sits below music. */
        const val SIRI_GAIN = 2f
        /**
         * About +9.5 dB: call audio is levelled for a handset earpiece (around -26 dBFS speech), well below Siri
         * and music; the soft knee in [applyPcmGain] takes the louder syllables.
         */
        const val CALL_GAIN = 3f
        /** Music starts with less than its reserve only after this long without a packet. */
        const val MUSIC_IDLE_START_NS = 1_000_000_000L
        /** A packet within this time after the track ran dry: a gap in arrival, not the iPhone pausing. */
        const val DRY_GAP_NS = 2_000_000_000L
        /** Longer gaps are a restart, not loss: the decoder resumes without inventing audio. */
        const val MAX_CONCEALED = 5
    }
}
