package com.example.tts.neck

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Mode C data collection screen: front camera face landmarks + phone tilt, recorded to frame CSV. */
class NeckCollectActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: KeypointOverlayView
    private lateinit var tvFeatures: TextView
    private lateinit var btnRecord: Button
    private lateinit var sensorManager: SensorManager

    private lateinit var btnCalibrateC: Button
    /** Mode C calibration in progress; null when not calibrating. */
    private var calibrationC: ModeCCalibration? = null
    /** Mode C per-user face model; null until calibrated. */
    private var faceModel: FaceModel? = null
    /** Front camera focal length on the sensor array, and in analysis-image pixels once the first frame arrives. */
    private var arrayFocal: CameraIntrinsics.ArrayFocal? = null
    private var focalPx: Double? = null
    /** Last mode C pose, used as the PnP starting point for the next frame. */
    private var lastPose: FacePose? = null
    private var lastPoseMs = 0L

    private lateinit var btnCalibrateG: Button
    /** g() calibration in progress (design doc §11.11); null when not calibrating. */
    private var calibrationG: GCalibration? = null

    /** Latest gravity vector; replaced (not mutated) so the analysis thread reads a consistent copy. */
    @Volatile
    private var latestGravity: FloatArray? = null
    /** Latest device→world rotation matrix from TYPE_GAME_ROTATION_VECTOR (mode C calibration). */
    @Volatile
    private var latestRotation: DoubleArray? = null
    private var recorder: FrameCsvRecorder? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
        btnCalibrateC = findViewById(R.id.btnCalibrateC)
        btnCalibrateC.setOnClickListener { if (calibrationC == null) startCalibrationC() else stopCalibrationC("모드 C 캘리브레이션 취소") }
        faceModel = FaceModel.load(getSharedPreferences(PREFS, MODE_PRIVATE))
        btnCalibrateG = findViewById(R.id.btnCalibrateG)
        btnCalibrateG.setOnClickListener { if (calibrationG == null) startCalibrationG() else stopCalibrationG("g() 캘리브레이션 취소") }
        arrayFocal = CameraIntrinsics.frontArrayFocal(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        NeckLiveState.collectScreenOpen = true
        (sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        NeckLiveState.collectScreenOpen = false
        sensorManager.unregisterListener(this)
        stopRecording()
        if (calibrationC != null) stopCalibrationC(null)
        if (calibrationG != null) stopCalibrationG(null)
        lastPose = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val m = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(m, event.values)
            latestRotation = DoubleArray(9) { m[it].toDouble() }
        } else {
            latestGravity = event.values.copyOf(3)
        }
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

    private fun startCalibrationC() {
        val message = when {
            calibrationG != null -> "g() 캘리브레이션 중에는 시작할 수 없습니다"
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null -> "회전 벡터 센서가 없어 모드 C 캘리브레이션을 할 수 없습니다"
            arrayFocal == null -> "카메라 초점 거리를 읽지 못해 모드 C 캘리브레이션을 할 수 없습니다"
            else -> null
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        calibrationC = ModeCCalibration()
        btnCalibrateC.text = "모드 C 캘리브레이션 취소"
    }

    private fun stopCalibrationC(message: String?) {
        calibrationC = null
        btnCalibrateC.text = "모드 C 캘리브레이션"
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    /** Feeds one frame to the mode C calibration (design doc §11.4); after the last step builds the face model. */
    private fun updateCalibrationC(
        tsMs: Long,
        gravity: FloatArray?,
        rotation: DoubleArray?,
        face: FaceFeatures?,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        val cal = calibrationC ?: return
        val landmarks = face?.modeCLandmarks()?.map { it?.toXY() } ?: List(FaceModel.LANDMARKS.size) { null }
        when (cal.onFrame(tsMs, landmarks, face?.center?.toXY(), imageWidth, imageHeight, rotation, gravity, face?.pitch?.toDouble())) {
            ModeCCalibration.Status.RETRY ->
                Toast.makeText(this, "${cal.step.label}: 조건 만족 프레임 부족, 다시 맞춰 주세요", Toast.LENGTH_SHORT).show()
            ModeCCalibration.Status.STEP_DONE ->
                Toast.makeText(this, "${cal.results.last().step.label} 완료", Toast.LENGTH_SHORT).show()
            ModeCCalibration.Status.ALL_DONE -> finishCalibrationC(cal, imageWidth, imageHeight)
            else -> Unit
        }
    }

    /** Reconstructs the face model (design doc §11.5), writes the calibration CSVs, and stores the model if it is consistent. */
    private fun finishCalibrationC(cal: ModeCCalibration, imageWidth: Int, imageHeight: Int) {
        stopCalibrationC(null)
        val f = focalPx ?: return
        val rec = FaceModelCalibrator.reconstruct(cal.results.map { it.view }, f, imageWidth / 2.0, imageHeight / 2.0)
        val alphaRef = cal.alphaRefDeg!!
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        ModeCCalibration.writeCsv(NeckCsvLogger.logDir(this), name, cal.results, rec, alphaRef, f)
        if (!rec.ok) {
            val worst = rec.viewRms.indices.maxBy { rec.viewRms[it] }
            Toast.makeText(
                this,
                "모드 C 복원 실패: ${cal.results[worst].step.label} 재투영 오차 ${fmtD(rec.viewRms[worst])} px. 머리를 고정하고 다시 측정해 주세요",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val model = FaceModel(rec.points, alphaRef, rec.reprojRms)
        model.save(getSharedPreferences(PREFS, MODE_PRIVATE))
        faceModel = model
        lastPose = null
        Toast.makeText(this, "모드 C 모델 저장 (재투영 ${fmtD(rec.reprojRms)} px)", Toast.LENGTH_LONG).show()
    }

    /** Guidance for the current mode C calibration step; null when not calibrating. */
    private fun calibrationCText(gravity: FloatArray?, rotation: DoubleArray?): String? {
        val cal = calibrationC ?: return null
        val step = cal.step
        val now = if (step == CalibrationStep.FRONT) {
            "폰 기울기 ${gravity?.let { fmtD(TiltCalculator.tiltDegrees(it[0], it[1], it[2])) } ?: "-"}° (목표 0°)"
        } else {
            val label = rotation?.let { cal.label(it) }
            "얼굴 pitch ${label?.let { fmtD(it.first) } ?: "-"}° / yaw ${label?.let { fmtD(it.second) } ?: "-"}° " +
                "(목표 ${fmtD(step.pitch)}° / ${fmtD(step.yaw)}°)"
        }
        return "[모드 C 캘리브레이션 ${cal.results.size + 1}/${CalibrationStep.entries.size}] ${step.label}\n" +
            "뒤통수를 벽에 붙여 머리를 고정하고, 폰을 이 위치에서 얼굴이 화면 가운데 오도록 드세요\n" +
            "$now · 모은 프레임 ${cal.collectedFrames}"
    }

    private fun startCalibrationG() {
        val message = when {
            calibrationC != null -> "모드 C 캘리브레이션 중에는 시작할 수 없습니다"
            faceModel == null -> "모드 C 캘리브레이션을 먼저 해 주세요"
            arrayFocal == null -> "카메라 초점 거리를 읽지 못해 g() 캘리브레이션을 할 수 없습니다"
            else -> null
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        calibrationG = GCalibration()
        btnCalibrateG.text = "g() 캘리브레이션 취소"
    }

    private fun stopCalibrationG(message: String?) {
        calibrationG = null
        btnCalibrateG.text = "g() 캘리브레이션"
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    /** Feeds one frame to the g() calibration (design doc §11.11); after the last step replaces the stored g(). */
    private fun updateCalibrationG(tsMs: Long, gravity: FloatArray?, pose: FacePose?, neckC: Double?) {
        val cal = calibrationG ?: return
        val tilt = gravity?.let { TiltCalculator.tiltDegrees(it[0], it[1], it[2]) }
        when (cal.onFrame(tsMs, tilt, neckC, pose?.reprojRms)) {
            GCalibration.Status.TIMEOUT -> {
                val r = cal.lastRejects!!
                val text = if (r.onTarget == 0) {
                    "폰 기울기를 ${fmtD(cal.step.targetDeg)}° ±${fmtD(GCalibration.TILT_TOLERANCE_DEG)}° 에 맞춰 주세요"
                } else {
                    "${fmtD(cal.step.targetDeg)}°: 기울기가 맞은 ${r.onTarget}프레임 중 얼굴 없음 ${r.noFace}, 재투영 오차 초과 ${r.highRms}"
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
            GCalibration.Status.STEP_DONE -> {
                val last = cal.results.last()
                Toast.makeText(this, "${fmtD(last.step.targetDeg)}° 완료 (θ_C ${fmtD(last.thetaC)}°)", Toast.LENGTH_SHORT).show()
            }
            GCalibration.Status.ALL_DONE -> finishCalibrationG(cal)
            else -> Unit
        }
    }

    private fun finishCalibrationG(cal: GCalibration) {
        stopCalibrationG(null)
        val mapping = cal.mapping()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mapping.save(prefs)
        prefs.edit().putString(NeckSensingService.KEY_G_SOURCE, NeckSensingService.G_SOURCE_MODE_C).apply()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        GCalibration.writeCsv(NeckCsvLogger.logDir(this), name, cal.results, mapping)
        Toast.makeText(
            this,
            "g() 저장: θ_C ≈ ${mapping.slope?.let { fmtD(it) } ?: "-"}·θ_phone + ${mapping.intercept?.let { fmtD(it) } ?: "-"} " +
                "(잔차 ${mapping.residualStd?.let { fmtD(it) } ?: "-"}°)",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Guidance for the current g() calibration step; null when not calibrating. */
    private fun calibrationGText(gravity: FloatArray?): String? {
        val cal = calibrationG ?: return null
        val step = cal.step
        val index = cal.results.size
        val lower = if (step.round > 1 && index == GCalibration.TARGETS_DEG.size && cal.collectedFrames == 0) {
            "\n폰을 한 번 내려놓았다가 다시 드세요"
        } else {
            ""
        }
        val tilt = gravity?.let { fmtD(TiltCalculator.tiltDegrees(it[0], it[1], it[2])) } ?: "-"
        return "[g() 캘리브레이션 ${index + 1}/${cal.steps.size}] 목표 폰 기울기 ${fmtD(step.targetDeg)}°$lower\n" +
            "바로 앉아 폰 기울기만 맞추고, 아래 글을 편하게 읽으세요\n" +
            "지금 폰 기울기 $tilt° · 모은 프레임 ${cal.collectedFrames}\n\n" + READING_TEXT
    }

    private fun PointF.toXY() = doubleArrayOf(x.toDouble(), y.toDouble())

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        faceDetector.close()
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
        val deviceRotation = latestRotation
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bufferWidth = imageProxy.width
        val bufferHeight = imageProxy.height
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width

        val input = InputImage.fromMediaImage(mediaImage, rotation)
        faceDetector.process(input)
            .addOnCompleteListener(ContextCompat.getMainExecutor(this)) { faceTask ->
                val face = if (faceTask.isSuccessful) {
                    faceTask.result.maxByOrNull { it.boundingBox.width() }?.let { FaceFeatures.from(it) }
                } else {
                    Log.w(TAG, "Face detection failed", faceTask.exception)
                    null
                }
                if (focalPx == null) focalPx = arrayFocal?.let { CameraIntrinsics.focalPx(it, bufferWidth, bufferHeight) }
                updateCalibrationC(tsMs, gravity, deviceRotation, face, uprightWidth, uprightHeight)
                val pose = estimateModeC(tsMs, face, uprightWidth, uprightHeight)
                val neckC = pose?.let { p -> gravity?.let { modeCFlexion(p, it) } }
                updateCalibrationG(tsMs, gravity, pose, neckC)
                val guide = calibrationCText(gravity, deviceRotation) ?: calibrationGText(gravity)
                showFeatures(face, uprightWidth, uprightHeight, guide, pose, neckC)
                recordFrame(tsMs, uprightWidth, uprightHeight, gravity, face, pose, neckC)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Mode B columns (shoulders, norm_ref, neck_pred_deg) stay in the header but are always empty. */
    private fun recordFrame(
        tsMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        gravity: FloatArray?,
        face: FaceFeatures?,
        pose: FacePose?,
        neckC: Double?,
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
                null, null, null, // sh_l_x, sh_l_y, sh_l_likelihood
                null, null, null, // sh_r_x, sh_r_y, sh_r_likelihood
                null, null, // norm_ref, neck_pred_deg
                face?.mouthLeft?.x, face?.mouthLeft?.y,
                face?.mouthRight?.x, face?.mouthRight?.y,
                face?.mouthBottom?.x, face?.mouthBottom?.y,
                face?.leftCheek?.x, face?.leftCheek?.y,
                face?.rightCheek?.x, face?.rightCheek?.y,
                face?.center?.x, face?.center?.y,
                pose?.pitchDeg, pose?.yawDeg, pose?.distCm, pose?.reprojRms, neckC,
            )
        )
        btnRecord.text = "기록 중지 (${r.frameCount}프레임)"
    }

    /** Mode C face pose by PnP on the user's face model (design doc §11.6); null without model, focal length or landmarks. */
    private fun estimateModeC(tsMs: Long, face: FaceFeatures?, imageWidth: Int, imageHeight: Int): FacePose? {
        val model = faceModel ?: return null
        val f = focalPx ?: return null
        val landmarks = face?.modeCLandmarks()?.map { it?.toXY() } ?: return null
        val previous = lastPose?.takeIf { tsMs - lastPoseMs <= POSE_REUSE_MS }
        val pose = FacePoseSolver.solve(model.points, landmarks, f, imageWidth / 2.0, imageHeight / 2.0, previous)
        if (pose != null) {
            lastPose = pose
            lastPoseMs = tsMs
        }
        return pose
    }

    /** θ_C: head flexion relative to gravity minus α_ref (design doc §11.3). */
    private fun modeCFlexion(pose: FacePose, gravity: FloatArray): Double =
        ModeCGeometry.headFlexion(pose.rotation, gravity[0], gravity[1], gravity[2]) - faceModel!!.alphaRefDeg

    private fun showFeatures(
        face: FaceFeatures?,
        imageWidth: Int,
        imageHeight: Int,
        calibrationCText: String?,
        pose: FacePose?,
        neckC: Double?,
    ) {
        overlayView.setPoints(
            imageWidth,
            imageHeight,
            face?.let { (it.modeCLandmarks() + it.center).filterNotNull() } ?: emptyList(),
        )
        val faceText = if (face == null) {
            "얼굴: 없음"
        } else {
            "얼굴 pitch ${fmt(face.pitch)}° · yaw ${fmt(face.yaw)}° · roll ${fmt(face.roll)}°"
        }
        tvFeatures.text = (calibrationCText?.let { it + "\n" } ?: "") + faceText +
            "\n모드 C: " + when {
                faceModel == null -> "캘리브레이션 필요"
                pose == null -> "-"
                else -> "얼굴 pitch ${fmtD(pose.pitchDeg)}° · yaw ${fmtD(pose.yawDeg)}° · ${fmtD(pose.distCm)} cm · " +
                    "오차 ${fmtD(pose.reprojRms)} px\n머리 숙임 θ_C: ${neckC?.let { fmtD(it) + "°" } ?: "-"}"
            }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun fmtD(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val TAG = "NeckCollectActivity"
        private const val PREFS = ModeBFeatures.PREFS
        /** A mode C pose newer than this (ms) starts the next frame's PnP. */
        private const val POSE_REUSE_MS = 500L
        /** Shown during the g() calibration so the user looks at the screen as when reading. */
        private const val READING_TEXT =
            "오래 앉아 있으면 목과 어깨가 굳기 쉽습니다. 한 시간에 한 번쯤 일어나서 어깨를 천천히 돌리고, " +
                "고개를 좌우로 기울여 목 옆을 늘려 주세요. 화면을 볼 때는 폰을 눈높이에 가깝게 들면 목에 가는 부담이 줄어듭니다."
    }
}
