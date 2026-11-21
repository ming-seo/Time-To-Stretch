package com.example.tts.stretch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.tts.ActivityTransitionReceiver
import com.example.tts.MainActivity
import com.example.tts.SedentaryTracker
import com.example.tts.neck.NeckSensingService
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Glue between the two detectors: NeckSensingService is the single background engine, and calls
 * [onServiceStart], [onServiceStop] and [onNeckDebt] (once per 60 s cycle). Here D_s is read alongside
 * D_n, combined as D = w_n * D_n + w_s * D_s, and a stretch reminder is posted when D crosses [THRESHOLD].
 */
object Stretch {

    /** User turned background monitoring on: start the engine now and again after reboot. */
    fun enable(context: Context) {
        prefs(context).edit { putBoolean(KEY_ENABLED, true) }
        ContextCompat.startForegroundService(context, Intent(context, NeckSensingService::class.java))
    }

    fun disable(context: Context) {
        prefs(context).edit { putBoolean(KEY_ENABLED, false) }
        context.stopService(Intent(context, NeckSensingService::class.java))
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * The service's own start/stop also sets [KEY_ENABLED], so starting or stopping from any screen
     * (this app's home or NeckActivity) leaves one consistent on/off state. A process kill skips
     * onDestroy, so the flag stays on and the service comes back via START_STICKY or [BootReceiver].
     * Some OEMs (Samsung) do run onDestroy at power-off, so a stop after [onShutdown] keeps the flag.
     */
    fun onServiceStart(context: Context) {
        prefs(context).edit {
            putBoolean(KEY_ENABLED, true)
            putBoolean(KEY_SHUTTING_DOWN, false)
        }
        SedentaryTracker(context).start()
        registerTransitions(context)
    }

    fun onShutdown(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_SHUTTING_DOWN, true) }
    }

    fun onBoot(context: Context) {
        prefs(context).edit { putBoolean(KEY_SHUTTING_DOWN, false) }
    }

    fun onServiceStop(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SHUTTING_DOWN, false)) {
            prefs.edit(commit = true) { putBoolean(KEY_ENABLED, false) }
        }
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(transitionPendingIntent(context))
        SedentaryTracker(context).stop()
    }

    fun onNeckDebt(context: Context, dn: Double) {
        val ds = SedentaryTracker(context).getDebt()
        val combined = W_N * dn + W_S * ds
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        prefs.edit {
            putFloat(KEY_LAST_D, combined.toFloat())
            putLong(KEY_LAST_EVAL_AT, now)
        }
        Log.d(TAG, "D_n=%.2f D_s=%.2f D=%.2f".format(dn, ds, combined))

        val lastNotified = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
        if (combined >= THRESHOLD && now - lastNotified >= COOLDOWN_MS) {
            notifyStretch(context, dn, ds, combined)
            prefs.edit { putLong(KEY_LAST_NOTIFIED_AT, now) }
        }
    }

    fun lastCombined(context: Context): Double = prefs(context).getFloat(KEY_LAST_D, 0f).toDouble()

    fun lastEvalAt(context: Context): Long = prefs(context).getLong(KEY_LAST_EVAL_AT, 0L)

    private fun registerTransitions(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted; D_s will not update")
            return
        }
        val transitions = listOf(
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionPendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "activity transitions registered") }
            .addOnFailureListener { Log.e(TAG, "activity transition registration failed", it) }
    }

    private fun transition(activity: Int, type: Int) =
        ActivityTransition.Builder().setActivityType(activity).setActivityTransition(type).build()

    private fun transitionPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        TRANSITION_REQUEST_CODE,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyStretch(context: Context, dn: Double, ds: Double, combined: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "스트레칭 알림", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val reason = if (W_N * dn >= W_S * ds) "목을 오래 숙이고 있었어요" else "오래 앉아 있었어요"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("스트레칭할 시간이에요")
            .setContentText("$reason (D = %.1f)".format(combined))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "Stretch"
    private const val PREFS = "stretch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHUTTING_DOWN = "shutting_down"
    private const val KEY_LAST_D = "last_d"
    private const val KEY_LAST_EVAL_AT = "last_eval_at"
    private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
    private const val CHANNEL_ID = "stretch_reminder"
    private const val NOTIFICATION_ID = 3001
    private const val TRANSITION_REQUEST_CODE = 1001

    // Placeholders until calibrated. Both debts grow ~1/min under load, so 30 ≈ half an hour of one alone.
    const val W_N = 1.0
    const val W_S = 1.0
    const val THRESHOLD = 30.0
    private const val COOLDOWN_MS = 30 * 60_000L
}
