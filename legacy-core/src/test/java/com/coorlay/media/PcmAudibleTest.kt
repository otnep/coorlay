package com.coorlay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudibleTest {
    @Test fun silenceAndDitherAreNotMusic() {
        assertFalse(pcmAudible(ByteArray(960)))
        assertFalse(pcmAudible(byteArrayOf(40, 0, -40, -1, 64, 0)))
    }

    @Test fun signalInEitherPolarityIsMusic() {
        assertTrue(pcmAudible(byteArrayOf(0, 0, 0, 2)))
        assertTrue(pcmAudible(byteArrayOf(0, 0, 0, -2)))
    }

    @Test fun onlyTheGivenSliceIsChecked() {
        // A loud RTP header before the payload must not count as music, nor a loud tail after it.
        val packet = byteArrayOf(0, 2, 0, 2, 0, 0, 0, 0, 0, 2)
        assertFalse(pcmAudible(packet, 4, offset = 4))
        assertTrue(pcmAudible(packet, 6, offset = 4))
    }
}
