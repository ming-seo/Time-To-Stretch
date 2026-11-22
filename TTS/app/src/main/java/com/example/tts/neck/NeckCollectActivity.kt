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
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_neck_collect)
        setTitle(R.string.collect_title)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvFeatures = findViewById(R.id.tvFeatures)
        btnRecord = findViewById(R.id.btnRecord)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        btnRecord.setOnClickListener { if (recorder == null) startRecording() else stopRecording() }
        btnCalibrateC = findViewById(R.id.btnCalibrateC)
        btnCalibrateC.setOnClickListener { if (calibrationC == null) startCalibrationC() else stopCalibrationC(getString(R.string.calib_c_canceled)) }
        faceModel = FaceModel.load(getSharedPreferences(PREFS, MODE_PRIVATE))
        btnCalibrateG = findViewById(R.id.btnCalibrateG)
        btnCalibrateG.setOnClickListener { if (calibrationG == null) startCalibrationG() else stopCalibrationG(getString(R.string.calib_g_canceled)) }
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
        btnRecord.text = getString(R.string.record_stop, 0)
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        r.close()
        btnRecord.setText(R.string.record_start)
        Toast.makeText(this, getString(R.string.frames_saved, r.frameCount, r.file.name), Toast.LENGTH_LONG).show()
    }

    private fun startCalibrationC() {
        val message = when {
            calibrationG != null -> getString(R.string.busy_calib_g)
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null -> getString(R.string.no_rotation_sensor)
            arrayFocal == null -> getString(R.string.no_focal_calib_c)
            else -> null
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        calibrationC = ModeCCalibration()
        btnCalibrateC.setText(R.string.calib_cancel)
    }

    private fun stopCalibrationC(message: String?) {
        calibrationC = null
        btnCalibrateC.setText(R.string.calib_c)
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
                Toast.makeText(this, getString(R.string.calib_c_retry, getString(cal.step.label)), Toast.LENGTH_SHORT).show()
            ModeCCalibration.Status.STEP_DONE ->
                Toast.makeText(this, getString(R.string.step_done, getString(cal.results.last().step.label)), Toast.LENGTH_SHORT).show()
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
                getString(R.string.mode_c_failed, getString(cal.results[worst].step.label), fmtD(rec.viewRms[worst])),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val model = FaceModel(rec.points, alphaRef, rec.reprojRms)
        model.save(getSharedPreferences(PREFS, MODE_PRIVATE))
        faceModel = model
        lastPose = null
        Toast.makeText(this, getString(R.string.mode_c_saved, fmtD(rec.reprojRms)), Toast.LENGTH_LONG).show()
    }

    /** Guidance for the current mode C calibration step; null when not calibrating. */
    private fun calibrationCText(gravity: FloatArray?, rotation: DoubleArray?): String? {
        val cal = calibrationC ?: return null
        val step = cal.step
        val now = if (step == CalibrationStep.FRONT) {
            getString(R.string.calib_c_now_tilt, gravity?.let { fmtD(TiltCalculator.tiltDegrees(it[0], it[1], it[2])) } ?: "-")
        } else {
            val label = rotation?.let { cal.label(it) }
            getString(
                R.string.calib_c_now_face,
                label?.let { fmtD(it.first) } ?: "-", label?.let { fmtD(it.second) } ?: "-", fmtD(step.pitch), fmtD(step.yaw),
            )
        }
        return getString(R.string.calib_c_header, cal.results.size + 1, CalibrationStep.entries.size, getString(step.label)) + "\n" +
            getString(R.string.calib_c_instruction) + "\n" +
            getString(R.string.calib_c_progress, now, cal.collectedFrames)
    }

    private fun startCalibrationG() {
        val message = when {
            calibrationC != null -> getString(R.string.busy_calib_c)
            faceModel == null -> getString(R.string.need_calib_c_first)
            arrayFocal == null -> getString(R.string.no_focal_calib_g)
            else -> null
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        calibrationG = GCalibration()
        btnCalibrateG.setText(R.string.calib_cancel)
    }

    private fun stopCalibrationG(message: String?) {
        calibrationG = null
        btnCalibrateG.setText(R.string.calib_g)
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
                    getString(R.string.calib_g_tilt_hint, fmtD(cal.step.targetDeg), fmtD(GCalibration.TILT_TOLERANCE_DEG))
                } else {
                    getString(R.string.calib_g_rejects, fmtD(cal.step.targetDeg), r.onTarget, r.noFace, r.highRms)
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
            GCalibration.Status.STEP_DONE -> {
                val last = cal.results.last()
                Toast.makeText(this, getString(R.string.calib_g_step_done, fmtD(last.step.targetDeg), fmtD(last.thetaC)), Toast.LENGTH_SHORT).show()
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
            getString(
                R.string.g_saved,
                mapping.slope?.let { fmtD(it) } ?: "-",
                mapping.intercept?.let { fmtD(it) } ?: "-",
                mapping.residualStd?.let { fmtD(it) } ?: "-",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Guidance for the current g() calibration step; null when not calibrating. */
    private fun calibrationGText(gravity: FloatArray?): String? {
        val cal = calibrationG ?: return null
        val step = cal.step
        val index = cal.results.size
        val lower = if (step.round > 1 && index == GCalibration.TARGETS_DEG.size && cal.collectedFrames == 0) {
            "\n" + getString(R.string.calib_g_lower)
        } else {
            ""
        }
        val tilt = gravity?.let { fmtD(TiltCalculator.tiltDegrees(it[0], it[1], it[2])) } ?: "-"
        return getString(R.string.calib_g_header, index + 1, cal.steps.size, fmtD(step.targetDeg)) + lower + "\n" +
            getString(R.string.calib_g_instruction) + "\n" +
            getString(R.string.calib_g_progress, tilt, cal.collectedFrames) + "\n\n" + getString(R.string.reading_text)
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
                Toast.makeText(this, getString(R.string.camera_open_failed, e.message), Toast.LENGTH_LONG).show()
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
        btnRecord.text = getString(R.string.record_stop, r.frameCount)
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
            getString(R.string.face_none)
        } else {
            getString(R.string.face_angles, fmt(face.pitch), fmt(face.yaw), fmt(face.roll))
        }
        tvFeatures.text = (calibrationCText?.let { it + "\n" } ?: "") + faceText +
            "\n" + getString(
                R.string.mode_c_line,
                when {
                    faceModel == null -> getString(R.string.mode_c_need_calib)
                    pose == null -> "-"
                    else -> getString(
                        R.string.mode_c_pose,
                        fmtD(pose.pitchDeg), fmtD(pose.yawDeg), fmtD(pose.distCm), fmtD(pose.reprojRms),
                        neckC?.let { fmtD(it) + "°" } ?: "-",
                    )
                },
            )
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun fmtD(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val TAG = "NeckCollectActivity"
        private const val PREFS = ModeBFeatures.PREFS
        /** A mode C pose newer than this (ms) starts the next frame's PnP. */
        private const val POSE_REUSE_MS = 500L
    }
}
