package com.coorlay.media

import com.coorlay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallStateTest {
    private fun update(status: Int, uuid: String = "A") = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        string(0, "+70000000000")
        u8(2, status)
        string(4, uuid)
    }

    @Test
    fun incomingCallRingsThenConnectsThenEnds() {
        val state = CarPlayCallState()
        assertTrue(state.accept(update(2)))
        assertEquals(CarPlayCallState.Phase.RINGING, state.phase)
        assertTrue(state.accept(update(4)))
        assertEquals(CarPlayCallState.Phase.ACTIVE, state.phase)
        assertFalse(state.accept(update(4)))
        assertTrue(state.accept(update(0)))
        assertNull(state.phase)
    }

    @Test
    fun ringingSecondCallWinsOverTheActiveOne() {
        val state = CarPlayCallState()
        state.accept(update(4, "A"))
        state.accept(update(2, "B"))
        assertEquals(CarPlayCallState.Phase.RINGING, state.phase)
        state.accept(update(6, "B"))
        assertEquals(CarPlayCallState.Phase.ACTIVE, state.phase)
    }

    @Test
    fun outgoingCallAndOtherMessages() {
        val state = CarPlayCallState()
        assertFalse(state.accept(Iap2Messages.buildRaw(0x5001) { u8(2, 2) }))
        state.accept(update(1))
        assertEquals(CarPlayCallState.Phase.DIALING, state.phase)
        assertTrue(state.clear())
        assertNull(state.phase)
        assertFalse(state.clear())
    }
}
