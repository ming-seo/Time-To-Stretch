package com.example.tts.neck

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-user mapping g(): θ_neck ≈ slope·θ_phone + intercept (design doc §4.5), fitted by least squares
 * over all (phone tilt, neck angle) pairs from the camera bursts. Keeps only running sums. Immutable.
 */
data class NeckMapping(
    val n: Int = 0,
    val sumX: Double = 0.0,
    val sumY: Double = 0.0,
    val sumXX: Double = 0.0,
    val sumXY: Double = 0.0,
    val sumYY: Double = 0.0,
    val minX: Double = Double.POSITIVE_INFINITY,
    val maxX: Double = Double.NEGATIVE_INFINITY,
) {

    fun add(phoneTilt: Double, neckDeg: Double) = NeckMapping(
        n = n + 1,
        sumX = sumX + phoneTilt,
        sumY = sumY + neckDeg,
        sumXX = sumXX + phoneTilt * phoneTilt,
        sumXY = sumXY + phoneTilt * neckDeg,
        sumYY = sumYY + neckDeg * neckDeg,
        minX = min(minX, phoneTilt),
        maxX = max(maxX, phoneTilt),
    )

    /** Enough pairs over a wide enough tilt range for a stable slope. */
    val ready: Boolean get() = n >= MIN_PAIRS && maxX - minX >= MIN_TILT_RANGE_DEG && denominator > 0.0

    private val denominator: Double get() = n * sumXX - sumX * sumX

    val slope: Double? get() = if (ready) (n * sumXY - sumX * sumY) / denominator else null

    val intercept: Double? get() = slope?.let { (sumY - it * sumX) / n }

    /** g(θ_phone); null until [ready]. */
    fun predict(phoneTilt: Double): Double? {
        val a = slope ?: return null
        return a * phoneTilt + intercept!!
    }

    /** Residual standard deviation (°), stored and shown as the uncertainty of g(). */
    val residualStd: Double?
        get() {
            val a = slope ?: return null
            val b = intercept!!
            val sse = sumYY - 2 * a * sumXY - 2 * b * sumY + a * a * sumXX + 2 * a * b * sumX + n * b * b
            return sqrt(max(0.0, sse) / (n - 2))
        }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(KEY_N, n)
            .putLong(KEY_SX, sumX.toRawBits())
            .putLong(KEY_SY, sumY.toRawBits())
            .putLong(KEY_SXX, sumXX.toRawBits())
            .putLong(KEY_SXY, sumXY.toRawBits())
            .putLong(KEY_SYY, sumYY.toRawBits())
            .putLong(KEY_MIN_X, minX.toRawBits())
            .putLong(KEY_MAX_X, maxX.toRawBits())
            .apply()
    }

    companion object {
        const val MIN_PAIRS = 10
        const val MIN_TILT_RANGE_DEG = 20.0

        private const val KEY_N = "g_n"
        private const val KEY_SX = "g_sum_x"
        private const val KEY_SY = "g_sum_y"
        private const val KEY_SXX = "g_sum_xx"
        private const val KEY_SXY = "g_sum_xy"
        private const val KEY_SYY = "g_sum_yy"
        private const val KEY_MIN_X = "g_min_x"
        private const val KEY_MAX_X = "g_max_x"

        fun load(prefs: SharedPreferences): NeckMapping {
            if (!prefs.contains(KEY_N)) return NeckMapping()
            fun d(key: String) = Double.fromBits(prefs.getLong(key, 0L))
            return NeckMapping(
                n = prefs.getInt(KEY_N, 0),
                sumX = d(KEY_SX),
                sumY = d(KEY_SY),
                sumXX = d(KEY_SXX),
                sumXY = d(KEY_SXY),
                sumYY = d(KEY_SYY),
                minX = d(KEY_MIN_X),
                maxX = d(KEY_MAX_X),
            )
        }
    }
}
