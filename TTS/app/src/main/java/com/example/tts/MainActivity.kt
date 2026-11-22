package com.example.tts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.tts.neck.NeckActivity
import com.example.tts.neck.NeckDebtCalculator
import com.example.tts.neck.NeckLiveState
import com.example.tts.stretch.Stretch
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Locale

/** Setup and status home. Monitoring itself runs in NeckSensingService, independent of this screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var tracker: SedentaryTracker
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var progressTotal: LinearProgressIndicator
    private lateinit var switchMonitoring: SwitchCompat
    private lateinit var tvNeckDebt: TextView
    private lateinit var tvSitDebt: TextView
    private lateinit var tvSitState: TextView
    private lateinit var tvLastEval: TextView
    private lateinit var tvLog: TextView

    /** Set while refreshUi syncs the switch, so the change isn't taken as a user tap. */
    private var syncingSwitch = false

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
            Toast.makeText(this, R.string.permission_missing, Toast.LENGTH_LONG).show()
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdge()

        tracker = SedentaryTracker(this)
        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvTotal = findViewById(R.id.tvTotal)
        tvThreshold = findViewById(R.id.tvThreshold)
        progressTotal = findViewById(R.id.progressTotal)
        switchMonitoring = findViewById(R.id.switchMonitoring)
        tvNeckDebt = findViewById(R.id.tvNeckDebt)
        tvSitDebt = findViewById(R.id.tvSitDebt)
        tvSitState = findViewById(R.id.tvSitState)
        tvLastEval = findViewById(R.id.tvLastEval)
        tvLog = findViewById(R.id.tvLog)

        tvThreshold.text = "/ %.0f".format(Stretch.THRESHOLD)
        switchMonitoring.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (checked) turnOn() else {
                Stretch.disable(this)
                refreshUi()
            }
        }
        findViewById<View>(R.id.rowOpenNeck).setOnClickListener {
            startActivity(Intent(this, NeckActivity::class.java))
        }
        findViewById<View>(R.id.rowResetDebts).setOnClickListener { confirmResetDebts() }
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
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

    /** Draw behind the system bars on every API level and pad the scroll content by their size instead. */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        // Otherwise 3-button navigation gets a white scrim that doesn't match the grouped background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun turnOn() {
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

    private fun confirmResetDebts() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reset_d_title)
            .setMessage(R.string.reset_d_message)
            .setPositiveButton(R.string.reset) { _, _ -> resetDebts() }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.red))
    }

    /** The service holds D_n in memory, so while it runs: stop it, reset once it is gone, then start it again. */
    private fun resetDebts() {
        if (!NeckLiveState.running) {
            Stretch.resetDebts(this)
            onResetDone()
            return
        }
        Stretch.disable(this)
        val deadline = System.currentTimeMillis() + RESET_WAIT_MS
        refreshHandler.post(object : Runnable {
            override fun run() {
                if (NeckLiveState.running && System.currentTimeMillis() < deadline) {
                    refreshHandler.postDelayed(this, 100)
                    return
                }
                Stretch.resetDebts(this@MainActivity)
                Stretch.enable(this@MainActivity)
                onResetDone()
            }
        })
    }

    private fun onResetDone() {
        Toast.makeText(this, R.string.reset_d_done, Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun refreshUi() {
        val enabled = Stretch.isEnabled(this)
        val running = NeckLiveState.running

        syncingSwitch = true
        switchMonitoring.isChecked = enabled
        syncingSwitch = false

        val green = ContextCompat.getColor(this, R.color.green)
        val secondary = ContextCompat.getColor(this, R.color.secondary_label)
        statusDot.background.mutate().setTint(
            ContextCompat.getColor(this, if (running) R.color.green_dot else R.color.gray)
        )
        val (state, detail) = when {
            running -> getString(R.string.status_tracking) to " · " + getString(R.string.status_tracking_detail)
            enabled -> getString(R.string.status_starting) to ""
            else -> getString(R.string.status_off) to " · " + getString(R.string.status_off_detail)
        }
        tvStatus.setTextColor(secondary)
        tvStatus.text = SpannableString(state + detail).apply {
            if (running) setSpan(ForegroundColorSpan(green), 0, state.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val dn = getSharedPreferences(NeckDebtCalculator.PREFS, MODE_PRIVATE)
            .getFloat(NeckDebtCalculator.KEY_DEBT, 0f).toDouble()
        val ds = tracker.getDebt()
        val total = Stretch.W_N * dn + Stretch.W_S * ds
        val fraction = (total / Stretch.THRESHOLD).coerceIn(0.0, 1.0)
        tvTotal.text = ONE_DECIMAL.format(total)
        progressTotal.setProgressCompat((fraction * progressTotal.max).toInt(), false)
        progressTotal.setIndicatorColor(
            ContextCompat.getColor(this, if (fraction >= WARN_FRACTION) R.color.orange else R.color.tint)
        )

        tvNeckDebt.text = ONE_DECIMAL.format(dn)
        tvSitDebt.text = ONE_DECIMAL.format(ds)
        tvSitState.text = when {
            !tracker.isMonitoring() -> getString(R.string.tracking_off)
            tracker.isStill() -> getString(R.string.sit_still)
            else -> getString(R.string.sit_moving)
        }
        tvLastEval.text = Stretch.lastEvalAt(this).takeIf { it > 0 }
            ?.let { getString(R.string.last_eval, TIME_FORMAT.format(it)) } ?: getString(R.string.not_calculated)

        tvLog.text = tracker.getLog().lines().filter { it.isNotBlank() }
            .joinToString("\n") { describeLogLine(it) }
            .ifBlank { getString(R.string.no_records) }
    }

    /** Stored lines look like "STILL ENTER @ 18:59:33"; show them in plain words. */
    private fun describeLogLine(line: String): String {
        val (event, time) = line.split(" @ ").let { it[0] to it.getOrElse(1) { "" } }
        val label = LOG_LABELS[event]?.let { getString(it) } ?: event
        return if (time.isEmpty()) label else "$time   $label"
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val RESET_WAIT_MS = 3000L
        private const val WARN_FRACTION = 0.8
        private const val ONE_DECIMAL = "%.1f"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val LOG_LABELS = mapOf(
            "STILL ENTER" to R.string.log_still_enter,
            "STILL EXIT" to R.string.log_still_exit,
            "WALKING ENTER" to R.string.log_walking_enter,
            "RUNNING ENTER" to R.string.log_running_enter,
        )
    }
}
