package com.example.tts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tts.neck.NeckActivity
import com.example.tts.neck.NeckDebtCalculator
import com.example.tts.neck.NeckLiveState
import com.example.tts.stretch.Stretch
import java.text.SimpleDateFormat
import java.util.Locale

/** Setup and debug home. Monitoring itself runs in NeckSensingService, independent of this screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var tracker: SedentaryTracker
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvDebts: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggleMonitoring: Button

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // CAMERA is optional: without it the neck estimate falls back to phone tilt only.
        if (results.filterKeys { it != Manifest.permission.CAMERA }.values.all { it }) {
            startMonitoring()
        } else {
            Toast.makeText(this, "측정에 필요한 권한이 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tracker = SedentaryTracker(this)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvDebts = findViewById(R.id.tvDebts)
        tvLog = findViewById(R.id.tvLog)
        btnToggleMonitoring = findViewById(R.id.btnToggleMonitoring)

        btnToggleMonitoring.setOnClickListener { onToggleClicked() }
        findViewById<Button>(R.id.btnOpenNeck).setOnClickListener {
            startActivity(Intent(this, NeckActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
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
        if (Stretch.isEnabled(this)) {
            Stretch.disable(this)
            refreshUi()
            return
        }
        val missing = (requiredPermissions() + Manifest.permission.CAMERA).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitoring() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startMonitoring() {
        Stretch.enable(this)
        requestBatteryExemption()
        refreshHandler.postDelayed({ refreshUi() }, 300)
    }

    /** Without this, Samsung and other OEMs kill the service a while after the screen turns off. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun refreshUi() {
        val enabled = Stretch.isEnabled(this)
        val running = NeckLiveState.running
        tvServiceStatus.text = when {
            enabled && running -> "백그라운드 측정 켜짐"
            enabled -> "백그라운드 측정 켜짐 (서비스 시작 대기)"
            running -> "목 각도 화면에서만 측정 중"
            else -> "백그라운드 측정 꺼짐"
        }
        btnToggleMonitoring.text = if (enabled) "백그라운드 측정 중지" else "백그라운드 측정 시작"

        val dn = getSharedPreferences(NeckDebtCalculator.PREFS, MODE_PRIVATE)
            .getFloat(NeckDebtCalculator.KEY_DEBT, 0f).toDouble()
        val ds = tracker.getDebt()
        val dsState = if (!tracker.isMonitoring()) "고정" else if (tracker.isStill()) "정지 중" else "이동 중"
        val lastEval = Stretch.lastEvalAt(this).takeIf { it > 0 }?.let { TIME_FORMAT.format(it) } ?: "-"
        tvDebts.text = buildString {
            appendLine("D_n = %.2f".format(dn))
            appendLine("D_s = %.2f (%s)".format(ds, dsState))
            appendLine("D   = %.2f / 알림 기준 %.0f".format(Stretch.lastCombined(this@MainActivity), Stretch.THRESHOLD))
            append("마지막 합산: $lastEval")
        }
        tvLog.text = tracker.getLog().ifBlank { "(아직 이벤트 없음)" }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}
