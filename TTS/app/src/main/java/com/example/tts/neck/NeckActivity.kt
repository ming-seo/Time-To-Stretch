package com.example.tts.neck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tts.R
import java.util.Locale

/** Debug screen for mode A: start/stop sensing and show the latest values, cycle state and D_n. */
class NeckActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvTilt: TextView
    private lateinit var tvThetaC: TextView
    private lateinit var tvScreenInUse: TextView
    private lateinit var tvLastCycle: TextView
    private lateinit var tvDebt: TextView
    private lateinit var tvLogDir: TextView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // CAMERA is optional: without it mode A still runs and the camera bursts are logged as no_permission.
        if (results.filterKeys { it != Manifest.permission.CAMERA }.values.all { it }) {
            startSensing()
        } else {
            Toast.makeText(this, "측정에 필요한 권한이 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_neck)

        btnToggle = findViewById(R.id.btnToggleSensing)
        tvTilt = findViewById(R.id.tvTilt)
        tvThetaC = findViewById(R.id.tvThetaC)
        tvScreenInUse = findViewById(R.id.tvScreenInUse)
        tvLastCycle = findViewById(R.id.tvLastCycle)
        tvDebt = findViewById(R.id.tvDebt)
        tvLogDir = findViewById(R.id.tvLogDir)

        btnToggle.setOnClickListener { onToggleClicked() }
        findViewById<Button>(R.id.btnOpenCollect).setOnClickListener {
            startActivity(Intent(this, NeckCollectActivity::class.java))
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
        if (NeckLiveState.running) {
            stopService(Intent(this, NeckSensingService::class.java))
            refreshUi()
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val request = missing + listOf(Manifest.permission.CAMERA).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (request.isEmpty()) startSensing() else permissionLauncher.launch(request.toTypedArray())
    }

    /** ACTIVITY_RECOGNITION is required for a health-type foreground service on Android 14+. */
    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    private fun startSensing() {
        ContextCompat.startForegroundService(this, Intent(this, NeckSensingService::class.java))
        refreshHandler.postDelayed({ refreshUi() }, 300)
    }

    private fun refreshUi() {
        val state = NeckLiveState
        btnToggle.text = if (state.running) "측정 중지" else "측정 시작"
        tvTilt.text = "폰 기울기 θ: " + (state.currentTilt?.let { fmt(it) + "°" } ?: "-")
        tvThetaC.text = "목 각도 θ_C: " + (state.frameThetaC?.let { fmt(it) + "°" + ageText(state.frameThetaCMs) } ?: "-")
        tvScreenInUse.text = "화면 사용 중: " + if (!state.running) "-" else if (state.screenInUse) "예" else "아니오"
        tvLastCycle.text = state.lastCycle?.let {
            "마지막 60초: CATA " + (it.cata?.let { c -> fmt(c) + "°" } ?: "-") +
                " · 유효 윈도우 ${it.validWindowCount}/${it.windowCount}" +
                " · 상태 ${state.lastState ?: "-"}" +
                "\n" + sampleText(state.lastCycleSample) +
                "\n목 각도 " + (state.lastNeck?.let { n -> fmt(n.deg) + "° (${n.source})" } ?: if (it.cata != null) "- (CATA)" else "-")
        } ?: "마지막 60초: -"
        val debt = getSharedPreferences(NeckDebtCalculator.PREFS, MODE_PRIVATE).getFloat(NeckDebtCalculator.KEY_DEBT, 0f)
        tvDebt.text = "D_n: " + String.format(Locale.US, "%.2f", debt)
        tvLogDir.text = "로그: " + (state.logDir ?: "-")
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** Empty while the frame is fresh (a burst is running), otherwise how long ago it was measured. */
    private fun ageText(timeMs: Long): String {
        val ageSec = (System.currentTimeMillis() - timeMs) / 1000
        return if (ageSec < 2) "" else if (ageSec < 60) " (${ageSec}초 전)" else " (${ageSec / 60}분 ${ageSec % 60}초 전)"
    }

    private fun sampleText(sample: FaceSample?): String = when {
        sample == null -> "측정 없음"
        sample.ok -> "측정 성공 (θ_C ${fmt(sample.neckDeg!!)}°)"
        else -> "측정 실패 (${sample.failReason})"
    }

    companion object {
        private const val REFRESH_MS = 1000L
    }
}
