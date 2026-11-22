package com.example.tts.neck

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Guided g() calibration (design doc §11.11): for each target phone tilt, collects θ_C while the user reads the screen
 * naturally and turns it into one (phone tilt, θ_C) pair. A frame is valid when the tilt is within
 * ±[TILT_TOLERANCE_DEG] of the target and the mode C pose has reprojection RMS ≤ [FaceModel.MAX_RMS_PX]. A step
 * collects for [STEP_MS] from its first valid frame and needs [MIN_FRAMES] valid frames, otherwise the window starts
 * over. When a step has no pair for [TIMEOUT_MS], [onFrame] reports [Status.TIMEOUT] with [lastRejects]. Pure logic.
 */
class GCalibration {

    enum class Status { WAITING, COLLECTING, TIMEOUT, STEP_DONE, ALL_DONE }

    class Step(val round: Int, val targetDeg: Double)

    class StepResult(val step: Step, val phoneTilt: Double, val thetaC: Double, val frames: Int)

    /** Frames with the tilt on target since the step (re)started, and why they could not be used. */
    class Rejects(val onTarget: Int, val noFace: Int, val highRms: Int)

    val steps: List<Step> = (1..ROUNDS).flatMap { round -> TARGETS_DEG.map { Step(round, it) } }
    val results = mutableListOf<StepResult>()
    val finished: Boolean get() = results.size == steps.size
    val step: Step get() = steps[results.size]
    val collectedFrames: Int get() = tilts.size
    var lastRejects: Rejects? = null
        private set

    private val tilts = mutableListOf<Double>()
    private val thetas = mutableListOf<Double>()
    private var windowStartMs: Long? = null
    private var stepStartMs: Long? = null
    private var onTarget = 0
    private var noFace = 0
    private var highRms = 0

    /** [tilt]: phone tilt (null without gravity); [thetaC] and [reprojRms]: mode C estimate (null without a pose). */
    fun onFrame(tsMs: Long, tilt: Double?, thetaC: Double?, reprojRms: Double?): Status {
        if (finished) return Status.ALL_DONE
        if (stepStartMs == null) stepStartMs = tsMs
        if (tilt != null && abs(tilt - step.targetDeg) <= TILT_TOLERANCE_DEG) {
            onTarget++
            when {
                thetaC == null || reprojRms == null -> noFace++
                reprojRms > FaceModel.MAX_RMS_PX -> highRms++
                else -> {
                    if (windowStartMs == null) windowStartMs = tsMs
                    tilts += tilt
                    thetas += thetaC
                }
            }
        }
        val start = windowStartMs
        if (start == null) {
            if (tsMs - stepStartMs!! < TIMEOUT_MS) return Status.WAITING
            lastRejects = Rejects(onTarget, noFace, highRms)
            restartStep(tsMs)
            return Status.TIMEOUT
        }
        if (tsMs - start < STEP_MS) return Status.COLLECTING
        if (tilts.size < MIN_FRAMES) {
            tilts.clear()
            thetas.clear()
            windowStartMs = null
            return Status.WAITING
        }
        results += StepResult(step, median(tilts), median(thetas), tilts.size)
        tilts.clear()
        thetas.clear()
        windowStartMs = null
        restartStep(null)
        return if (finished) Status.ALL_DONE else Status.STEP_DONE
    }

    /** g() fitted on the collected pairs only (the previous g() is discarded). */
    fun mapping(): NeckMapping = results.fold(NeckMapping()) { m, r -> m.add(r.phoneTilt, r.thetaC) }

    private fun restartStep(tsMs: Long?) {
        stepStartMs = tsMs
        onTarget = 0
        noFace = 0
        highRms = 0
    }

    companion object {
        val TARGETS_DEG = listOf(10.0, 25.0, 40.0, 55.0, 70.0)
        const val ROUNDS = 2
        const val TILT_TOLERANCE_DEG = 5.0
        const val STEP_MS = 3_000L
        const val MIN_FRAMES = 5
        const val TIMEOUT_MS = 10_000L

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Writes `calib_g_<name>.csv`: one row per step, then the fitted g() with the value in the round column. */
        fun writeCsv(dir: File, name: String, results: List<StepResult>, mapping: NeckMapping) {
            fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: ""
            File(dir, "calib_g_$name.csv").bufferedWriter().use { w ->
                w.write("round,target_tilt_deg,phone_tilt_deg,theta_c_deg,frames\n")
                results.forEach { r ->
                    w.write("${r.step.round},${fmt(r.step.targetDeg)},${fmt(r.phoneTilt)},${fmt(r.thetaC)},${r.frames}\n")
                }
                w.write("slope,${fmt(mapping.slope)},,,\n")
                w.write("intercept,${fmt(mapping.intercept)},,,\n")
                w.write("residual_std,${fmt(mapping.residualStd)},,,\n")
            }
        }
    }
}
