package com.example.tts.neck

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Device tilt from the gravity vector, independent of portrait/landscape.
 * 0° = screen vertical, +90° = screen facing up while flat, negative = screen facing down.
 * Same convention as SmartPose's CATA (|pitch + 90|) for a portrait phone.
 */
object TiltCalculator {
    fun tiltDegrees(gx: Float, gy: Float, gz: Float): Double {
        val horizontal = sqrt((gx * gx + gy * gy).toDouble())
        return Math.toDegrees(atan2(gz.toDouble(), horizontal))
    }
}
