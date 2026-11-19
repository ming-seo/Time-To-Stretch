package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeckEstimatorTest {

    /** Ready g(): θ_neck = θ_phone − 5 exactly. */
    private val g = (0 until 10).fold(NeckMapping()) { m, i -> m.add(20.0 + 4 * i, 15.0 + 4 * i) }
    private val notReady = NeckMapping()

    private fun cycle(startMs: Long, cata: Double) = CycleResult(
        startMs = startMs, endMs = startMs + 60_000, windowCount = 12, validWindowCount = 12,
        staticWindowCount = 0, cata = cata, tiltStd = 0.0, linAccMean = 0.5, linAccMax = 1.0,
    )

    private fun sample(startMs: Long, tilt: Double, neck: Double, fail: String? = null) = FaceSample(
        startMs, startMs + 3_000, 10, 10, if (fail == null) 10 else 0,
        if (fail == null) tilt else null, if (fail == null) neck else null, 0.0, 1.0, fail,
    )

    @Test
    fun usesGWithoutSamples() {
        val e = NeckEstimator()
        assertEquals(NeckEstimate(35.0, NeckSource.G), e.estimate(cycle(0, 40.0), g))
        assertNull(e.estimate(cycle(0, 40.0), notReady))
    }

    @Test
    fun sampleInsideCycleIsUsedDirectlyEvenBeforeGIsReady() {
        val e = NeckEstimator()
        e.onSample(sample(30_000, tilt = 40.0, neck = 50.0))
        assertEquals(NeckEstimate(50.0, NeckSource.SAMPLE), e.estimate(cycle(0, 40.0), notReady))
        assertEquals(NeckEstimate(50.0, NeckSource.SAMPLE), e.estimate(cycle(0, 40.0), g))
    }

    @Test
    fun residualCarriesToNextCycleWithinHoldAndTilt() {
        val e = NeckEstimator()
        e.onSample(sample(30_000, tilt = 40.0, neck = 50.0)) // residual = 50 − 35 = +15
        // Next cycle ends 90 s after the burst, CATA within 10° → g(45) + 15 = 55.
        assertEquals(NeckEstimate(55.0, NeckSource.RESIDUAL), e.estimate(cycle(60_000, 45.0), g))
        // Tilt moved more than 10° → g only.
        assertEquals(NeckEstimate(46.0, NeckSource.G), e.estimate(cycle(60_000, 51.0), g))
        // Cycle ends more than 2 min after the burst → g only.
        assertEquals(NeckEstimate(40.0, NeckSource.G), e.estimate(cycle(120_000, 45.0), g))
        // Residual needs g().
        assertNull(e.estimate(cycle(60_000, 45.0), notReady))
    }

    @Test
    fun failedSampleKeepsPreviousAnchorAndNewerSampleReplacesIt() {
        val e = NeckEstimator()
        e.onSample(sample(30_000, tilt = 40.0, neck = 50.0))
        e.onSample(sample(70_000, tilt = 0.0, neck = 0.0, fail = "too_few_frames"))
        assertEquals(NeckEstimate(55.0, NeckSource.RESIDUAL), e.estimate(cycle(60_000, 45.0), g))
        e.onSample(sample(90_000, tilt = 40.0, neck = 30.0)) // residual −5
        assertEquals(NeckEstimate(30.0, NeckSource.SAMPLE), e.estimate(cycle(60_000, 45.0), g))
        assertEquals(NeckEstimate(40.0, NeckSource.RESIDUAL), e.estimate(cycle(120_000, 50.0), g))
    }
}
