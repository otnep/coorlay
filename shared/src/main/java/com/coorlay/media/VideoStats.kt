package com.coorlay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
    private val logcat: Boolean = true,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    /** Gaps of 2 s and more: left out of maxGap (a still screen) but counted, so a freeze is not "maxGap=0". */
    private var longGaps = 0
    private var maxLongGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val decodeMicros = IntArray(1024)
    private var decodeSamples = 0
    private var late = 0
    private var forced = 0
    private var maxHeld = 0
    private var pacingDelayNanos = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        else if (lastArrivalNs != 0L) { longGaps++; maxLongGapNs = maxOf(maxLongGapNs, gap) }
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onRecovery() { recoveries++ }

    /** Time from queueing a frame into the decoder to dequeueing its output. */
    @Synchronized fun onDecodeLatency(ns: Long) {
        if (decodeSamples < decodeMicros.size) decodeMicros[decodeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized fun onLate() { late++ }

    /** A paced frame was shown before its time because the decoder needed its output buffer back. */
    @Synchronized fun onForced() { forced++ }

    /** How many decoded frames wait for their display time. */
    @Synchronized fun onHeld(count: Int) { maxHeld = maxOf(maxHeld, count) }

    /** Smooth video's current display delay ([PacingDelay]). */
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    private fun decodeSummary(): String {
        if (decodeSamples == 0) return ""
        val sorted = decodeMicros.copyOf(decodeSamples).also { it.sort() }
        fun percentileMs(p: Int) = sorted[(decodeSamples - 1) * p / 100] / 1000
        val pacing = if (pacingDelayNanos > 0) {
            " late=%d delay=%dms forced=%d heldMax=%d".format(late, pacingDelayNanos / 1_000_000, forced, maxHeld)
        } else ""
        return " decode p50=%dms p90=%dms".format(percentileMs(50), percentileMs(90)) + pacing
    }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        // A window without frames after the stream started is a freeze (or a still screen): it is reported too.
        if (received == 0 && touchSamples == 0 && lastArrivalNs == 0L) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + " longGaps=%d longGapMax=%dms sinceLastFrame=%dms".format(longGaps, maxLongGapNs / 1_000_000,
            if (lastArrivalNs == 0L) -1 else (now - lastArrivalNs) / 1_000_000) + decodeSummary()
        TouchLatencyProbe.maxSendNs = 0
        if (logcat) Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0; longGaps = 0; maxLongGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        decodeSamples = 0; late = 0; forced = 0; maxHeld = 0
        return line
    }

    private companion object {
        const val TAG = "coorlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
