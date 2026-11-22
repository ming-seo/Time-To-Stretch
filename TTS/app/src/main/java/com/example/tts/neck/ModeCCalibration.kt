package com.example.tts.neck

import androidx.annotation.StringRes
import com.example.tts.R
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Mode C calibration steps (design doc §11.4): target face pitch/yaw relative to the camera (°). */
enum class CalibrationStep(@StringRes val label: Int, val pitch: Double, val yaw: Double) {
    FRONT(R.string.step_front, 0.0, 0.0),
    UP_22(R.string.step_up_22, -22.5, 0.0),
    UP_45(R.string.step_up_45, -45.0, 0.0),
    DOWN_22(R.string.step_down_22, 22.5, 0.0),
    DOWN_45(R.string.step_down_45, 45.0, 0.0),
    LEFT_22(R.string.step_left_22, 0.0, -22.5),
    LEFT_45(R.string.step_left_45, 0.0, -45.0),
    RIGHT_22(R.string.step_right_22, 0.0, 22.5),
    RIGHT_45(R.string.step_right_45, 0.0, 45.0),
}

/**
 * Runs the 9 calibration steps over camera frames. A frame is accepted when all mode C landmarks are detected,
 * the face box center is within ±[CENTER_TOLERANCE] of the image center, and the face angle is within
 * ±[ANGLE_TOLERANCE_DEG] of the step's target (front step: |phone tilt| ≤ [ANGLE_TOLERANCE_DEG]). A step collects
 * for [STEP_MS] from its first accepted frame and needs [MIN_FRAMES] frames, otherwise it starts over. Pure logic.
 */
class ModeCCalibration {

    enum class Status { WAITING, COLLECTING, RETRY, STEP_DONE, ALL_DONE }

    /** Result of one step; [view] feeds [FaceModelCalibrator.reconstruct]. Angles in degrees. */
    class StepResult(
        val step: CalibrationStep,
        val view: CalibrationView,
        val frames: Int,
        val labelPitch: Double,
        val labelYaw: Double,
        val phoneTilt: Double,
        val facePitch: Double,
    )

    private class Sample(
        val landmarks: List<DoubleArray>,
        val deviceRotation: DoubleArray,
        val gravity: FloatArray,
        val tilt: Double,
        val facePitch: Double,
    )

    val results = mutableListOf<StepResult>()
    private val samples = mutableListOf<Sample>()
    private var windowStartMs: Long? = null
    /** Device→world rotation M_0 of the front step. */
    private var frontRotation: DoubleArray? = null
    /** Mean gravity of the front step, for α_ref. */
    private var frontGravity: FloatArray? = null

    val finished: Boolean get() = results.size == CalibrationStep.entries.size
    val step: CalibrationStep get() = CalibrationStep.entries[results.size]
    val collectedFrames: Int get() = samples.size

    /** Head flexion at the front step (taken as upright); null until the front step is done. */
    val alphaRefDeg: Double?
        get() = frontGravity?.let { ModeCGeometry.headFlexion(IDENTITY, it[0], it[1], it[2]) }

    /** Face (pitch, yaw) relative to the camera from the device rotation; null until the front step is done. */
    fun label(deviceRotation: DoubleArray): Pair<Double, Double>? =
        frontRotation?.let { ModeCGeometry.faceAngles(ModeCGeometry.headToCamera(it, deviceRotation)) }

    /**
     * [landmarks]: pixel positions in [FaceModel.LANDMARKS] order (null = not detected); [faceCenter]: face box center;
     * [deviceRotation]: device→world matrix from TYPE_GAME_ROTATION_VECTOR; [gravity]: TYPE_GRAVITY vector.
     */
    fun onFrame(
        tsMs: Long,
        landmarks: List<DoubleArray?>,
        faceCenter: DoubleArray?,
        imageWidth: Int,
        imageHeight: Int,
        deviceRotation: DoubleArray?,
        gravity: FloatArray?,
        facePitch: Double?,
    ): Status {
        if (finished) return Status.ALL_DONE
        if (landmarks.all { it != null } && faceCenter != null && deviceRotation != null && gravity != null && facePitch != null &&
            abs(faceCenter[0] - imageWidth / 2.0) <= CENTER_TOLERANCE * imageWidth &&
            abs(faceCenter[1] - imageHeight / 2.0) <= CENTER_TOLERANCE * imageHeight
        ) {
            val tilt = TiltCalculator.tiltDegrees(gravity[0], gravity[1], gravity[2])
            val onTarget = if (step == CalibrationStep.FRONT) {
                abs(tilt) <= ANGLE_TOLERANCE_DEG
            } else {
                val (pitch, yaw) = label(deviceRotation)!!
                abs(pitch - step.pitch) <= ANGLE_TOLERANCE_DEG && abs(yaw - step.yaw) <= ANGLE_TOLERANCE_DEG
            }
            if (onTarget) {
                if (windowStartMs == null) windowStartMs = tsMs
                samples += Sample(landmarks.map { it!! }, deviceRotation, gravity, tilt, facePitch)
            }
        }
        val start = windowStartMs ?: return Status.WAITING
        if (tsMs - start < STEP_MS) return Status.COLLECTING
        if (samples.size < MIN_FRAMES) {
            samples.clear()
            windowStartMs = null
            return Status.RETRY
        }
        finishStep()
        return if (finished) Status.ALL_DONE else Status.STEP_DONE
    }

    private fun finishStep() {
        val meanRotation = ModeCGeometry.meanRotation(samples.map { it.deviceRotation })
        if (step == CalibrationStep.FRONT) {
            frontRotation = meanRotation
            frontGravity = FloatArray(3) { i -> samples.map { it.gravity[i] }.average().toFloat() }
        }
        val rotation = ModeCGeometry.headToCamera(frontRotation!!, meanRotation)
        val medians = (0 until FaceModel.LANDMARKS.size).map { k ->
            doubleArrayOf(median(samples.map { it.landmarks[k][0] }), median(samples.map { it.landmarks[k][1] }))
        }
        val (pitch, yaw) = ModeCGeometry.faceAngles(rotation)
        results += StepResult(
            step, CalibrationView(rotation, medians), samples.size, pitch, yaw,
            median(samples.map { it.tilt }), median(samples.map { it.facePitch }),
        )
        samples.clear()
        windowStartMs = null
    }

    companion object {
        const val ANGLE_TOLERANCE_DEG = 5.0
        const val CENTER_TOLERANCE = 0.15
        const val STEP_MS = 2_000L
        const val MIN_FRAMES = 5
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /**
         * Writes `calib_c_<name>.csv` (one row per step) and `calib_c_<name>_model.csv` (landmark 3D positions in cm,
         * then summary rows with the value in the x column) for offline analysis (design doc §11.5, §11.7).
         */
        fun writeCsv(dir: File, name: String, results: List<StepResult>, rec: Reconstruction, alphaRefDeg: Double, focalPx: Double) {
            fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
            val landmarkCols = FaceModel.LANDMARKS.joinToString(",") { "${it}_x,${it}_y" }
            File(dir, "calib_c_$name.csv").bufferedWriter().use { w ->
                w.write("step,target_pitch_deg,target_yaw_deg,label_pitch_deg,label_yaw_deg,phone_tilt_deg,face_pitch_deg,frames,view_reproj_px,$landmarkCols\n")
                results.forEachIndexed { i, r ->
                    val cells = listOf(
                        r.step.name, fmt(r.step.pitch), fmt(r.step.yaw), fmt(r.labelPitch), fmt(r.labelYaw),
                        fmt(r.phoneTilt), fmt(r.facePitch), r.frames.toString(), fmt(rec.viewRms[i]),
                    ) + r.view.landmarks.flatMap { listOf(fmt(it[0]), fmt(it[1])) }
                    w.write(cells.joinToString(",") + "\n")
                }
            }
            File(dir, "calib_c_${name}_model.csv").bufferedWriter().use { w ->
                w.write("name,x,y,z\n")
                FaceModel.LANDMARKS.forEachIndexed { k, n ->
                    w.write("$n,${fmt(rec.points[k][0])},${fmt(rec.points[k][1])},${fmt(rec.points[k][2])}\n")
                }
                w.write("alpha_ref_deg,${fmt(alphaRefDeg)},,\n")
                w.write("reproj_rms_px,${fmt(rec.reprojRms)},,\n")
                w.write("max_view_rms_px,${fmt(rec.viewRms.max())},,\n")
                w.write("focal_px,${fmt(focalPx)},,\n")
                w.write("ok,${rec.ok},,\n")
            }
        }
    }
}
