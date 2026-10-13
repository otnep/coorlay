package com.coorlay.coolray

import org.junit.Assert.*
import org.junit.Test

class CarPlayResolutionTest {
    @Test fun factoryScreenRetainsItsWorkingCanvas() {
        assertEquals(CarPlayResolution.Size(1920, 640), CarPlayResolution.select(1920, 640).video)
    }
    @Test fun commonScreensUseTheirOwnPixelDimensions() {
        for ((width, height) in listOf(800 to 480, 1024 to 600, 1280 to 720, 1920 to 1080)) {
            val selected = CarPlayResolution.select(width, height)
            assertEquals(CarPlayResolution.Size(width, height), selected.video)
            assertEquals(selected.viewport, CarPlayResolution.fit(selected.viewport, selected.video))
        }
    }
    @Test fun fourKIsLimitedToFullHd() {
        assertEquals(CarPlayResolution.Size(1920, 1080), CarPlayResolution.select(3840, 2160).video)
    }
    @Test fun widerScreensRetainTheirAspectWhenLimited() {
        assertEquals(CarPlayResolution.Size(1920, 640), CarPlayResolution.select(3840, 1280).video)
    }
    @Test fun portraitViewportIsNotReinterpretedAsLandscape() {
        assertEquals(CarPlayResolution.Size(606, 1080), CarPlayResolution.select(1080, 1920).video)
    }
    @Test fun oddPixelDimensionsAreAlignedForH264() {
        assertEquals(CarPlayResolution.Size(1364, 766), CarPlayResolution.select(1365, 767).video)
    }
    @Test fun explicitFixedModeRetainsTheFactoryFallbackOnOtherScreens() {
        val selected = CarPlayResolution.select(1280, 720, automatic = false)
        assertEquals(CarPlayResolution.FACTORY, selected.video)
        assertEquals(CarPlayResolution.Size(1280, 426), CarPlayResolution.fit(selected.viewport, selected.video))
    }
    @Test fun reducedModeRetainsFactoryCompatibilityAndScalesOtherScreens() {
        assertEquals(CarPlayResolution.Size(1296, 432), CarPlayResolution.select(1920, 640, reduced = true).video)
        assertEquals(CarPlayResolution.Size(864, 486), CarPlayResolution.select(1280, 720, reduced = true).video)
        assertEquals(CarPlayResolution.Size(540, 324), CarPlayResolution.select(800, 480, reduced = true).video)
        assertEquals(CarPlayResolution.Size(1296, 432), CarPlayResolution.select(1280, 720, automatic = false, reduced = true).video)
    }
    @Test fun unavailableViewportUsesKnownFactoryDimensions() {
        for ((width, height) in listOf(0 to 0, 0 to 720, 1280 to -1, 1 to 480))
            assertEquals(CarPlayResolution.FACTORY, CarPlayResolution.select(width, height).video)
    }
    @Test fun fittingUsesTheNegotiatedCanvasWhenTheWindowChanges() {
        val canvas = CarPlayResolution.select(1920, 640).video
        assertEquals(CarPlayResolution.Size(1920, 640), CarPlayResolution.fit(CarPlayResolution.Size(1920, 1080), canvas))
        assertEquals(CarPlayResolution.Size(800, 266), CarPlayResolution.fit(CarPlayResolution.Size(800, 480), canvas))
    }
    @Test fun largeInputsDoNotOverflowAndNeverRequestMoreThanTheCeiling() {
        val selected = CarPlayResolution.select(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(CarPlayResolution.Size(1080, 1080), selected.video)
        assertEquals(selected.viewport, CarPlayResolution.fit(selected.viewport, selected.video))
    }
}
