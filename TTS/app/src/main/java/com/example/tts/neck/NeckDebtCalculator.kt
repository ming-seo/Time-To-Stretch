package com.example.tts.neck

import kotlin.math.max

/** Mode A posture state of one 60-second cycle (design doc §4.2). */
enum class NeckState { BENT, UPRIGHT, NOT_IN_USE, STATIC_DEVICE, SUPINE_LIKE }

/**
 * Judges each cycle and updates the neck debt D_n (design doc §4.6). Pure logic, no Android dependencies.
 * Without a personal mapping g(), only the SmartPose default threshold (CATA ≥ 56°) is used.
 */
class NeckDebtCalculator(
    private val minValidWindows: Int = 6,
    private val bentCataDeg: Double = 56.0,
    private val bentIncrement: Double = 1.0,
    private val uprightDecrement: Double = 0.25,
    private val restDecrement: Double = 0.5,
) {

    fun judge(cycle: CycleResult): NeckState {
        val cata = cycle.cata
        return when {
            cycle.validWindowCount < minValidWindows ->
                if (cycle.validWindowCount + cycle.staticWindowCount >= minValidWindows) {
                    NeckState.STATIC_DEVICE
                } else {
                    NeckState.NOT_IN_USE
                }
            cata == null -> NeckState.NOT_IN_USE
            cata < 0.0 -> NeckState.SUPINE_LIKE
            cata >= bentCataDeg -> NeckState.BENT
            else -> NeckState.UPRIGHT
        }
    }

    fun update(debt: Double, state: NeckState): Double = when (state) {
        NeckState.BENT -> debt + bentIncrement
        NeckState.UPRIGHT -> max(0.0, debt - uprightDecrement)
        NeckState.NOT_IN_USE -> max(0.0, debt - restDecrement)
        NeckState.STATIC_DEVICE, NeckState.SUPINE_LIKE -> debt
    }

    companion object {
        /** SharedPreferences file and key holding D_n across service restarts. */
        const val PREFS = "neck"
        const val KEY_DEBT = "d_n"
    }
}
