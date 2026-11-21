package com.example.tts.neck

import kotlin.math.hypot
import kotlin.math.sqrt

/** Face pose relative to the camera from [FacePoseSolver]. [rvec]/[t]: rotation vector and translation (model units, cm). */
class FacePose(
    val rvec: DoubleArray,
    val t: DoubleArray,
    val rotation: DoubleArray,
    val reprojRms: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    /** Camera to eye-midpoint distance (cm). */
    val distCm: Double,
)

/**
 * Perspective-n-point for mode C (design doc §11.6): finds the head→camera rotation and translation that best
 * reproject the face model's landmarks onto the detected ones. Pure logic, no Android dependencies.
 * Landmark lists follow [FaceModel.LANDMARKS] order: 0 left eye, 1 right eye, 2 nose base, ...
 */
object FacePoseSolver {

    const val MIN_LANDMARKS = 6

    /** Above this RMS (px) a solve started from the previous pose is retried from the default starting poses. */
    private const val REINIT_RMS_PX = 3.0

    /** [observed]: pixel (u, v) per landmark or null; [previous]: last frame's pose to start from. Null without enough landmarks. */
    fun solve(
        model: List<DoubleArray>,
        observed: List<DoubleArray?>,
        f: Double,
        cx: Double,
        cy: Double,
        previous: FacePose? = null,
    ): FacePose? {
        val used = observed.indices.filter { observed[it] != null }
        if (used.size < MIN_LANDMARKS || observed[0] == null || observed[1] == null || observed[2] == null) return null

        val residuals = { p: DoubleArray ->
            val r = ModeCGeometry.rodrigues(p)
            val t = doubleArrayOf(p[3], p[4], p[5])
            val out = DoubleArray(2 * used.size)
            used.forEachIndexed { i, k ->
                val uv = ModeCGeometry.project(r, t, model[k], f, cx, cy)
                out[2 * i] = uv[0] - observed[k]!![0]
                out[2 * i + 1] = uv[1] - observed[k]!![1]
            }
            out
        }

        var best: LevenbergMarquardt.Result? = null
        if (previous != null) {
            best = LevenbergMarquardt.minimize(previous.rvec + previous.t, 50, residuals)
        }
        if (best == null || rms(best.cost, used.size) > REINIT_RMS_PX) {
            val t0 = initialTranslation(model, observed[0]!!, observed[1]!!, f, cx, cy)
            for (w in DEFAULT_STARTS) {
                val result = LevenbergMarquardt.minimize(w + t0, 50, residuals)
                if (best == null || result.cost < best.cost) best = result
            }
        }
        val p = best!!.params
        val rotation = ModeCGeometry.rodrigues(p)
        val t = doubleArrayOf(p[3], p[4], p[5])
        val eyeMid = DoubleArray(3) { (model[0][it] + model[1][it]) / 2 }
        val eyeCam = ModeCGeometry.mulVec(rotation, eyeMid).let { DoubleArray(3) { i -> it[i] + t[i] } }
        val rmsPx = rms(best.cost, used.size)
        if (eyeCam[2] <= 0.0 || !rmsPx.isFinite()) return null
        val (pitch, yaw) = ModeCGeometry.faceAngles(rotation)
        return FacePose(p.copyOfRange(0, 3), t, rotation, rmsPx, pitch, yaw, ModeCGeometry.norm(eyeCam))
    }

    /** RMS reprojection error (px) per landmark from a sum of squared residuals over [landmarks] points. */
    fun rms(cost: Double, landmarks: Int) = sqrt(cost / landmarks)

    /** Translation putting the model's eye midpoint on the detected eye midpoint at the depth given by the eye distance. */
    fun initialTranslation(
        model: List<DoubleArray>,
        leftEye: DoubleArray,
        rightEye: DoubleArray,
        f: Double,
        cx: Double,
        cy: Double,
    ): DoubleArray {
        val eyeModel = ModeCGeometry.norm(DoubleArray(3) { model[0][it] - model[1][it] })
        val eyePx = hypot(leftEye[0] - rightEye[0], leftEye[1] - rightEye[1])
        val z = f * eyeModel / eyePx
        val mu = (leftEye[0] + rightEye[0]) / 2
        val mv = (leftEye[1] + rightEye[1]) / 2
        val mid = DoubleArray(3) { (model[0][it] + model[1][it]) / 2 }
        return doubleArrayOf((mu - cx) * z / f - mid[0], (mv - cy) * z / f - mid[1], z - mid[2])
    }

    /** Starting rotations: frontal, and ±0.5 rad about the x (pitch) and y (yaw) axes. */
    private val DEFAULT_STARTS = listOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.0, 0.0),
        doubleArrayOf(-0.5, 0.0, 0.0),
        doubleArrayOf(0.0, 0.5, 0.0),
        doubleArrayOf(0.0, -0.5, 0.0),
    )
}
