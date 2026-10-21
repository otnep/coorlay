package com.coorlay.airplay

import org.junit.Assert.*
import org.junit.Test

class ScreenProcessingStatsTest {
    @Test fun reportsSeparateTimingsAndResetsTheNextWindow() {
        var time = 0L
        val lines = mutableListOf<String>()
        var created = 3
        val stats = ScreenProcessingStats({ lines += it }, { time }, { created })
        stats.frame(1000, 2_000_000, 100_000, 300_000)
        assertTrue(lines.isEmpty())
        time = 5_000_000_000L
        created = 5
        stats.frame(2000, 4_000_000, 200_000, 400_000)
        assertEquals("Video processing frames=2 bytes=3000 decryptAvgUs=3000 decryptMaxUs=4000 convertMaxUs=200 deliveryMaxUs=400 poolNew=2", lines.single())
        time = 10_000_000_000L
        stats.frame(500, 1_000_000, 50_000, 100_000)
        assertEquals("Video processing frames=1 bytes=500 decryptAvgUs=1000 decryptMaxUs=1000 convertMaxUs=50 deliveryMaxUs=100 poolNew=0", lines.last())
    }
}
