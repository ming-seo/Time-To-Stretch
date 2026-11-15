package com.example.tts

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tracker: SedentaryTracker
    private lateinit var tvSedentaryStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggleMonitoring: Button
    private lateinit var btnClearLog: Button

    private var isMonitoring = false

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMonitoring()
        } else {
            Toast.makeText(this, "ACTIVITY_RECOGNITION 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tracker = SedentaryTracker(this)
        tvSedentaryStatus = findViewById(R.id.tvSedentaryStatus)
        tvLog = findViewById(R.id.tvLog)
        btnToggleMonitoring = findViewById(R.id.btnToggleMonitoring)
        btnClearLog = findViewById(R.id.btnClearLog)

        btnToggleMonitoring.setOnClickListener { onToggleClicked() }
        btnClearLog.setOnClickListener {
            tracker.clearLog()
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun onToggleClicked() {
        if (isMonitoring) {
            stopMonitoring()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = transitionPendingIntent()

        ActivityRecognition.getClient(this)
            .requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                isMonitoring = true
                btnToggleMonitoring.text = "모니터링 중지"
                Log.d(TAG, "requestActivityTransitionUpdates succeeded")
                Toast.makeText(this, "모니터링 시작", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "requestActivityTransitionUpdates failed", e)
                Toast.makeText(this, "모니터링 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun stopMonitoring() {
        ActivityRecognition.getClient(this)
            .removeActivityTransitionUpdates(transitionPendingIntent())
            .addOnSuccessListener {
                isMonitoring = false
                btnToggleMonitoring.text = "모니터링 시작"
                tracker.reset()
                refreshUi()
                Toast.makeText(this, "모니터링 중지", Toast.LENGTH_SHORT).show()
            }
    }

    private fun transitionPendingIntent(): PendingIntent {
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            TRANSITION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun refreshUi() {
        val sedentaryMillis = tracker.getSedentaryMillis()
        tvSedentaryStatus.text = when {
            sedentaryMillis > 0 -> {
                val totalSeconds = sedentaryMillis / 1000
                "정지(앉음 추정) 지속시간: %d분 %d초".format(totalSeconds / 60, totalSeconds % 60)
            }
            isMonitoring -> "대기 중 (아직 감지된 정지 없음)"
            else -> "모니터링 전"
        }
        tvLog.text = tracker.getLog().ifBlank { "(아직 이벤트 없음)" }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TRANSITION_REQUEST_CODE = 1001
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}
