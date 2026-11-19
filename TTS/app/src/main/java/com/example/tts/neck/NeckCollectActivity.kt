package com.example.tts.neck

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.tts.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** Mode B data collection screen: front camera face/shoulder features + phone tilt, recorded to frame CSV. */
class NeckCollectActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: KeypointOverlayView
    private lateinit var tvFeatures: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnCalibrate: Button
    private lateinit var sensorManager: SensorManager

    /** Reference (upright) value of nose–neck / eye distance; null until calibrated. */
    private var normRef: Float? = null
    /** Calibration in progress: end time and the ratios of frames that met the reference-posture conditions. */
    private var calibrationEndMs: Long? = null
    private val calibrationRatios = mutableListOf<Float>()
    /** Watanabe neck angle model; null if the asset is missing. */
    private var neckModel: WatanabeNeckModel? = null

    /** Latest gravity vector; replaced (not mutated) so the analysis thread reads a consistent copy. */
    @Volatile
    private var latestGravity: FloatArray? = null
    private var recorder: FrameCsvRecorder? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )
    private val poseDetector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_neck_collect)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvFeatures = findViewById(R.id.tvFeatures)
        btnRecord = findViewById(R.id.btnRecord)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        btnRecord.setOnClickListener { if (recorder == null) startRecording() else stopRecording() }
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnCalibrate.setOnClickListener { startCalibration() }
        normRef = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_NORM_REF, Float.NaN).takeUnless { it.isNaN() }
        neckModel = WatanabeNeckModel.load(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        (sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopRecording()
        calibrationEndMs = null
        btnCalibrate.isEnabled = true
        btnCalibrate.text = "기준 자세 측정"
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestGravity = event.values.copyOf(3)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun startRecording() {
        val name = "frames_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        recorder = FrameCsvRecorder(File(NeckCsvLogger.logDir(this), name))
        btnRecord.text = "기록 중지 (0프레임)"
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        r.close()
        btnRecord.text = "기록 시작"
        Toast.makeText(this, "${r.frameCount}프레임 저장: ${r.file.name}", Toast.LENGTH_LONG).show()
    }

    private fun startCalibration() {
        calibrationRatios.clear()
        calibrationEndMs = System.currentTimeMillis() + CALIBRATION_MS
        btnCalibrate.isEnabled = false
        btnCalibrate.text = "기준 자세 측정 중…"
    }

    /** Collects reference-posture frames for 3 s, then stores the median ratio as Norm_ref. */
    private fun updateCalibration(tsMs: Long, gravity: FloatArray?, face: FaceFeatures?, shoulders: ShoulderFeatures?) {
        val endMs = calibrationEndMs ?: return
        val tilt = gravity?.let { TiltCalculator.tiltDegrees(it[0], it[1], it[2]) }
        val ratio = ModeBFeatures.rawRatio(face, shoulders)
        if (tilt != null && abs(tilt) <= CAL_MAX_TILT_DEG &&
            face != null && abs(face.pitch) <= CAL_MAX_PITCH_DEG &&
            shoulders != null && shoulders.leftLikelihood >= MIN_SHOULDER_LIKELIHOOD &&
            shoulders.rightLikelihood >= MIN_SHOULDER_LIKELIHOOD && ratio != null
        ) {
            calibrationRatios.add(ratio)
        }
        btnCalibrate.text = "기준 자세 측정 중… (${calibrationRatios.size}프레임)"
        if (tsMs < endMs) return

        calibrationEndMs = null
        btnCalibrate.isEnabled = true
        btnCalibrate.text = "기준 자세 측정"
        if (calibrationRatios.size < CAL_MIN_FRAMES) {
            Toast.makeText(
                this,
                "측정 실패: 조건 만족 ${calibrationRatios.size}프레임 (폰 수직 ±15°, 얼굴 pitch ±10°, 양 어깨 검출)",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val sorted = calibrationRatios.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        normRef = median
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_NORM_REF, median).apply()
        Toast.makeText(this, "Norm_ref ${fmt(median)} 저장 (${sorted.size}프레임)", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        faceDetector.close()
        poseDetector.close()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val aspect43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect43).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect43)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, "카메라를 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val tsMs = System.currentTimeMillis()
        val gravity = latestGravity
        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width

        val input = InputImage.fromMediaImage(mediaImage, rotation)
        val faceTask = faceDetector.process(input)
        val poseTask = poseDetector.process(input)
        Tasks.whenAllComplete(faceTask, poseTask)
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) {
                val face = if (faceTask.isSuccessful) {
                    faceTask.result.maxByOrNull { it.boundingBox.width() }?.let { FaceFeatures.from(it) }
                } else {
                    Log.w(TAG, "Face detection failed", faceTask.exception)
                    null
                }
                val shoulders = if (poseTask.isSuccessful) {
                    ShoulderFeatures.from(poseTask.result)
                } else {
                    Log.w(TAG, "Pose detection failed", poseTask.exception)
                    null
                }
                updateCalibration(tsMs, gravity, face, shoulders)
                val neckDeg = predictNeck(gravity, face, shoulders)
                showFeatures(face, shoulders, uprightWidth, uprightHeight, neckDeg)
                recordFrame(tsMs, uprightWidth, uprightHeight, gravity, face, shoulders, neckDeg)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun recordFrame(
        tsMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        gravity: FloatArray?,
        face: FaceFeatures?,
        shoulders: ShoulderFeatures?,
        neckDeg: Double?,
    ) {
        val r = recorder ?: return
        r.write(
            listOf(
                tsMs, imageWidth, imageHeight,
                gravity?.let { TiltCalculator.tiltDegrees(it[0], it[1], it[2]) },
                gravity?.let { TiltCalculator.rollDegrees(it[0], it[1]) },
                face?.pitch, face?.yaw, face?.roll,
                face?.nose?.x, face?.nose?.y,
                face?.leftEye?.x, face?.leftEye?.y,
                face?.rightEye?.x, face?.rightEye?.y,
                shoulders?.left?.x, shoulders?.left?.y, shoulders?.leftLikelihood,
                shoulders?.right?.x, shoulders?.right?.y, shoulders?.rightLikelihood,
                normRef, neckDeg,
            )
        )
        btnRecord.text = "기록 중지 (${r.frameCount}프레임)"
    }

    /** Neck angle (°) from the Watanabe model; null without model, Norm_ref, phone tilt, face or both shoulders. */
    private fun predictNeck(gravity: FloatArray?, face: FaceFeatures?, shoulders: ShoulderFeatures?): Double? =
        ModeBFeatures.predictNeck(
            neckModel, normRef, gravity?.let { TiltCalculator.tiltDegrees(it[0], it[1], it[2]) }, face, shoulders,
        )

    private fun showFeatures(
        face: FaceFeatures?,
        shoulders: ShoulderFeatures?,
        imageWidth: Int,
        imageHeight: Int,
        neckDeg: Double?,
    ) {
        overlayView.setPoints(
            imageWidth,
            imageHeight,
            listOfNotNull(face?.nose, face?.leftEye, face?.rightEye, shoulders?.left, shoulders?.right, shoulders?.neck),
        )
        val eyeDist = face?.eyeDistance()
        val faceText = if (face == null) {
            "얼굴: 없음"
        } else {
            "얼굴 pitch ${fmt(face.pitch)}° · yaw ${fmt(face.yaw)}° · roll ${fmt(face.roll)}°\n" +
                "눈 사이 거리: ${eyeDist?.let { fmt(it) + " px" } ?: "-"} (${imageWidth}×${imageHeight})"
        }
        val shoulderText = if (shoulders == null) {
            "어깨: 없음"
        } else {
            "어깨 신뢰도 L ${fmt(shoulders.leftLikelihood)} · R ${fmt(shoulders.rightLikelihood)}\n" +
                "코–목 / 눈 사이: ${ModeBFeatures.rawRatio(face, shoulders)?.let { fmt(it) } ?: "-"}"
        }
        tvFeatures.text = faceText + "\n" + shoulderText + "\nNorm_ref: ${normRef?.let { fmt(it) } ?: "미설정"}" +
            "\n목 각도: " + when {
                neckModel == null -> "모델 파일 없음"
                neckDeg == null -> "-"
                else -> String.format(Locale.US, "%.1f°", neckDeg)
            }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    companion object {
        private const val TAG = "NeckCollectActivity"
        private const val PREFS = ModeBFeatures.PREFS
        private const val KEY_NORM_REF = ModeBFeatures.KEY_NORM_REF
        private const val CALIBRATION_MS = 3000L
        private const val CAL_MAX_TILT_DEG = 15.0
        private const val CAL_MAX_PITCH_DEG = 10f
        private const val CAL_MIN_FRAMES = 5
        private const val MIN_SHOULDER_LIKELIHOOD = ModeBFeatures.MIN_SHOULDER_LIKELIHOOD
    }
}
