package com.example.tts.neck

import kotlin.math.abs

/** Where a cycle's neck angle came from. */
enum class NeckSource {
    /** A successful camera burst started inside this cycle: its measured neck angle. */
    SAMPLE,
    /** g(CATA) plus the last burst's residual (measured − g(phone tilt)), while that burst is recent and similar. */
    RESIDUAL,
    /** g(CATA) only. */
    G,
}

data class NeckEstimate(val deg: Double, val source: NeckSource)

/**
 * Picks the neck angle used to judge a cycle. Pure logic, no Android dependencies.
 * g() alone gives the same angle for the same phone tilt; the camera bursts add what g() misses:
 * the measured angle for the cycle they fall in, and their residual for the cycles right after.
 */
class NeckEstimator(
    private val maxHoldMs: Long = 120_000L,
    private val maxTiltDiffDeg: Double = 10.0,
) {

    private class Anchor(val timeMs: Long, val phoneTilt: Double, val neckDeg: Double)

    /** Latest successful burst; a newer one replaces it. */
    private var anchor: Anchor? = null

    fun onSample(sample: FaceSample) {
        if (!sample.ok) return
        anchor = Anchor(sample.startMs, sample.phoneTilt!!, sample.neckDeg!!)
    }

    /** Neck angle for [cycle], or null when there is neither a burst in the cycle nor a ready g(). */
    fun estimate(cycle: CycleResult, mapping: NeckMapping): NeckEstimate? {
        val cata = cycle.cata ?: return null
        val a = anchor
        if (a != null && a.timeMs >= cycle.startMs && a.timeMs < cycle.endMs) {
            return NeckEstimate(a.neckDeg, NeckSource.SAMPLE)
        }
        val g = mapping.predict(cata) ?: return null
        if (a != null && a.timeMs < cycle.startMs && cycle.endMs - a.timeMs <= maxHoldMs &&
            abs(cata - a.phoneTilt) <= maxTiltDiffDeg
        ) {
            // Residual against the current g(), so it stays consistent as g() is refitted.
            val residual = a.neckDeg - mapping.predict(a.phoneTilt)!!
            return NeckEstimate(g + residual, NeckSource.RESIDUAL)
        }
        return NeckEstimate(g, NeckSource.G)
    }
}
