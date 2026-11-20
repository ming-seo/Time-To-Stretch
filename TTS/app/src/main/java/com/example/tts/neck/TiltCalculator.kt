package com.example.tts.neck

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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

    /** Screen rotation about its normal: 0° = upright portrait, ±90° = landscape. Unstable when the phone is flat. */
    fun rollDegrees(gx: Float, gy: Float): Double = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble()))

    /**
     * In-plane gravity (gx, gy) expressed in the displayed content's frame (x = content right, y = content up),
     * given the display rotation in quarter turns (Surface.ROTATION_0..3). atan2(x, y) of the result is the
     * content roll: 0° = content upright with respect to gravity, ±90° = content lying sideways (e.g. side-lying).
     */
    fun toContentFrame(gx: Float, gy: Float, rotation: Int): Pair<Double, Double> {
        val theta = Math.toRadians(rotation * 90.0)
        val c = cos(theta)
        val s = sin(theta)
        return (gx * c - gy * s) to (gx * s + gy * c)
    }
}
