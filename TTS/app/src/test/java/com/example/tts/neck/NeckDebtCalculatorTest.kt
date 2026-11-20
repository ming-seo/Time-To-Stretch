package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Test

class NeckDebtCalculatorTest {

    private val calc = NeckDebtCalculator()

    private fun cycle(cata: Double?, supine: Int = 0, sideLying: Int = 0) = CycleResult(
        startMs = 0, endMs = 60_000, windowCount = 12, validWindowCount = 12, staticWindowCount = 0,
        cata = cata, tiltStd = 0.0, linAccMean = 0.5, linAccMax = 1.0,
        supineWindowCount = supine, sideLyingWindowCount = sideLying,
    )

    @Test
    fun withoutMappingUsesCataThreshold() {
        assertEquals(NeckState.BENT, calc.judge(cycle(56.0)))
        assertEquals(NeckState.UPRIGHT, calc.judge(cycle(55.0)))
        assertEquals(1.0, calc.update(0.0, NeckState.BENT), 1e-9)
    }

    @Test
    fun withMappingUsesNeckAngle() {
        // Low phone tilt but large estimated neck angle → BENT; and the reverse → UPRIGHT.
        assertEquals(NeckState.BENT, calc.judge(cycle(30.0), neckDeg = 20.0))
        assertEquals(NeckState.UPRIGHT, calc.judge(cycle(70.0), neckDeg = 10.0))
    }

    @Test
    fun lyingWindowsDecideTheCycleFromHalf() {
        assertEquals(NeckState.SUPINE_LIKE, calc.judge(cycle(null, supine = 12), neckDeg = 30.0))
        assertEquals(NeckState.SIDE_LYING_LIKE, calc.judge(cycle(20.0, sideLying = 6)))
        // Mixed lying windows: the larger group wins, SUPINE_LIKE on a tie.
        assertEquals(NeckState.SIDE_LYING_LIKE, calc.judge(cycle(20.0, supine = 2, sideLying = 5)))
        assertEquals(NeckState.SUPINE_LIKE, calc.judge(cycle(20.0, supine = 3, sideLying = 3)))
        // Fewer than half lying: judged from the upright windows' CATA.
        assertEquals(NeckState.BENT, calc.judge(cycle(60.0, sideLying = 5)))
        // Slightly negative CATA is no longer SUPINE_LIKE by itself.
        assertEquals(NeckState.UPRIGHT, calc.judge(cycle(-5.0)))
    }

    @Test
    fun lyingLeavesDebtUnchanged() {
        assertEquals(2.0, calc.update(2.0, NeckState.SUPINE_LIKE), 1e-9)
        assertEquals(2.0, calc.update(2.0, NeckState.SIDE_LYING_LIKE), 1e-9)
    }

    @Test
    fun bentAddsHansrajWeight() {
        assertEquals(1.0, NeckDebtCalculator.loadWeight(10.0), 1e-9)
        assertEquals(1.0, NeckDebtCalculator.loadWeight(15.0), 1e-9)
        assertEquals(1.25, NeckDebtCalculator.loadWeight(22.5), 1e-9)
        assertEquals(1.5, NeckDebtCalculator.loadWeight(30.0), 1e-9)
        assertEquals(22.0 / 12.0, NeckDebtCalculator.loadWeight(45.0), 1e-9)
        assertEquals(2.25, NeckDebtCalculator.loadWeight(60.0), 1e-9)
        assertEquals(2.25, NeckDebtCalculator.loadWeight(80.0), 1e-9)
        assertEquals(1.5 + 2.0, calc.update(2.0, NeckState.BENT, neckDeg = 30.0), 1e-9)
    }
}
