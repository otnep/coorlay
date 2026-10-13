package com.coorlay.coolray

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WifiDirectReadinessTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("coolray43", Context.MODE_PRIVATE)
    /** What WifiP2pGroupManager records: true group created, false P2P did not turn on. */
    private fun groupWorked(worked: Boolean) =
        context.getSharedPreferences("wifi-direct", Context.MODE_PRIVATE).edit().putBoolean("groupReady", worked).commit()
    @Test fun stockFirmwareIsNotReady() {
        groupWorked(false)
        assertFalse(WifiDirectReadiness.observed(context, prefs, false))
        assertFalse(WifiDirectReadiness.ready(context, prefs))
    }

    @Test fun p2pSeenEnabledAfterTheRootFixMakesItReady() {
        groupWorked(false) // an attempt before the fix
        assertTrue(WifiDirectReadiness.observed(context, prefs, true))
        assertTrue(WifiDirectReadiness.ready(context, prefs))
        assertFalse("reported once", WifiDirectReadiness.observed(context, prefs, true))
    }

    @Test fun aLaterFailureMakesItNotReadyAgain() {
        WifiDirectReadiness.observed(context, prefs, true)
        groupWorked(false)
        assertFalse(WifiDirectReadiness.ready(context, prefs))
    }

    @Test fun aCreatedGroupIsReady() {
        groupWorked(true)
        assertTrue(WifiDirectReadiness.ready(context, prefs))
    }

    @Test fun wifiDirectIsTheDefaultMode() {
        assertFalse(hotspotMode(prefs))
        prefs.edit().putBoolean(WIFI_DIRECT, false).commit()
        assertTrue(hotspotMode(prefs))
    }
}
