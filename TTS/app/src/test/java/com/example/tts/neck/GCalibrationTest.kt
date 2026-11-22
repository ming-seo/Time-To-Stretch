package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GCalibrationTest {

    /** Feeds frames every 200 ms for [durationMs] from [startMs]; returns the statuses and the next timestamp. */
    private fun run(
        cal: GCalibration,
        startMs: Long,
        durationMs: Long,
        frame: (Long) -> Triple<Double?, Double?, Double?>,
    ): Pair<List<GCalibration.Status>, Long> {
        val statuses = mutableListOf<GCalibration.Status>()
        var ts = startMs
        while (ts < startMs + durationMs) {
            val (tilt, theta, rms) = frame(ts)
            statuses += cal.onFrame(ts, tilt, theta, rms)
            ts += 200
        }
        return statuses to ts
    }

    @Test
    fun allStepsFitTheNeckToTiltRelation() {
        val cal = GCalibration()
        var ts = 0L
        while (!cal.finished) {
            val target = cal.step.targetDeg
            ts = run(cal, ts, 4_000) { Triple(target + 1.0, 0.5 * (target + 1.0) - 3.0, 2.0) }.second
        }
        assertEquals(10, cal.results.size)
        val g = cal.mapping()
        assertTrue(g.ready)
        assertEquals(0.5, g.slope!!, 1e-9)
        assertEquals(-3.0, g.intercept!!, 1e-9)
    }

    @Test
    fun timeoutReportsWhyOnTargetFramesWereRejected() {
        val cal = GCalibration()
        val (statuses, _) = run(cal, 0, 10_200) { Triple(10.0, 5.0, 8.0) }
        assertTrue(GCalibration.Status.TIMEOUT in statuses)
        val rejects = cal.lastRejects
        assertNotNull(rejects)
        assertEquals(rejects!!.onTarget, rejects.highRms)
        assertEquals(0, rejects.noFace)
        assertEquals(0, cal.results.size)
    }

    @Test
    fun offTargetFramesAreNotCollected() {
        val cal = GCalibration()
        val (statuses, _) = run(cal, 0, 5_000) { Triple(30.0, 10.0, 2.0) }
        assertTrue(statuses.all { it == GCalibration.Status.WAITING })
        assertEquals(0, cal.collectedFrames)
    }
}
