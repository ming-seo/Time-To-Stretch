package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePoseSolverTest {

    private val f = 500.0
    private val cx = 240.0
    private val cy = 320.0
    /** Eyes, nose base, mouth corners and bottom, cheeks (cm, head axes: x image right, y down, z away from the camera). */
    private val model = listOf(
        doubleArrayOf(3.15, 0.0, 0.0), doubleArrayOf(-3.15, 0.0, 0.0), doubleArrayOf(0.0, 4.2, -1.5),
        doubleArrayOf(2.4, 7.0, 0.3), doubleArrayOf(-2.4, 7.0, 0.3), doubleArrayOf(0.0, 8.2, -0.3),
        doubleArrayOf(4.2, 3.6, 1.2), doubleArrayOf(-4.2, 3.6, 1.2),
    )

    private fun observe(r: DoubleArray, t: DoubleArray) = model.map { ModeCGeometry.project(r, t, it, f, cx, cy) }

    @Test
    fun recoversPitchYawAndDistance() {
        for ((rvec, t) in listOf(
            doubleArrayOf(0.0, 0.0, 0.0) to doubleArrayOf(0.0, 0.0, 30.0),
            doubleArrayOf(0.6, 0.0, 0.0) to doubleArrayOf(1.0, -2.0, 35.0),
            doubleArrayOf(-0.7, 0.2, 0.05) to doubleArrayOf(-2.0, 3.0, 25.0),
            doubleArrayOf(0.1, -0.7, 0.0) to doubleArrayOf(0.0, 1.0, 45.0),
        )) {
            val r = ModeCGeometry.rodrigues(rvec)
            val (pitch, yaw) = ModeCGeometry.faceAngles(r)
            val pose = FacePoseSolver.solve(model, observe(r, t), f, cx, cy)!!
            assertEquals(pitch, pose.pitchDeg, 1e-3)
            assertEquals(yaw, pose.yawDeg, 1e-3)
            assertEquals(ModeCGeometry.norm(t), pose.distCm, 1e-2)
            assertEquals(0.0, pose.reprojRms, 1e-3)
        }
    }

    @Test
    fun startsFromPreviousPoseAndHandlesMissingLandmarks() {
        val r = ModeCGeometry.rodrigues(doubleArrayOf(0.4, 0.1, 0.0))
        val t = doubleArrayOf(0.5, 0.5, 30.0)
        val first = FacePoseSolver.solve(model, observe(r, t), f, cx, cy)!!
        val partial = observe(r, t).mapIndexed { i, uv -> if (i == 6 || i == 7) null else uv }
        val second = FacePoseSolver.solve(model, partial, f, cx, cy, previous = first)!!
        assertEquals(ModeCGeometry.faceAngles(r).first, second.pitchDeg, 1e-3)
    }

    @Test
    fun needsEyesNoseAndSixLandmarks() {
        val r = ModeCGeometry.rodrigues(doubleArrayOf(0.0, 0.0, 0.0))
        val obs = observe(r, doubleArrayOf(0.0, 0.0, 30.0))
        assertNull(FacePoseSolver.solve(model, obs.mapIndexed { i, uv -> if (i == 2) null else uv }, f, cx, cy))
        assertNull(FacePoseSolver.solve(model, obs.mapIndexed { i, uv -> if (i >= 5) null else uv }, f, cx, cy))
    }
}
