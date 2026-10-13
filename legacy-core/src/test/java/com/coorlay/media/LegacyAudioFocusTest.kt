package com.coorlay.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** MODE switching to radio/USB/Bluetooth and back, as seen through Android audio focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LegacyAudioFocusTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private var lost = 0
    private var regained = 0
    private val focus = LegacyAudioFocus(context, {}, { lost++ }, { regained++ })

    private fun track() = AudioTrack(AudioManager.STREAM_MUSIC, 44_100, AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT, 8192, AudioTrack.MODE_STREAM)

    /** Delivers a focus change the way the OEM player's request would. */
    private fun focusChange(event: Int) = audio.lastAudioFocusRequest.listener.onAudioFocusChange(event)

    @Test fun permanentLossParksMusicButNotPrompts() {
        focus.acquire(track(), music = true)
        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(focus.parked)
        assertEquals(1, lost)
        assertEquals(0f, focus.volume(music = true))
        assertEquals(1f, focus.volume(music = false)) // Navigation prompts and calls keep playing.
        assertSame(audio.lastAudioFocusRequest.listener, audio.lastAbandonedAudioFocusListener)
    }

    @Test fun transientLossesKeepMusicAudible() {
        focus.acquire(track(), music = true)
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(focus.parked)
        assertEquals(1f, focus.volume(music = true))
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(.2f, focus.volume(music = true))
        focusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, focus.volume(music = true))
        assertEquals(0, lost)
    }

    @Test fun audibleMusicReclaimsOnlyAfterTheGracePeriod() {
        focus.acquire(track(), music = true)
        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        // Music still in flight right after the switch must not undo it.
        focus.musicAudible()
        assertTrue(focus.parked)
        ShadowSystemClock.advanceBy(Duration.ofMillis(LegacyAudioFocus.RECLAIM_GRACE_MS + 1))
        focus.musicAudible()
        assertFalse(focus.parked)
        assertEquals(1, regained)
        assertEquals(1f, focus.volume(music = true))
    }

    @Test fun reclaimNeedsAParkedSessionAndGrantedFocus() {
        focus.acquire(track(), music = true)
        assertFalse(focus.reclaim("not parked"))
        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(focus.reclaim("denied"))
        assertTrue(focus.parked)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(focus.reclaim("MODE returned to CarPlay"))
        assertFalse(focus.parked)
        assertEquals(1, regained)
    }

    @Test fun closedSessionIgnoresFocusAndTakesNoNewFocus() {
        focus.acquire(track(), music = true)
        val request = audio.lastAudioFocusRequest
        focus.close()
        // A late LOSS must not pause the next session's iPhone.
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0, lost)
        assertFalse(focus.parked)
        focus.acquire(track(), music = true)
        assertSame(request, audio.lastAudioFocusRequest)
    }

    @Test fun focusIsRequestedOnceWhileHeld() {
        focus.acquire(track(), music = true)
        val first = audio.lastAudioFocusRequest
        focus.acquire(track(), music = false)
        assertSame(first, audio.lastAudioFocusRequest)
        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        // While parked a new prompt track plays without taking the speakers back.
        focus.acquire(track(), music = false)
        assertSame(first, audio.lastAudioFocusRequest)
        assertTrue(focus.reclaim("back"))
        assertNotSame(first, audio.lastAudioFocusRequest)
    }
}
