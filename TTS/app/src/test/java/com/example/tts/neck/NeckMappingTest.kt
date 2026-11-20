package com.example.tts.neck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeckMappingTest {

    private fun mappingOf(vararg pairs: Pair<Double, Double>) =
        pairs.fold(NeckMapping()) { g, (x, y) -> g.add(x, y) }

    @Test
    fun exactLineIsRecovered() {
        val g = mappingOf(*(0 until 10).map { i -> (10.0 + 5 * i) to (0.5 * (10.0 + 5 * i) - 3.0) }.toTypedArray())
        assertTrue(g.ready)
        assertEquals(0.5, g.slope!!, 1e-9)
        assertEquals(-3.0, g.intercept!!, 1e-9)
        assertEquals(27.0, g.predict(60.0)!!, 1e-9)
        assertEquals(0.0, g.residualStd!!, 1e-6)
    }

    @Test
    fun residualStdMatchesNoise() {
        // y = x + (+1, -1, ...): slope 1, intercept ~0, residual std ~1 (n-2 denominator).
        val pairs = (0 until 10).map { i -> (10.0 * i) to (10.0 * i + if (i % 2 == 0) 1.0 else -1.0) }
        val g = mappingOf(*pairs.toTypedArray())
        assertEquals(1.0, g.slope!!, 0.05)
        val sse = pairs.sumOf { (x, y) -> (y - g.predict(x)!!).let { it * it } }
        assertEquals(kotlin.math.sqrt(sse / 8), g.residualStd!!, 1e-6)
    }

    @Test
    fun notReadyWithTooFewPairs() {
        val g = mappingOf(*(0 until 9).map { i -> (10.0 * i) to (10.0 * i) }.toTypedArray())
        assertFalse(g.ready)
        assertNull(g.predict(30.0))
    }

    @Test
    fun notReadyWithNarrowTiltRange() {
        val g = mappingOf(*(0 until 20).map { i -> (40.0 + i) to (20.0 + i) }.toTypedArray())
        assertFalse(g.ready) // range 19° < 20°
        assertNull(g.slope)
    }
}
