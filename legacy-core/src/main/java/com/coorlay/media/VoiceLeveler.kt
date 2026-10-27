package com.coorlay.media

/**
 * Automatic gain for call and Siri voice (16-bit little-endian PCM, processed in place, one decoded packet at a time).
 *
 * A fixed gain was not enough: on the unit (2026-10-21) the other party in a call sat at -20..-38 dBFS RMS with
 * peaks up to 0 dBFS, against about -9 dBFS for music, and the driver turned the music volume from 3 to 11 for the
 * call. The leveler follows the speech level over [LEVEL_WINDOW_S] and moves the gain towards [targetDbfs]: down
 * quickly, up slowly ([RELEASE_DB_PER_S]) so pauses between words do not pump. Packets below [GATE_DBFS] (silence,
 * line noise) hold the gain. A packet whose peak would pass [CEILING] after the gain gets less gain at once, so the
 * loudest syllables are never clipped.
 */
internal class VoiceLeveler(
    private val sampleRate: Int,
    private val channels: Int,
    targetDbfs: Double = TARGET_DBFS,
    maxGainDb: Double,
    startGainDb: Double,
) {
    private val target = FULL_SCALE * fromDb(targetDbfs)
    private val maxGain = fromDb(maxGainDb)
    /** The gain at the end of the last packet (a factor, not dB). */
    var gain = fromDb(startGainDb).coerceIn(MIN_GAIN, maxGain)
        private set
    /** Smoothed mean square of the speech packets; 0 until the first one. */
    private var power = 0.0
    // Range of the gain since [takeStats], for the five-second audio stats line.
    private var lowest = gain
    private var highest = gain
    private var limited = 0

    fun process(bytes: ByteArray, offset: Int, length: Int) {
        val count = length / 2
        if (count == 0) return
        var squares = 0.0
        var peak = 0
        var cursor = offset
        repeat(count) {
            val sample = (bytes[cursor].toInt() and 0xff) or (bytes[cursor + 1].toInt() shl 8)
            squares += sample.toDouble() * sample
            if (sample > peak) peak = sample else if (-sample > peak) peak = -sample
            cursor += 2
        }
        val seconds = count.toDouble() / channels / sampleRate
        val meanSquare = squares / count
        var next = gain
        if (meanSquare >= GATE_SQUARE) {
            power = if (power == 0.0) meanSquare
                else power + (meanSquare - power) * minOf(1.0, seconds / LEVEL_WINDOW_S)
            val wanted = (target / Math.sqrt(power)).coerceIn(MIN_GAIN, maxGain)
            next = if (wanted < gain) gain * Math.pow(wanted / gain, minOf(1.0, seconds / ATTACK_S))
                else minOf(wanted, gain * fromDb(RELEASE_DB_PER_S * seconds))
        }
        if (peak * next > CEILING) { next = CEILING / peak; limited++ }
        // A rising gain is spread over the packet; a falling one applies at once (that is what keeps the peak in range).
        val from = if (next > gain) gain else next
        val stepPerSample = (next - from) / count
        var applied = from
        cursor = offset
        repeat(count) {
            val sample = (bytes[cursor].toInt() and 0xff) or (bytes[cursor + 1].toInt() shl 8)
            val scaled = Math.round(sample * applied).toInt().coerceIn(-32767, 32767)
            bytes[cursor] = scaled.toByte(); bytes[cursor + 1] = (scaled shr 8).toByte()
            applied += stepPerSample
            cursor += 2
        }
        gain = next
        if (gain < lowest) lowest = gain
        if (gain > highest) highest = gain
    }

    /** " agc=+a..+bdB limited=n" for the stats line; starts a new range. */
    fun takeStats(): String {
        val text = " agc=%+.1f..%+.1fdB limited=%d".format(java.util.Locale.US, toDb(lowest), toDb(highest), limited)
        lowest = gain; highest = gain; limited = 0
        return text
    }

    internal companion object {
        const val FULL_SCALE = 32768.0
        /** Speech RMS the leveler aims at; music from the iPhone measured about -9 dBFS on the unit. */
        const val TARGET_DBFS = -14.0
        /** Never quieter than this factor (about -6 dB): very loud voice is only brought down a little. */
        const val MIN_GAIN = 0.5
        const val GATE_DBFS = -50.0
        val GATE_SQUARE = Math.pow(FULL_SCALE * fromDb(GATE_DBFS), 2.0)
        /** -1 dBFS. */
        const val CEILING = 29205.0
        const val LEVEL_WINDOW_S = 0.4
        const val ATTACK_S = 0.05
        const val RELEASE_DB_PER_S = 6.0

        fun fromDb(db: Double) = Math.pow(10.0, db / 20.0)
        fun toDb(factor: Double) = 20.0 * Math.log10(factor)

        /**
         * Calls are levelled for a handset earpiece (about -26 dBFS speech), so they start high; Siri sits nearer to
         * music. Other streams (music, navigation prompts) are left as the iPhone sends them.
         */
        fun forAudioType(audioType: String, sampleRate: Int, channels: Int): VoiceLeveler? = when {
            audioType.equals("telephony", ignoreCase = true) ->
                VoiceLeveler(sampleRate, channels, maxGainDb = 24.0, startGainDb = 12.0)
            audioType.equals("speechRecognition", ignoreCase = true) ->
                VoiceLeveler(sampleRate, channels, maxGainDb = 18.0, startGainDb = 6.0)
            else -> null
        }
    }
}
