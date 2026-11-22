package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ModeCCalibrationTest {

    private val f = 500.0
    private val w = 480
    private val h = 640
    private val upright = floatArrayOf(0f, 9.81f, 0f)

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    }

    private fun rotY(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    }

    /** Device rotation that puts the face at [step]'s target, as the user holding the phone there would. */
    private fun deviceRotation(step: CalibrationStep) = when {
        step.pitch != 0.0 -> rotX(-step.pitch)
        step.yaw != 0.0 -> rotY(-step.yaw)
        else -> rotX(0.0)
    }

    /** Gravity of a device rotated by [m] from upright (world up = +y of the front device). */
    private fun gravity(m: DoubleArray): FloatArray {
        val up = ModeCGeometry.mulVec(ModeCGeometry.transpose(m), doubleArrayOf(0.0, 9.81, 0.0))
        return floatArrayOf(up[0].toFloat(), up[1].toFloat(), up[2].toFloat())
    }

    private fun frame(cal: ModeCCalibration, ts: Long, m: DoubleArray, centerX: Double = w / 2.0): ModeCCalibration.Status {
        val r = ModeCGeometry.headToCamera(rotX(0.0), m)
        val t = doubleArrayOf(0.0, -3.0, 30.0)
        val lm = FaceModel.GENERIC_POINTS.map { ModeCGeometry.project(r, t, it, f, w / 2.0, h / 2.0) }
        return cal.onFrame(ts, lm, doubleArrayOf(centerX, h / 2.0), w, h, m, gravity(m), 0.0)
    }

    @Test
    fun walksThroughAllStepsWithLabelsAtTargets() {
        val cal = ModeCCalibration()
        var ts = 0L
        for (step in CalibrationStep.entries) {
            assertEquals(step, cal.step)
            val m = deviceRotation(step)
            var status: ModeCCalibration.Status
            do {
                status = frame(cal, ts, m)
                ts += 200
            } while (status == ModeCCalibration.Status.COLLECTING || status == ModeCCalibration.Status.WAITING)
            assertTrue(status == ModeCCalibration.Status.STEP_DONE || status == ModeCCalibration.Status.ALL_DONE)
        }
        assertTrue(cal.finished)
        assertEquals(0.0, cal.alphaRefDeg!!, 1e-6)
        for (r in cal.results) {
            assertEquals(r.step.pitch, r.labelPitch, 1e-6)
            assertEquals(r.step.yaw, r.labelYaw, 1e-6)
            assertTrue(r.frames >= ModeCCalibration.MIN_FRAMES)
        }
        assertEquals(-22.5, cal.results[1].phoneTilt, 1e-4)
    }

    @Test
    fun offCenterOrOffTargetFramesAreNotCollected() {
        val cal = ModeCCalibration()
        assertEquals(ModeCCalibration.Status.WAITING, frame(cal, 0, rotX(0.0), centerX = w * 0.8))
        assertEquals(ModeCCalibration.Status.WAITING, frame(cal, 200, rotX(-10.0)))
        assertEquals(ModeCCalibration.Status.COLLECTING, frame(cal, 400, rotX(0.0)))
        // Only 2 accepted frames when the 2 s window ends → the step starts over.
        assertEquals(ModeCCalibration.Status.COLLECTING, frame(cal, 600, rotX(0.0)))
        assertEquals(ModeCCalibration.Status.RETRY, frame(cal, 2_400, rotX(-10.0)))
        assertEquals(CalibrationStep.FRONT, cal.step)
    }
}
