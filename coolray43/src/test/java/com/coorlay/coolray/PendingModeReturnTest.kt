package com.coorlay.coolray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MODE rotation together with the delayed return, as CoolrayActivity wires them. */
class PendingModeReturnTest {
    private var now = 0L
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private val returns = PendingModeReturn({ task, delay -> scheduled += now + delay to task }, { task -> scheduled.removeAll { it.second === task } })
    private val cycle = ModeCycle({ now })
    private var returnedToCarPlay = 0

    private fun advance(millis: Long) {
        val until = now + millis
        while (true) {
            val next = scheduled.filter { it.first <= until }.minByOrNull { it.first } ?: break
            scheduled.remove(next); now = next.first; next.second.run()
        }
        now = until
    }

    /** MODE released: a pending return is dropped (the driver moved on), then the firmware switches source. */
    private fun mode(source: String, sourceAfterMillis: Long = 2_000) {
        returns.cancel()
        cycle.modePressed()
        advance(sourceAfterMillis)
        if (cycle.sourceChanged(source)) returns.schedule(1_200) { returnedToCarPlay++; cycle.carPlayActive() }
    }

    @Test fun wrappedRotationReturnsToCarPlayAfterTheDelay() {
        mode("audio_bt"); advance(10_000)
        mode("radio_fm"); advance(10_000)
        mode("audio_bt")
        advance(1_199)
        assertEquals(0, returnedToCarPlay)
        advance(1)
        assertEquals(1, returnedToCarPlay)
    }

    @Test fun modePressedDuringTheDelayKeepsTheChosenSource() {
        mode("radio_fm"); advance(10_000)
        mode("radio_fm") // Wraps: CarPlay is due in 1.2 s.
        advance(500)
        // The driver presses MODE again before the return ran; the firmware switches to Bluetooth.
        mode("audio_bt", sourceAfterMillis = 300)
        advance(10_000)
        assertEquals(0, returnedToCarPlay)
    }

    @Test fun cancelReportsWhetherSomethingWasPending() {
        assertFalse(returns.cancel())
        returns.schedule(1_200) { returnedToCarPlay++ }
        assertTrue(returns.cancel())
        assertFalse(returns.cancel())
        advance(5_000)
        assertEquals(0, returnedToCarPlay)
    }

    @Test fun aNewReturnReplacesTheOldOne() {
        returns.schedule(1_200) { returnedToCarPlay += 10 }
        returns.schedule(1_200) { returnedToCarPlay += 1 }
        advance(5_000)
        assertEquals(1, returnedToCarPlay)
        assertFalse(returns.cancel()) // A return that ran is no longer pending.
    }
}
