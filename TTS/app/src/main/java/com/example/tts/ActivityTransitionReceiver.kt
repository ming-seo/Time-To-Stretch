package com.example.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive fired, hasResult=${ActivityTransitionResult.hasResult(intent)}")
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val tracker = SedentaryTracker(context)
        result.transitionEvents.forEach { event ->
            Log.d(TAG, "transition event: activityType=${event.activityType} transitionType=${event.transitionType}")
            tracker.onTransitionEvent(event)
        }
    }

    companion object {
        private const val TAG = "ActivityTransitionRx"
    }
}
