package com.coorlay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFramePoolTest {
    private fun pool() = ScreenFramePool(regularSize = 1_000, regularCount = 3, largeCount = 2, largeStep = 500)

    @Test fun regularFramesUseTheBuffersMadeUpFront() {
        val pool = pool()
        val taken = List(3) { pool.obtain(400) }
        assertEquals(0, pool.created)
        taken.forEach { assertEquals(1_000, it.size) }
        taken.forEach(pool.recycle)
        repeat(30) { pool.recycle(pool.obtain(1_000)) }
        assertEquals(0, pool.created)
    }

    @Test fun aBacklogBeyondThePoolMakesRegularBuffersAndCountsThem() {
        val pool = pool()
        val taken = List(5) { pool.obtain(10) }
        assertEquals(2, pool.created)
        taken.forEach(pool.recycle)
        repeat(3) { pool.obtain(10) }
        assertEquals(2, pool.created)
    }

    @Test fun keyframesKeepTheirOwnBuffersAndLeaveTheRegularOnes() {
        val pool = pool()
        val key = pool.obtain(1_200)
        assertEquals(1_500, key.size)
        assertEquals(1, pool.created)
        pool.recycle(key)
        assertSame(key, pool.obtain(1_100))
        pool.recycle(key)
        List(3) { pool.obtain(900) }.forEach { assertEquals(1_000, it.size) }
        assertEquals(1, pool.created)
    }

    @Test fun aLargerKeyframeReplacesTheSmallerLargeBuffer() {
        val pool = pool()
        pool.recycle(pool.obtain(1_200))
        val bigger = pool.obtain(2_100)
        assertEquals(2_500, bigger.size)
        assertEquals(2, pool.created)
        pool.recycle(bigger)
        // The next keyframe of any size up to the largest seen reuses it.
        assertSame(bigger, pool.obtain(1_300))
        assertTrue(pool.created == 2)
    }
}
