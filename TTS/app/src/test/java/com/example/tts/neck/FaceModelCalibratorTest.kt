package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FaceModelCalibratorTest {

    private val f = 500.0
    private val cx = 240.0
    private val cy = 320.0
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** A face that differs from the average one: deeper nose, longer face, wider cheeks, 5% larger. */
    private val userFace = listOf(
        doubleArrayOf(3.3, 0.0, 0.0), doubleArrayOf(-3.3, 0.0, 0.0), doubleArrayOf(0.0, 4.8, -2.3),
        doubleArrayOf(2.6, 7.8, 0.1), doubleArrayOf(-2.6, 7.8, 0.1), doubleArrayOf(0.0, 9.0, -0.6),
        doubleArrayOf(4.7, 3.9, 1.0), doubleArrayOf(-4.7, 3.9, 1.0),
    )

    private fun rotX(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    }

    private fun rotY(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    }

    /** The 9 steps as device rotations relative to the front step, then head→camera rotations. */
    private val stepRotations = listOf(identity) +
        listOf(22.5, 45.0, -22.5, -45.0).map { ModeCGeometry.headToCamera(identity, rotX(it)) } +
        listOf(22.5, 45.0, -22.5, -45.0).map { ModeCGeometry.headToCamera(identity, rotY(it)) }

    /** Deterministic ±[amp] px noise. */
    private fun noise(i: Int, amp: Double) = amp * sin(i * 12.9898 + 78.233)

    private fun views(face: List<DoubleArray>, rotations: List<DoubleArray>, noiseAmp: Double) =
        rotations.mapIndexed { v, r ->
            val t = doubleArrayOf(0.3 * v - 1.0, -3.0 + 0.2 * v, 28.0 + v)
            CalibrationView(r, face.mapIndexed { i, x ->
                val uv = ModeCGeometry.project(r, t, x, f, cx, cy)
                doubleArrayOf(uv[0] + noise(16 * v + 2 * i, noiseAmp), uv[1] + noise(16 * v + 2 * i + 1, noiseAmp))
            })
        }

    @Test
    fun reconstructedModelGivesAccuratePose() {
        val rec = FaceModelCalibrator.reconstruct(views(userFace, stepRotations, 0.5), f, cx, cy)
        assertTrue(rec.ok)
        assertTrue(rec.reprojRms < 1.0)
        assertEquals(FaceModel.EYE_DISTANCE_CM, ModeCGeometry.norm(DoubleArray(3) { rec.points[0][it] - rec.points[1][it] }), 1e-9)

        // A new pose at a different distance: pitch 30° down, yaw 10°, 40 cm.
        val r = ModeCGeometry.rodrigues(doubleArrayOf(Math.toRadians(30.0), Math.toRadians(-10.0), 0.0))
        val t = doubleArrayOf(1.0, 2.0, 40.0)
        val obs = userFace.map { ModeCGeometry.project(r, t, it, f, cx, cy) }
        val (pitch, yaw) = ModeCGeometry.faceAngles(r)
        val personal = FacePoseSolver.solve(rec.points, obs, f, cx, cy)!!
        val generic = FacePoseSolver.solve(FaceModel.GENERIC_POINTS, obs, f, cx, cy)!!
        assertEquals(pitch, personal.pitchDeg, 1.0)
        assertEquals(yaw, personal.yawDeg, 1.0)
        assertTrue(abs(personal.pitchDeg - pitch) < abs(generic.pitchDeg - pitch))
    }

    @Test
    fun movedHeadFailsReconstruction() {
        // One step labelled 15° off (the head moved): the views no longer agree.
        val wrong = stepRotations.toMutableList()
        wrong[2] = ModeCGeometry.headToCamera(identity, rotX(30.0))
        val obsViews = views(userFace, stepRotations, 0.0)
        val labelled = obsViews.mapIndexed { v, view -> CalibrationView(wrong[v], view.landmarks) }
        val rec = FaceModelCalibrator.reconstruct(labelled, f, cx, cy)
        assertTrue(rec.viewRms[2] > FaceModel.MAX_RMS_PX)
        assertFalse(rec.ok)
    }
}
