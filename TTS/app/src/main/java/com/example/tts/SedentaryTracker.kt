package com.example.tts

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Persists the "still since" state across the ActivityTransitionReceiver process
 * and the UI process via SharedPreferences, and derives the current sedentary
 * duration (D_s candidate) from it.
 */
class SedentaryTracker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onTransitionEvent(event: ActivityTransitionEvent) {
        val now = System.currentTimeMillis()
        val isStillEnter = event.activityType == DetectedActivity.STILL &&
            event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        val isMovementEnter = (event.activityType == DetectedActivity.WALKING ||
            event.activityType == DetectedActivity.RUNNING) &&
            event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER

        when {
            isStillEnter -> prefs.edit {
                putLong(KEY_STILL_SINCE, now)
                putBoolean(KEY_IS_STILL, true)
            }
            isMovementEnter -> prefs.edit {
                putBoolean(KEY_IS_STILL, false)
            }
        }

        appendLog("${describe(event)} @ ${TIME_FORMAT.format(now)}")
    }

    /** Sedentary duration in millis; 0 while the user is not in a continuous STILL streak. */
    fun getSedentaryMillis(): Long {
        val isStill = prefs.getBoolean(KEY_IS_STILL, false)
        val since = prefs.getLong(KEY_STILL_SINCE, 0L)
        return if (isStill && since > 0L) System.currentTimeMillis() - since else 0L
    }

    fun getLog(): String = prefs.getString(KEY_LOG, "") ?: ""

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
        private const val PREFS_NAME = "posture_prefs"
        private const val KEY_IS_STILL = "is_still"
        private const val KEY_STILL_SINCE = "still_since"
        private const val KEY_LOG = "transition_log"
        private const val MAX_LOG_LINES = 20
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}
