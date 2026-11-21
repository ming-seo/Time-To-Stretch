package com.example.tts.neck

import android.content.SharedPreferences
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Per-user 3D face model for mode C (design doc §11.5): landmark positions in head axes (cm), in [LANDMARKS] order,
 * and α_ref, the head flexion measured at the front calibration step (taken as upright).
 */
class FaceModel(val points: List<DoubleArray>, val alphaRefDeg: Double, val reprojRms: Double) {

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_POINTS, points.flatMap { it.toList() }.joinToString(","))
            .putLong(KEY_ALPHA_REF, alphaRefDeg.toRawBits())
            .putLong(KEY_RMS, reprojRms.toRawBits())
            .apply()
    }

    companion object {
        /** ML Kit landmarks used by mode C, in model order ([FaceFeatures.modeCLandmarks]). */
        val LANDMARKS = listOf("eye_l", "eye_r", "nose_base", "mouth_l", "mouth_r", "mouth_b", "cheek_l", "cheek_r")

        /**
         * Average face (cm) used as the starting point and weak prior of the reconstruction. Head axes: x image right
         * (the subject's left eye appears on the right of the unmirrored front camera image), y down, z away from the camera.
         */
        val GENERIC_POINTS = listOf(
            doubleArrayOf(3.15, 0.0, 0.0), doubleArrayOf(-3.15, 0.0, 0.0), doubleArrayOf(0.0, 4.2, -1.5),
            doubleArrayOf(2.4, 7.0, 0.3), doubleArrayOf(-2.4, 7.0, 0.3), doubleArrayOf(0.0, 8.2, -0.3),
            doubleArrayOf(4.2, 3.6, 1.2), doubleArrayOf(-4.2, 3.6, 1.2),
        )

        /** Assumed eye distance (cm); fixes the model scale for distances in cm. Angles do not depend on it. */
        const val EYE_DISTANCE_CM = 6.3

        /** Reconstruction fails when any view's RMS reprojection error (px) is above this. */
        const val MAX_RMS_PX = 5.0

        /** Weight of the average-face prior (px per cm of deviation). */
        const val PRIOR_WEIGHT = 1.0

        private const val KEY_POINTS = "c_model_points"
        private const val KEY_ALPHA_REF = "c_alpha_ref"
        private const val KEY_RMS = "c_model_rms"

        fun load(prefs: SharedPreferences): FaceModel? {
            val values = prefs.getString(KEY_POINTS, null)?.split(",")?.map { it.toDouble() } ?: return null
            if (values.size != 3 * LANDMARKS.size) return null
            return FaceModel(
                points = values.chunked(3).map { it.toDoubleArray() },
                alphaRefDeg = Double.fromBits(prefs.getLong(KEY_ALPHA_REF, 0L)),
                reprojRms = Double.fromBits(prefs.getLong(KEY_RMS, 0L)),
            )
        }
    }
}

/** One calibration step: head→camera rotation from the sensors and the median pixel position of every landmark. */
class CalibrationView(val rotation: DoubleArray, val landmarks: List<DoubleArray>)

/**
 * Reconstructed landmark positions (cm) and reprojection errors (px), overall and per view. Judged on the worst
 * view: one step taken with the head moved barely raises the overall RMS but stands out in its own view.
 */
class Reconstruction(val points: List<DoubleArray>, val reprojRms: Double, val viewRms: List<Double>) {
    val ok: Boolean get() = viewRms.max() <= FaceModel.MAX_RMS_PX
}

/**
 * Recovers the user's 3D landmark layout from the calibration views (design doc §11.5). Rotations are fixed to
 * the sensor values; landmark positions and per-view translations are fitted by Levenberg–Marquardt to the
 * detected pixel positions, with a weak prior toward [FaceModel.GENERIC_POINTS]. Pure logic.
 */
object FaceModelCalibrator {

    /** [views][0] must be the front step (rotation = identity). */
    fun reconstruct(
        views: List<CalibrationView>,
        f: Double,
        cx: Double,
        cy: Double,
        priorWeight: Double = FaceModel.PRIOR_WEIGHT,
    ): Reconstruction {
        val k = FaceModel.LANDMARKS.size
        val front = views[0].landmarks
        // Mirror the prior if the left eye appears on the left of the image (mirrored buffer).
        val sign = if (front[0][0] >= front[1][0]) 1.0 else -1.0
        val prior = FaceModel.GENERIC_POINTS.map { doubleArrayOf(sign * it[0], it[1], it[2]) }

        val init = DoubleArray(3 * k + 3 * views.size)
        prior.forEachIndexed { i, p -> p.copyInto(init, 3 * i) }
        views.forEachIndexed { v, view -> initialTranslation(prior, view, f, cx, cy).copyInto(init, 3 * k + 3 * v) }

        val residuals = { p: DoubleArray ->
            val out = DoubleArray(2 * k * views.size + 3 * k)
            var o = 0
            views.forEachIndexed { v, view ->
                val t = doubleArrayOf(p[3 * k + 3 * v], p[3 * k + 3 * v + 1], p[3 * k + 3 * v + 2])
                for (i in 0 until k) {
                    val uv = ModeCGeometry.project(view.rotation, t, doubleArrayOf(p[3 * i], p[3 * i + 1], p[3 * i + 2]), f, cx, cy)
                    out[o++] = uv[0] - view.landmarks[i][0]
                    out[o++] = uv[1] - view.landmarks[i][1]
                }
            }
            for (i in 0 until 3 * k) out[o++] = priorWeight * (p[i] - prior[i / 3][i % 3])
            out
        }
        val p = LevenbergMarquardt.minimize(init, 200, residuals).params
        val r = residuals(p)

        val viewRms = views.indices.map { v ->
            sqrt((0 until 2 * k).sumOf { r[2 * k * v + it].let { e -> e * e } } / k)
        }
        val totalRms = sqrt((0 until 2 * k * views.size).sumOf { r[it] * r[it] } / (k * views.size))

        val points = (0 until k).map { i -> doubleArrayOf(p[3 * i], p[3 * i + 1], p[3 * i + 2]) }
        val eyeDist = ModeCGeometry.norm(DoubleArray(3) { points[0][it] - points[1][it] })
        val scale = FaceModel.EYE_DISTANCE_CM / eyeDist
        return Reconstruction(points.map { pt -> DoubleArray(3) { pt[it] * scale } }, totalRms, viewRms)
    }

    /** Puts the model's eye midpoint on the detected one, at the depth given by the rotated eye vector's image length. */
    private fun initialTranslation(model: List<DoubleArray>, view: CalibrationView, f: Double, cx: Double, cy: Double): DoubleArray {
        val le = view.landmarks[0]
        val re = view.landmarks[1]
        val eyeVec = ModeCGeometry.mulVec(view.rotation, DoubleArray(3) { model[0][it] - model[1][it] })
        val z = f * hypot(eyeVec[0], eyeVec[1]) / hypot(le[0] - re[0], le[1] - re[1])
        val mid = ModeCGeometry.mulVec(view.rotation, DoubleArray(3) { (model[0][it] + model[1][it]) / 2 })
        return doubleArrayOf(((le[0] + re[0]) / 2 - cx) * z / f - mid[0], ((le[1] + re[1]) / 2 - cy) * z / f - mid[1], z - mid[2])
    }
}
