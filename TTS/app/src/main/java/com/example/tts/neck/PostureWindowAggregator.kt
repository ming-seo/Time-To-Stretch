package com.example.tts.neck

import kotlin.math.max
import kotlin.math.sqrt

/** One 5-second monitoring window (SmartPose "monitoring period"). */
data class WindowResult(
    val startMs: Long,
    val endMs: Long,
    /** Screen on and unlocked for the whole window. */
    val screenInUse: Boolean,
    /** A call or a covered proximity sensor was seen during the window. */
    val excluded: Boolean,
    val tiltCount: Int,
    val tiltMean: Double?,
    val tiltStd: Double?,
    val linAccCount: Int,
    val linAccMean: Double?,
    val linAccMax: Double?,
) {
    val valid: Boolean get() = screenInUse && !excluded && tiltCount > 0
}

/** One 60-second cycle of 12 windows (SmartPose "notification cycle"). Stats use valid windows only. */
data class CycleResult(
    val startMs: Long,
    val endMs: Long,
    val windowCount: Int,
    val validWindowCount: Int,
    /** Mean of the valid windows' mean tilt (SmartPose CATA). */
    val cata: Double?,
    /** Standard deviation of the valid windows' mean tilt. */
    val tiltStd: Double?,
    val linAccMean: Double?,
    val linAccMax: Double?,
)

/**
 * Collects tilt and linear-acceleration samples into 5-second windows and groups
 * every [windowsPerCycle] windows into a cycle. Pure logic, no Android dependencies.
 */
class PostureWindowAggregator(private val windowsPerCycle: Int = 12) {

    private var windowStartMs = 0L
    private var inUseWholeWindow = false
    private var excludedInWindow = false
    private val tilt = RunningStats()
    private val linAcc = RunningStats()
    private var linAccMax = 0.0
    private val cycleWindows = mutableListOf<WindowResult>()

    fun startWindow(nowMs: Long, screenInUse: Boolean) {
        windowStartMs = nowMs
        inUseWholeWindow = screenInUse
        excludedInWindow = false
        tilt.clear()
        linAcc.clear()
        linAccMax = 0.0
    }

    fun onScreenInUseChanged(screenInUse: Boolean) {
        if (!screenInUse) inUseWholeWindow = false
    }

    fun markExcluded() {
        excludedInWindow = true
    }

    fun addTilt(degrees: Double) = tilt.add(degrees)

    fun addLinearAccMagnitude(magnitude: Double) {
        linAcc.add(magnitude)
        linAccMax = max(linAccMax, magnitude)
    }

    /** Closes the current window, starts the next one, and returns the finished window and cycle (if any). */
    fun closeWindow(nowMs: Long, screenInUseNow: Boolean): Pair<WindowResult, CycleResult?> {
        val window = WindowResult(
            startMs = windowStartMs,
            endMs = nowMs,
            screenInUse = inUseWholeWindow,
            excluded = excludedInWindow,
            tiltCount = tilt.count,
            tiltMean = tilt.meanOrNull(),
            tiltStd = tilt.stdOrNull(),
            linAccCount = linAcc.count,
            linAccMean = linAcc.meanOrNull(),
            linAccMax = if (linAcc.count > 0) linAccMax else null,
        )
        cycleWindows += window

        var cycle: CycleResult? = null
        if (cycleWindows.size >= windowsPerCycle) {
            cycle = buildCycle(cycleWindows)
            cycleWindows.clear()
        }
        startWindow(nowMs, screenInUseNow)
        return window to cycle
    }

    /** Drops the unfinished cycle, e.g. when monitoring stops. */
    fun reset() {
        cycleWindows.clear()
    }

    private fun buildCycle(windows: List<WindowResult>): CycleResult {
        val valid = windows.filter { it.valid }
        val tiltMeans = RunningStats().apply { valid.forEach { add(it.tiltMean!!) } }
        val withLinAcc = valid.filter { it.linAccCount > 0 }
        return CycleResult(
            startMs = windows.first().startMs,
            endMs = windows.last().endMs,
            windowCount = windows.size,
            validWindowCount = valid.size,
            cata = tiltMeans.meanOrNull(),
            tiltStd = tiltMeans.stdOrNull(),
            linAccMean = if (withLinAcc.isEmpty()) null else withLinAcc.map { it.linAccMean!! }.average(),
            linAccMax = withLinAcc.maxOfOrNull { it.linAccMax!! },
        )
    }

    /** Welford running mean / population standard deviation. */
    private class RunningStats {
        var count = 0
            private set
        private var mean = 0.0
        private var m2 = 0.0

        fun add(x: Double) {
            count++
            val delta = x - mean
            mean += delta / count
            m2 += delta * (x - mean)
        }

        fun clear() {
            count = 0
            mean = 0.0
            m2 = 0.0
        }

        fun meanOrNull(): Double? = if (count > 0) mean else null
        fun stdOrNull(): Double? = if (count > 0) sqrt(m2 / count) else null
    }
}
