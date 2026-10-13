package com.coorlay.media

/**
 * Streaming linear resampler for little-endian 16-bit PCM.
 *
 * The Coolray audio HAL opens the built-in microphone only at 8/16 kHz, while the phone
 * negotiates 48 kHz voice; this bridges the capture rate to the negotiated rate. The read
 * position is kept as an exact integer in units of 1/outRate input frames, so long sessions
 * do not drift. Frame -1 is the last frame of the previous block.
 */
internal class PcmResampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    private val previous = IntArray(channels)
    private var position = 0L

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
    }

    fun process(input: ByteArray, length: Int): ByteArray {
        val frameBytes = channels * 2
        val frames = length / frameBytes
        if (frames == 0) return ByteArray(0)
        val out = outRate.toLong()
        val maxFrames = ((frames + 1).toLong() * outRate / inRate + 2).toInt()
        val result = ByteArray(maxFrames * frameBytes)
        var written = 0
        val end = (frames - 1).toLong() * out
        while (position < end) {
            // position >= -out, so shifting by one frame keeps the division non-negative.
            val index = ((position + out) / out).toInt() - 1
            val fraction = position - index.toLong() * out
            for (channel in 0 until channels) {
                val first = sample(input, index, channel)
                val second = sample(input, index + 1, channel)
                val value = first + ((second - first) * fraction / out).toInt()
                result[written++] = value.toByte()
                result[written++] = (value shr 8).toByte()
            }
            position += inRate
        }
        position -= frames.toLong() * out
        for (channel in 0 until channels) previous[channel] = sample(input, frames - 1, channel)
        return if (written == result.size) result else result.copyOf(written)
    }

    private fun sample(input: ByteArray, frame: Int, channel: Int): Int {
        if (frame < 0) return previous[channel]
        val offset = (frame * channels + channel) * 2
        return (input[offset].toInt() and 0xff) or (input[offset + 1].toInt() shl 8)
    }
}
