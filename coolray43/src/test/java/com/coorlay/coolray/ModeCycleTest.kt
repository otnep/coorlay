package com.coorlay.coolray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeCycleTest {
    private var now = 0L
    private val cycle = ModeCycle({ now })

    private fun mode(source: String): Boolean {
        cycle.modePressed()
        now += 2_000
        return cycle.sourceChanged(source).also { now += 10_000 }
    }

    @Test fun carPlayFollowsTheLastOemSource() {
        assertFalse(mode("audio_bt"))
        assertFalse(mode("radio_fm"))
        assertTrue(mode("audio_bt"))
        cycle.carPlayActive()
        assertFalse(mode("radio_fm"))
    }

    @Test fun sourceTappedOnScreenDoesNotTriggerCarPlay() {
        assertFalse(mode("audio_bt"))
        assertFalse(mode("radio_fm"))
        assertFalse(cycle.sourceChanged("audio_bt"))
    }

    @Test fun singleOemSourceAlternatesWithCarPlay() {
        assertFalse(mode("radio_fm"))
        assertTrue(mode("radio_fm"))
    }
}
