package com.example.tts

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * Sedentary debt D_s, mirroring NeckDebtCalculator's D_n so the two can be combined as
 * D = w_n * D_n + w_s * D_s: accrues at [STILL_RATE_PER_MINUTE] while the device is
 * continuously STILL, decays at [MOVING_RATE_PER_MINUTE] while WALKING/RUNNING, floored at 0.
 *
 * There is no periodic tick; debt is evaluated lazily from the last committed
 * (anchor time, debt at that time, state since then), the same way a bank computes
 * accrued interest on read instead of ticking a background clock.
 */
class SedentaryTracker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onTransitionEvent(event: ActivityTransitionEvent) {
        val now = System.currentTimeMillis()
        val newIsStill = when {
            event.activityType == DetectedActivity.STILL &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> true
            event.activityType == DetectedActivity.STILL &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> false
            (event.activityType == DetectedActivity.WALKING || event.activityType == DetectedActivity.RUNNING) &&
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> false
            else -> isStill()
        }
        commit(now, newIsStill, monitoring = true)
        appendLog("${describe(event)} @ ${TIME_FORMAT.format(now)}")
    }

    /**
     * Call once monitoring starts. Assumes STILL: the Transition API sends no STILL ENTER for a state the
     * device is already in, so starting while seated would otherwise never accrue until the user walks.
     */
    fun start() {
        commit(System.currentTimeMillis(), newIsStill = true, monitoring = true)
    }

    /** Call once monitoring stops: freezes D_s so it no longer drifts while there is no signal. */
    fun stop() {
        commit(System.currentTimeMillis(), isStill(), monitoring = false)
    }

    /** Current D_s (minutes-equivalent debt units), evaluated lazily. */
    fun getDebt(): Double = debtAt(System.currentTimeMillis())

    fun isStill(): Boolean = prefs.getBoolean(KEY_IS_STILL, false)

    fun isMonitoring(): Boolean = prefs.getBoolean(KEY_MONITORING, false)

    fun getLog(): String = prefs.getString(KEY_LOG, "") ?: ""

    fun clearLog() {
        prefs.edit { remove(KEY_LOG) }
    }

    private fun commit(now: Long, newIsStill: Boolean, monitoring: Boolean) {
        val debtNow = debtAt(now)
        prefs.edit {
            putFloat(KEY_BASE_DEBT, debtNow.toFloat())
            putLong(KEY_ANCHOR_TIME, now)
            putBoolean(KEY_IS_STILL, newIsStill)
            putBoolean(KEY_MONITORING, monitoring)
        }
    }

    /** D_s at [time], extrapolated from the last commit; frozen whenever monitoring is off. */
    private fun debtAt(time: Long): Double {
        val baseDebt = prefs.getFloat(KEY_BASE_DEBT, 0f).toDouble()
        if (!prefs.getBoolean(KEY_MONITORING, false)) return baseDebt

        val anchorTime = prefs.getLong(KEY_ANCHOR_TIME, time)
        val wasStill = prefs.getBoolean(KEY_IS_STILL, false)
        val elapsedMinutes = max(0L, time - anchorTime) / 60_000.0
        val delta = if (wasStill) {
            STILL_RATE_PER_MINUTE * elapsedMinutes
        } else {
            -MOVING_RATE_PER_MINUTE * elapsedMinutes
        }
        return max(0.0, baseDebt + delta)
    }

    private fun appendLog(line: String) {
        val existing = getLog().split("\n").filter { it.isNotBlank() }
        val updated = (listOf(line) + existing).take(MAX_LOG_LINES).joinToString("\n")
        prefs.edit { putString(KEY_LOG, updated) }
    }

    private fun describe(event: ActivityTransitionEvent): String {
        val activity = when (event.activityType) {
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            else -> "ACTIVITY_${event.activityType}"
        }
        val transition = when (event.transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
            else -> "TRANSITION_${event.transitionType}"
        }
        return "$activity $transition"
    }

    companion object {
        private const val PREFS_NAME = "sedentary"
        private const val KEY_BASE_DEBT = "base_debt"
        private const val KEY_ANCHOR_TIME = "anchor_time"
        private const val KEY_IS_STILL = "is_still"
        private const val KEY_MONITORING = "monitoring"
        private const val KEY_LOG = "transition_log"
        private const val MAX_LOG_LINES = 20
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // Placeholder rates; to be calibrated against real usage once both debts feed one notification.
        private const val STILL_RATE_PER_MINUTE = 1.0
        private const val MOVING_RATE_PER_MINUTE = 2.0
    }
}
