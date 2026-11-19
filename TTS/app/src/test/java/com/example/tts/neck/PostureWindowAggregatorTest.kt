package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostureWindowAggregatorTest {

    /** Fills one in-use, moving window with [tilt] and content-frame gravity at [roll]° and closes it. */
    private fun PostureWindowAggregator.window(t: Long, tilt: Double, roll: Double): Pair<WindowResult, CycleResult?> {
        startWindow(t, screenInUse = true)
        addTilt(tilt)
        val r = Math.toRadians(roll)
        addContentGravity(Math.sin(r), Math.cos(r), rotation = 0)
        addLinearAccMagnitude(1.0)
        return closeWindow(t + 5_000, screenInUseNow = true)
    }

    @Test
    fun classifiesWindows() {
        val agg = PostureWindowAggregator(windowsPerCycle = 100)
        assertEquals(WindowPosture.TRUNK_UPRIGHT, agg.window(0, 50.0, 0.0).first.posture)
        assertEquals(WindowPosture.SUPINE, agg.window(0, -20.0, 0.0).first.posture)
        assertEquals(WindowPosture.SIDE_LYING, agg.window(0, 10.0, 90.0).first.posture)
        assertEquals(WindowPosture.SIDE_LYING, agg.window(0, -10.0, -100.0).first.posture)
        // Content roll is not trusted on a nearly flat phone.
        assertEquals(WindowPosture.TRUNK_UPRIGHT, agg.window(0, 70.0, 90.0).first.posture)
        // Upside-down content is outside the side-lying band.
        assertEquals(WindowPosture.TRUNK_UPRIGHT, agg.window(0, 10.0, 170.0).first.posture)
    }

    @Test
    fun invalidWindowHasNoPosture() {
        val agg = PostureWindowAggregator()
        agg.startWindow(0, screenInUse = false)
        agg.addTilt(-40.0)
        assertNull(agg.closeWindow(5_000, screenInUseNow = false).first.posture)
    }

    @Test
    fun cataUsesTrunkUprightWindowsOnly() {
        val agg = PostureWindowAggregator(windowsPerCycle = 4)
        agg.window(0, 60.0, 0.0)
        agg.window(5_000, 40.0, 0.0)
        agg.window(10_000, -60.0, 0.0)
        val cycle = agg.window(15_000, 0.0, 90.0).second!!
        assertEquals(50.0, cycle.cata!!, 1e-9)
        assertEquals(4, cycle.validWindowCount)
        assertEquals(1, cycle.supineWindowCount)
        assertEquals(1, cycle.sideLyingWindowCount)
    }
}
