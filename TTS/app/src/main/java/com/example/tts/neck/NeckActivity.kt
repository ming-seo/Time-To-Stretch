package com.example.tts.neck

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.tts.R
import java.util.Locale

/** Debug screen for mode A: shows the latest values, cycle state and D_n (sensing is turned on from the home screen). */
class NeckActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_neck)

        tvTilt = findViewById(R.id.tvTilt)
        tvThetaC = findViewById(R.id.tvThetaC)
        tvScreenInUse = findViewById(R.id.tvScreenInUse)
        tvLastCycle = findViewById(R.id.tvLastCycle)
        tvDebt = findViewById(R.id.tvDebt)
        tvLogDir = findViewById(R.id.tvLogDir)

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

    private fun refreshUi() {
        val state = NeckLiveState
        tvTilt.text = getString(R.string.neck_phone_tilt, state.currentTilt?.let { fmt(it) + "°" } ?: "-")
        tvThetaC.text = getString(R.string.neck_theta_c, state.frameThetaC?.let { fmt(it) + "°" + ageText(state.frameThetaCMs) } ?: "-")
        tvScreenInUse.text = getString(
            R.string.neck_screen_in_use,
            if (!state.running) "-" else getString(if (state.screenInUse) R.string.yes else R.string.no),
        )
        tvLastCycle.text = state.lastCycle?.let {
            getString(
                R.string.neck_last_cycle,
                it.cata?.let { c -> fmt(c) + "°" } ?: "-", it.validWindowCount, it.windowCount, state.lastState ?: "-",
            ) +
                "\n" + sampleText(state.lastCycleSample) +
                "\n" + getString(
                    R.string.neck_angle,
                    state.lastNeck?.let { n -> fmt(n.deg) + "° (${n.source})" } ?: if (it.cata != null) "- (CATA)" else "-",
                )
        } ?: getString(R.string.neck_last_cycle_none)
        val debt = getSharedPreferences(NeckDebtCalculator.PREFS, MODE_PRIVATE).getFloat(NeckDebtCalculator.KEY_DEBT, 0f)
        tvDebt.text = "D_n: " + String.format(Locale.US, "%.2f", debt)
        tvLogDir.text = getString(R.string.neck_log, state.logDir ?: "-")
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** Empty while the frame is fresh (a burst is running), otherwise how long ago it was measured. */
    private fun ageText(timeMs: Long): String {
        val ageSec = (System.currentTimeMillis() - timeMs) / 1000
        return if (ageSec < 2) "" else if (ageSec < 60) " (" + getString(R.string.age_sec, ageSec) + ")"
        else " (" + getString(R.string.age_min_sec, ageSec / 60, ageSec % 60) + ")"
    }

    private fun sampleText(sample: FaceSample?): String = when {
        sample == null -> getString(R.string.sample_none)
        sample.ok -> getString(R.string.sample_ok, fmt(sample.neckDeg!!))
        else -> getString(R.string.sample_fail, sample.failReason)
    }

    companion object {
        private const val REFRESH_MS = 1000L
    }
}
