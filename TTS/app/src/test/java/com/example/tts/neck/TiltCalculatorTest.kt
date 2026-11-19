package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan2

class TiltCalculatorTest {

    private val g = 9.81f

    private fun contentRoll(gx: Float, gy: Float, rotation: Int): Double {
        val (x, y) = TiltCalculator.toContentFrame(gx, gy, rotation)
        return Math.toDegrees(atan2(x, y))
    }

    @Test
    fun contentUprightWhenDisplayFollowsGravity() {
        assertEquals(0.0, contentRoll(0f, g, 0), 1e-6)   // portrait
        assertEquals(0.0, contentRoll(g, 0f, 1), 1e-6)   // turned counter-clockwise, ROTATION_90
        assertEquals(0.0, contentRoll(-g, 0f, 3), 1e-6)  // turned clockwise, ROTATION_270
    }

    @Test
    fun contentSidewaysWhenRotationIsLocked() {
        // Side-lying with portrait locked: gravity along the phone's x axis, display not rotated.
        assertEquals(90.0, contentRoll(g, 0f, 0), 1e-6)
        assertEquals(-90.0, contentRoll(-g, 0f, 0), 1e-6)
        // Side-lying with a landscape video: phone long axis vertical, display rotated.
        assertEquals(90.0, Math.abs(contentRoll(0f, g, 1)), 1e-6)
    }
}
