package com.example.tts.neck

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Mode A (SmartPose-based) background sensing.
 * While the screen is on and unlocked, samples gravity (tilt) and linear acceleration,
 * aggregates 5-second windows / 60-second cycles, judges each cycle, updates D_n, and writes CSV.
 */
class NeckSensingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
    private var defaultDisplay: Display? = null
    private lateinit var logger: NeckCsvLogger

    private val aggregator = PostureWindowAggregator()
    private val debtCalculator = NeckDebtCalculator()
    private var debt = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var sensorsRegistered = false
    private var screenInUse = false
    private lateinit var sampler: NeckCameraSampler
    private var lastSampleAttemptMs = 0L
    private var mapping = NeckMapping()
    private val estimator = NeckEstimator()
    /** Latest gravity vector for the mode C camera bursts; null while the screen is not in use. */
    private var latestGravity: FloatArray? = null

    // Low-pass filtered accelerometer, used only when TYPE_GRAVITY is unavailable.
    private val fallbackGravity = FloatArray(3)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateScreenInUse()
    }

    private val windowTick = object : Runnable {
        override fun run() {
            closeWindow()
            handler.postDelayed(this, WINDOW_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        defaultDisplay = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        logger = NeckCsvLogger(this)
        sampler = NeckCameraSampler(this, gravity = { latestGravity }, onFrameNeck = { neck ->
            NeckLiveState.frameThetaC = neck
            NeckLiveState.frameThetaCMs = System.currentTimeMillis()
        })
        debt = prefs().getFloat(NeckDebtCalculator.KEY_DEBT, 0f).toDouble()
        mapping = NeckMapping.load(prefs())
        if (prefs().getString(KEY_G_SOURCE, null) != G_SOURCE_MODE_C) {
            // g() fitted on mode B neck angles must not mix with mode C pairs (design doc §11.10): start over once.
            mapping = NeckMapping()
            mapping.save(prefs())
            prefs().edit().putString(KEY_G_SOURCE, G_SOURCE_MODE_C).apply()
        }

        startInForeground()

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        screenInUse = isScreenInUse()
        aggregator.startWindow(System.currentTimeMillis(), screenInUse)
        if (screenInUse) registerSensors()
        handler.postDelayed(windowTick, WINDOW_MS)

        NeckLiveState.running = true
        NeckLiveState.screenInUse = screenInUse
        NeckLiveState.logDir = logger.dir.absolutePath
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(windowTick)
        sampler.release()
        unregisterReceiver(screenReceiver)
        unregisterSensors()
        aggregator.reset()
        NeckLiveState.running = false
        NeckLiveState.currentTilt = null
        NeckLiveState.frameThetaC = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> onGravity(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    fallbackGravity[i] = LOW_PASS_ALPHA * fallbackGravity[i] + (1 - LOW_PASS_ALPHA) * event.values[i]
                }
                onGravity(fallbackGravity[0], fallbackGravity[1], fallbackGravity[2])
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val (x, y, z) = event.values
                aggregator.addLinearAccMagnitude(sqrt((x * x + y * y + z * z).toDouble()))
            }
            Sensor.TYPE_PROXIMITY -> {
                if (event.values[0] < event.sensor.maximumRange) aggregator.markExcluded("proximity")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun onGravity(gx: Float, gy: Float, gz: Float) {
        val tilt = TiltCalculator.tiltDegrees(gx, gy, gz)
        aggregator.addTilt(tilt)
        val rotation = defaultDisplay?.rotation ?: 0
        val (cx, cy) = TiltCalculator.toContentFrame(gx, gy, rotation)
        aggregator.addContentGravity(cx, cy, rotation)
        NeckLiveState.currentTilt = tilt
        latestGravity = floatArrayOf(gx, gy, gz)
        if (isInCall()) aggregator.markExcluded("call")
    }

    private fun closeWindow() {
        updateScreenInUse()
        val (window, cycle) = aggregator.closeWindow(System.currentTimeMillis(), screenInUse)
        logger.logWindow(window)
        maybeStartSample(window)
        if (cycle != null) {
            // Re-read: the g() calibration on the collection screen may have replaced it (design doc §11.11).
            mapping = NeckMapping.load(prefs())
            val gCata = cycle.cata?.let { mapping.predict(it) }
            val neck = estimator.estimate(cycle, mapping)
            val state = debtCalculator.judge(cycle, neck?.deg)
            debt = debtCalculator.update(debt, state, neck?.deg)
            prefs().edit().putFloat(NeckDebtCalculator.KEY_DEBT, debt.toFloat()).apply()
            logger.logCycle(cycle, state, debt, gCata, if (gCata != null) mapping.residualStd else null, neck)
            NeckLiveState.lastCycle = cycle
            NeckLiveState.lastState = state
            NeckLiveState.lastCycleSample = NeckLiveState.lastSample?.takeIf { it.startMs >= cycle.startMs && it.startMs < cycle.endMs }
            NeckLiveState.lastNeck = neck
        }
    }

    /**
     * Starts a camera burst every [SAMPLE_INTERVAL_MS] when the window just closed was valid (phone in hand, in use)
     * and TRUNK_UPRIGHT, so that g() is fitted only from sitting/standing pairs.
     */
    private fun maybeStartSample(window: WindowResult) {
        val now = System.currentTimeMillis()
        if (!window.valid || window.posture != WindowPosture.TRUNK_UPRIGHT || sampler.busy || NeckLiveState.collectScreenOpen) return
        if (now - lastSampleAttemptMs < SAMPLE_INTERVAL_MS) return
        lastSampleAttemptMs = now
        sampler.start { sample ->
            logger.logSample(sample)
            NeckLiveState.lastSample = sample
            if (sample.ok) {
                mapping = NeckMapping.load(prefs()).add(sample.phoneTilt!!, sample.neckDeg!!)
                mapping.save(prefs())
                estimator.onSample(sample)
            }
        }
    }

    private fun updateScreenInUse() {
        val inUse = isScreenInUse()
        if (inUse == screenInUse) return
        screenInUse = inUse
        aggregator.onScreenInUseChanged(inUse)
        NeckLiveState.screenInUse = inUse
        if (inUse) {
            registerSensors()
        } else {
            sampler.cancel()
            unregisterSensors()
            NeckLiveState.currentTilt = null
            latestGravity = null
        }
    }

    private fun prefs() = getSharedPreferences(NeckDebtCalculator.PREFS, MODE_PRIVATE)

    private fun isScreenInUse(): Boolean = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    private fun isInCall(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun registerSensors() {
        if (sensorsRegistered) return
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    private fun startInForeground() {
        val channel = NotificationChannel(CHANNEL_ID, "자세 측정", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("자세 측정 중")
            .setContentText("폰 기울기를 기록하고 있어요")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val WINDOW_MS = 5_000L
        private const val SAMPLE_INTERVAL_MS = 120_000L
        /** Which camera estimate the stored g() was fitted on; anything else is cleared once on start. */
        const val KEY_G_SOURCE = "g_source"
        const val G_SOURCE_MODE_C = "C"
        private const val LOW_PASS_ALPHA = 0.8f
        private const val CHANNEL_ID = "neck_sensing"
        private const val NOTIFICATION_ID = 2001
    }
}
