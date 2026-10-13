package com.coorlay.coolray

import android.test.ActivityInstrumentationTestCase2
import android.view.SurfaceView
import android.widget.FrameLayout

/** Check the API-18 fullscreen viewport and SurfaceView, not density-derived dp. */
class DisplayResolutionTest : ActivityInstrumentationTestCase2<CoolrayActivity>(CoolrayActivity::class.java) {
    fun testVideoSurfaceMatchesTheAvailableWindowByDefault() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("coolray43", 0)
        val oldAuto = preferences.getBoolean("automaticResolution", true)
        val oldReduced = preferences.getBoolean("reduced", false)
        preferences.edit().putBoolean("automaticResolution", true).putBoolean("reduced", false).commit()
        try {
            val launched = activity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val container = launched.findViewById(android.R.id.content) as FrameLayout
                val root = container.getChildAt(0) as FrameLayout
                val surface = root.getChildAt(0) as SurfaceView
                assertTrue(root.width > 0 && root.height > 0)
                val selected = CarPlayResolution.select(root.width, root.height)
                val fitted = CarPlayResolution.fit(selected.viewport, selected.video)
                assertEquals(fitted.width, surface.width)
                assertEquals(fitted.height, surface.height)
            }
        } finally {
            preferences.edit().putBoolean("automaticResolution", oldAuto).putBoolean("reduced", oldReduced).commit()
        }
    }
    fun testResolutionPolicyLoadsOnDalvikAndRetainsLegacyReducedSize() {
        val selected = CarPlayResolution.select(1280, 720)
        assertEquals(1280, selected.video.width)
        assertEquals(720, selected.video.height)
        val oldSize = CarPlayResolution.select(1920, 640, reduced = true)
        assertEquals(1296, oldSize.video.width)
        assertEquals(432, oldSize.video.height)
        assertTrue(selected.diagnostic().contains("mode=auto"))
    }
}
