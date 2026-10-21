package com.coorlay.coolray

import com.coorlay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OemHardKeysDecoderTest {
    @Test fun shortPressSendsOneTrackChangeOnRelease() {
        val decoder = OemHardKeys.Decoder()
        assertNull(decoder.mediaButton(OemHardKeys.KEY_NEXT, 0))
        assertEquals(CarPlayMediaButton.NEXT, decoder.mediaButton(OemHardKeys.KEY_NEXT, 1))
        assertNull(decoder.mediaButton(OemHardKeys.KEY_PREVIOUS, 0))
        assertEquals(CarPlayMediaButton.PREVIOUS, decoder.mediaButton(OemHardKeys.KEY_PREVIOUS, 1))
        assertNull(decoder.mediaButton(OemHardKeys.KEY_PLAY_PAUSE, 0))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, decoder.mediaButton(OemHardKeys.KEY_PLAY_PAUSE, 1))
    }

    @Test fun longPressDoesNotChangeTrack() {
        val decoder = OemHardKeys.Decoder()
        decoder.mediaButton(OemHardKeys.KEY_NEXT, 0)
        assertNull(decoder.mediaButton(OemHardKeys.KEY_NEXT, 2))
        assertNull(decoder.mediaButton(OemHardKeys.KEY_NEXT, 1))
        assertEquals(CarPlayMediaButton.NEXT, decoder.mediaButton(OemHardKeys.KEY_NEXT, 1))
    }

    @Test fun callKeyActsOnShortReleaseOnly() {
        val decoder = OemHardKeys.Decoder()
        assertEquals(false, decoder.shortPress(OemHardKeys.KEY_CALL, 0))
        assertEquals(true, decoder.shortPress(OemHardKeys.KEY_CALL, 1))
        decoder.shortPress(OemHardKeys.KEY_CALL, 0)
        assertEquals(false, decoder.shortPress(OemHardKeys.KEY_CALL, 2))
        assertEquals(false, decoder.shortPress(OemHardKeys.KEY_CALL, 1))
        assertNull(decoder.mediaButton(OemHardKeys.KEY_CALL, 1))
    }

    @Test fun otherWheelKeysAreIgnored() {
        assertNull(OemHardKeys.Decoder().mediaButton(0x18, 1))
    }
}
