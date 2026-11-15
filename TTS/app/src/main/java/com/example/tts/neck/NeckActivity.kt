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

/** P0 debug screen for mode A: start/stop sensing and show the latest values. */
class NeckActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvTilt: TextView
    private lateinit var tvScreenInUse: TextView
    private lateinit var tvLastCycle: TextView
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
        if (results.values.all { it }) {
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
        tvScreenInUse = findViewById(R.id.tvScreenInUse)
        tvLastCycle = findViewById(R.id.tvLastCycle)
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
        if (missing.isEmpty()) startSensing() else permissionLauncher.launch(missing.toTypedArray())
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
        tvScreenInUse.text = "화면 사용 중: " + if (!state.running) "-" else if (state.screenInUse) "예" else "아니오"
        tvLastCycle.text = state.lastCycle?.let {
            "마지막 60초: CATA " + (it.cata?.let { c -> fmt(c) + "°" } ?: "-") +
                " · 유효 윈도우 ${it.validWindowCount}/${it.windowCount}"
        } ?: "마지막 60초: -"
        tvLogDir.text = "로그: " + (state.logDir ?: "-")
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val REFRESH_MS = 1000L
    }
}
