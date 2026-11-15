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
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Mode A (SmartPose-based) background sensing, P0 logging only.
 * While the screen is on and unlocked, samples gravity (tilt) and linear acceleration,
 * aggregates 5-second windows / 60-second cycles, and writes them to CSV.
 */
class NeckSensingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
    private lateinit var logger: NeckCsvLogger

    private val aggregator = PostureWindowAggregator()
    private val handler = Handler(Looper.getMainLooper())
    private var sensorsRegistered = false
    private var screenInUse = false

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
        logger = NeckCsvLogger(this)

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
        unregisterReceiver(screenReceiver)
        unregisterSensors()
        aggregator.reset()
        NeckLiveState.running = false
        NeckLiveState.currentTilt = null
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
                if (event.values[0] < event.sensor.maximumRange) aggregator.markExcluded()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun onGravity(gx: Float, gy: Float, gz: Float) {
        val tilt = TiltCalculator.tiltDegrees(gx, gy, gz)
        aggregator.addTilt(tilt)
        NeckLiveState.currentTilt = tilt
        if (isInCall()) aggregator.markExcluded()
    }

    private fun closeWindow() {
        updateScreenInUse()
        val (window, cycle) = aggregator.closeWindow(System.currentTimeMillis(), screenInUse)
        logger.logWindow(window)
        if (cycle != null) {
            logger.logCycle(cycle)
            NeckLiveState.lastCycle = cycle
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
            unregisterSensors()
            NeckLiveState.currentTilt = null
        }
    }

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
        private const val LOW_PASS_ALPHA = 0.8f
        private const val CHANNEL_ID = "neck_sensing"
        private const val NOTIFICATION_ID = 2001
    }
}
