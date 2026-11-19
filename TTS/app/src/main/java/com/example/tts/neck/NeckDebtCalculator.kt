package com.example.tts.neck

import kotlin.math.max

/** Mode A posture state of one 60-second cycle (design doc §4.2). */
enum class NeckState { BENT, UPRIGHT, NOT_IN_USE, STATIC_DEVICE, SUPINE_LIKE, SIDE_LYING_LIKE }

/**
 * Judges each cycle and updates the neck debt D_n (design doc §4.6). Pure logic, no Android dependencies.
 * With a neck angle for the cycle (camera burst, or g(CATA) with or without the burst residual), a cycle is
 * BENT when it is ≥ [bentNeckDeg] and adds the Hansraj load weight; without one, the SmartPose default
 * (CATA ≥ 56°) adds [bentIncrement]. When at least [lyingMinFraction] of the valid windows are lying (SUPINE or
 * SIDE_LYING), the cycle is SUPINE_LIKE or SIDE_LYING_LIKE (whichever has more windows; SUPINE_LIKE on a tie) and
 * D_n is left unchanged; otherwise CATA comes from the TRUNK_UPRIGHT windows only.
 */
class NeckDebtCalculator(
    private val minValidWindows: Int = 6,
    private val bentCataDeg: Double = 56.0,
    private val bentNeckDeg: Double = 15.0,
    private val bentIncrement: Double = 1.0,
    private val uprightDecrement: Double = 0.25,
    private val restDecrement: Double = 0.5,
    private val lyingMinFraction: Double = 0.5,
) {

    /** [neckDeg] is the cycle's neck angle from [NeckEstimator], or null when there is none (then CATA is used). */
    fun judge(cycle: CycleResult, neckDeg: Double? = null): NeckState {
        val cata = cycle.cata
        val lying = cycle.supineWindowCount + cycle.sideLyingWindowCount
        return when {
            cycle.validWindowCount < minValidWindows ->
                if (cycle.validWindowCount + cycle.staticWindowCount >= minValidWindows) {
                    NeckState.STATIC_DEVICE
                } else {
                    NeckState.NOT_IN_USE
                }
            lying >= lyingMinFraction * cycle.validWindowCount ->
                if (cycle.sideLyingWindowCount > cycle.supineWindowCount) NeckState.SIDE_LYING_LIKE else NeckState.SUPINE_LIKE
            cata == null -> NeckState.NOT_IN_USE
            neckDeg != null -> if (neckDeg >= bentNeckDeg) NeckState.BENT else NeckState.UPRIGHT
            cata >= bentCataDeg -> NeckState.BENT
            else -> NeckState.UPRIGHT
        }
    }

    /** [neckDeg] is the neck angle used in [judge]; BENT adds w(neckDeg) with it, [bentIncrement] without. */
    fun update(debt: Double, state: NeckState, neckDeg: Double? = null): Double = when (state) {
        NeckState.BENT -> debt + (neckDeg?.let { loadWeight(it) } ?: bentIncrement)
        NeckState.UPRIGHT -> max(0.0, debt - uprightDecrement)
        NeckState.NOT_IN_USE -> max(0.0, debt - restDecrement)
        NeckState.STATIC_DEVICE, NeckState.SUPINE_LIKE, NeckState.SIDE_LYING_LIKE -> debt
    }

    companion object {
        /** SharedPreferences file and key holding D_n across service restarts. */
        const val PREFS = "neck"
        const val KEY_DEBT = "d_n"

        /** Hansraj (2014) effective head load (kg) at neck flexion 15°/30°/45°/60°. */
        private val LOAD_ANGLES = doubleArrayOf(15.0, 30.0, 45.0, 60.0)
        private val LOAD_KG = doubleArrayOf(12.0, 18.0, 22.0, 27.0)

        /**
         * Load weight w(θ_neck) = load(θ) / load(15°), linearly interpolated: 15°→1.0, 30°→1.5, 45°→1.83,
         * 60°→2.25. Clamped to 1.0 below 15° and 2.25 above 60° (no data beyond 60°).
         */
        fun loadWeight(neckDeg: Double): Double {
            val base = LOAD_KG[0]
            if (neckDeg <= LOAD_ANGLES.first()) return 1.0
            if (neckDeg >= LOAD_ANGLES.last()) return LOAD_KG.last() / base
            val i = LOAD_ANGLES.indexOfLast { it <= neckDeg }
            val t = (neckDeg - LOAD_ANGLES[i]) / (LOAD_ANGLES[i + 1] - LOAD_ANGLES[i])
            return (LOAD_KG[i] + t * (LOAD_KG[i + 1] - LOAD_KG[i])) / base
        }
    }
}
