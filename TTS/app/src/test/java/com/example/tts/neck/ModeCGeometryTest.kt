package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ModeCGeometryTest {

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    }

    private fun rotY(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    }

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun phoneAboveEyesGivesNegativePitch() {
        // Device rotated about its x axis so the screen normal points down toward the face (phone raised above the eyes).
        val r = ModeCGeometry.headToCamera(front = identity, step = rotX(30.0))
        val (pitch, yaw) = ModeCGeometry.faceAngles(r)
        assertEquals(-30.0, pitch, 1e-9)
        assertEquals(0.0, yaw, 1e-9)
    }

    @Test
    fun phoneOnUsersLeftGivesNegativeYaw() {
        // Device rotated about its y axis so the screen normal points from the user's left (−x) toward the face.
        val r = ModeCGeometry.headToCamera(front = identity, step = rotY(30.0))
        val (pitch, yaw) = ModeCGeometry.faceAngles(r)
        assertEquals(0.0, pitch, 1e-9)
        assertEquals(-30.0, yaw, 1e-9)
    }

    @Test
    fun headFlexionMatchesPhoneTiltMinusPitch() {
        // Phone tilted back 40° (screen up), face pitched 10° down relative to the camera → head bent 50°.
        val tilt = Math.toRadians(40.0)
        val gx = 0f
        val gy = (9.81 * cos(tilt)).toFloat()
        val gz = (9.81 * sin(tilt)).toFloat()
        assertEquals(40.0, TiltCalculator.tiltDegrees(gx, gy, gz), 1e-4)
        val faceDown10 = rotX(10.0) // f = R·(0,0,−1) = (0, sin, −cos): face looks below the camera
        assertEquals(-10.0, ModeCGeometry.faceAngles(faceDown10).first, 1e-9)
        assertEquals(50.0, ModeCGeometry.headFlexion(faceDown10, gx, gy, gz), 1e-4)
        assertEquals(0.0, ModeCGeometry.headFlexion(identity, 0f, 9.81f, 0f), 1e-9)
    }

    @Test
    fun rodriguesIsOrthonormalAndMatchesAxisRotation() {
        val r = ModeCGeometry.rodrigues(doubleArrayOf(Math.toRadians(25.0), 0.0, 0.0))
        val expected = rotX(25.0)
        for (i in 0 until 9) assertEquals(expected[i], r[i], 1e-12)
        val rtr = ModeCGeometry.mul(ModeCGeometry.transpose(r), r)
        for (i in 0 until 9) assertEquals(identity[i], rtr[i], 1e-12)
    }

    @Test
    fun meanRotationOfSameRotationIsThatRotation() {
        val r = ModeCGeometry.mul(rotX(20.0), rotY(-15.0))
        val m = ModeCGeometry.meanRotation(listOf(r, r, r))
        for (i in 0 until 9) assertEquals(r[i], m[i], 1e-12)
    }

    @Test
    fun focalScalesToCroppedBuffer() {
        // 4032×3024 (4:3) array, 3000 px focal → 640×480 buffer: scale 640/4032.
        val array = CameraIntrinsics.ArrayFocal(3000.0, 4032, 3024, "test")
        assertEquals(3000.0 * 640 / 4032, CameraIntrinsics.focalPx(array, 640, 480), 1e-9)
        // 16:9 array cropped to a 4:3 buffer: the height fills, so scale 480/2268.
        val wide = CameraIntrinsics.ArrayFocal(3000.0, 4032, 2268, "test")
        assertEquals(3000.0 * 480 / 2268, CameraIntrinsics.focalPx(wide, 640, 480), 1e-9)
    }
}
