package com.coorlay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelerTest {
    private val rate = 16_000
    /** One 20 ms mono packet of a 440 Hz tone with the given peak. */
    private fun tone(peak: Int, phase: Int = 0) = ByteArray(rate / 50 * 2).also { bytes ->
        for (i in 0 until bytes.size / 2) {
            val v = (peak * Math.sin(2 * Math.PI * 440 * (i + phase) / rate)).toInt()
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
    }
    private fun samples(bytes: ByteArray) = (bytes.indices step 2).map {
        (bytes[it].toInt() and 0xff) or (bytes[it + 1].toInt() shl 8)
    }
    private fun rmsDbfs(bytes: ByteArray): Double {
        val s = samples(bytes)
        return 20 * Math.log10(Math.sqrt(s.sumOf { it.toDouble() * it } / s.size) / 32768.0)
    }
    private fun call() = VoiceLeveler.forAudioType("telephony", rate, 1)!!

    @Test fun quietCallSpeechIsBroughtUpToTheTarget() {
        val leveler = call()
        var out = ByteArray(0)
        // About -36 dBFS RMS, like the quiet calls in the 2026-10-21 log; 5 s is enough for the slow rise.
        repeat(250) { out = tone(723, it * 320).also { packet -> leveler.process(packet, 0, packet.size) } }
        assertEquals(VoiceLeveler.TARGET_DBFS, rmsDbfs(out), 1.0)
    }

    @Test fun gainRisesSlowly() {
        val leveler = call()
        repeat(5) { val p = tone(723, it * 320); leveler.process(p, 0, p.size) }
        // 100 ms at 6 dB/s: at most 0.6 dB above the start (+12 dB).
        assertTrue(VoiceLeveler.toDb(leveler.gain) < 12.7)
    }

    @Test fun loudPeaksAreNeverClipped() {
        val leveler = call()
        val packet = tone(20_000)
        leveler.process(packet, 0, packet.size)
        val peak = samples(packet).maxOf { Math.abs(it) }
        assertTrue("peak=$peak", peak <= VoiceLeveler.CEILING.toInt() + 1)
        assertTrue(peak > 20_000)
        assertTrue(leveler.takeStats().endsWith("limited=1"))
    }

    @Test fun silenceHoldsTheGain() {
        val leveler = call()
        val before = leveler.gain
        repeat(100) { val p = tone(50, it * 320); leveler.process(p, 0, p.size) }
        assertEquals(before, leveler.gain, 1e-9)
    }

    @Test fun onlyTheGivenRangeChanges() {
        val leveler = call()
        val bytes = ByteArray(6).also { it[0] = 100; it[2] = 100; it[4] = 100 }
        leveler.process(bytes, 2, 2)
        assertEquals(listOf(100, 398, 100), samples(bytes))
    }

    @Test fun musicAndPromptsAreLeftAlone() {
        assertNull(VoiceLeveler.forAudioType("media", rate, 2))
        assertNull(VoiceLeveler.forAudioType("default", rate, 1))
    }
}
