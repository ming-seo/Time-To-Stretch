package com.example.tts.neck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/** Result of one periodic camera burst. Medians are over valid frames; null when the burst failed. */
data class FaceSample(
    val startMs: Long,
    val endMs: Long,
    /** Frames run through face detection. */
    val frames: Int,
    val faceFrames: Int,
    /** Frames with a mode C estimate (face model pose with reprojection RMS ≤ [FaceModel.MAX_RMS_PX], gravity). */
    val validFrames: Int,
    val phoneTilt: Double?,
    /** Mode C head flexion θ_C (°), used as the cycle's neck angle (design doc §11.10). */
    val neckDeg: Double?,
    /** ML Kit face pitch (°). */
    val facePitch: Double?,
    /** Population standard deviation of the per-frame θ_C (°); spread of the estimate within the burst. */
    val neckStd: Double?,
    /** null on success; otherwise no_permission, no_face_model, no_focal, camera_error, too_few_frames, cancelled. */
    val failReason: String?,
    /** Mode C face pitch relative to the camera (°). */
    val cPitch: Double? = null,
    /** Camera to eye-midpoint distance (cm). */
    val cDistCm: Double? = null,
    /** PnP reprojection RMS (px). */
    val cReprojPx: Double? = null,
) {
    val ok: Boolean get() = failReason == null
}

/**
 * Mode C measurement from inside [NeckSensingService] (design doc §11.10): opens the front camera (analysis only,
 * no preview) for [burstMs], runs face detection at about [fps], estimates θ_C per frame by PnP on the user's
 * face model and reports the medians. Runs on the main thread; [gravity] supplies the service's latest gravity vector.
 */
class NeckCameraSampler(
    private val context: Context,
    private val gravity: () -> FloatArray?,
    private val burstMs: Long = 3_000L,
    private val fps: Int = 5,
    private val minValidFrames: Int = 5,
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )
    private val arrayFocal: CameraIntrinsics.ArrayFocal? = CameraIntrinsics.frontArrayFocal(context)

    private var burst: Burst? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile
    private var lastFrameMs = 0L

    val busy: Boolean get() = burst != null

    private class Burst(val startMs: Long, val model: FaceModel, val onDone: (FaceSample) -> Unit) {
        var analysis: ImageAnalysis? = null
        var frames = 0
        var faceFrames = 0
        var lastPose: FacePose? = null
        val tilts = mutableListOf<Double>()
        val necks = mutableListOf<Double>()
        val pitches = mutableListOf<Double>()
        val cPitches = mutableListOf<Double>()
        val dists = mutableListOf<Double>()
        val reprojs = mutableListOf<Double>()
    }

    /** Starts one burst; [onDone] is called once on the main thread, also when the burst fails or is cancelled. */
    fun start(onDone: (FaceSample) -> Unit) {
        if (burst != null) return
        val now = System.currentTimeMillis()
        val model = FaceModel.load(context.getSharedPreferences(NeckDebtCalculator.PREFS, Context.MODE_PRIVATE))
        val precondition = when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                "no_permission"
            model == null -> "no_face_model"
            arrayFocal == null -> "no_focal"
            else -> null
        }
        if (precondition != null) {
            onDone(FaceSample(now, now, 0, 0, 0, null, null, null, null, precondition))
            return
        }
        val b = Burst(now, model!!, onDone)
        burst = b
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (burst !== b) return@addListener
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { image -> analyze(image, b) } }
                b.analysis = analysis
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                handler.postDelayed({ finish(b, null) }, burstMs)
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed", e)
                finish(b, "camera_error")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Stops the running burst (e.g. screen turned off) and reports it as cancelled. */
    fun cancel() {
        burst?.let { finish(it, "cancelled") }
    }

    /** Stops any burst without reporting and releases the detector. Call from the service's onDestroy. */
    fun release() {
        burst?.let { stopCamera(it) }
        burst = null
        handler.removeCallbacksAndMessages(null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        analysisExecutor.shutdown()
        faceDetector.close()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy, b: Burst) {
        val mediaImage = imageProxy.image
        val now = System.currentTimeMillis()
        if (mediaImage == null || now - lastFrameMs < 1000L / fps) {
            imageProxy.close()
            return
        }
        lastFrameMs = now
        val rotation = imageProxy.imageInfo.rotationDegrees
        val f = CameraIntrinsics.focalPx(arrayFocal!!, imageProxy.width, imageProxy.height)
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        faceDetector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { faces ->
                if (burst !== b) return@addOnSuccessListener
                b.frames++
                val face = faces.maxByOrNull { it.boundingBox.width() }?.let { FaceFeatures.from(it) } ?: return@addOnSuccessListener
                b.faceFrames++
                val landmarks = face.modeCLandmarks().map { p -> p?.let { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) } }
                val pose = FacePoseSolver.solve(b.model.points, landmarks, f, uprightWidth / 2.0, uprightHeight / 2.0, b.lastPose)
                    ?: return@addOnSuccessListener
                b.lastPose = pose
                val g = gravity() ?: return@addOnSuccessListener
                if (pose.reprojRms > FaceModel.MAX_RMS_PX) return@addOnSuccessListener
                b.tilts += TiltCalculator.tiltDegrees(g[0], g[1], g[2])
                b.necks += ModeCGeometry.headFlexion(pose.rotation, g[0], g[1], g[2]) - b.model.alphaRefDeg
                b.pitches += face.pitch.toDouble()
                b.cPitches += pose.pitchDeg
                b.dists += pose.distCm
                b.reprojs += pose.reprojRms
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun finish(b: Burst, error: String?) {
        if (burst !== b) return
        burst = null
        handler.removeCallbacksAndMessages(null)
        stopCamera(b)
        val valid = b.necks.size
        val reason = error ?: if (valid < minValidFrames) "too_few_frames" else null
        val ok = reason == null
        b.onDone(
            FaceSample(
                startMs = b.startMs,
                endMs = System.currentTimeMillis(),
                frames = b.frames,
                faceFrames = b.faceFrames,
                validFrames = valid,
                phoneTilt = if (ok) median(b.tilts) else null,
                neckDeg = if (ok) median(b.necks) else null,
                facePitch = if (ok) median(b.pitches) else null,
                neckStd = if (ok) std(b.necks) else null,
                failReason = reason,
                cPitch = if (ok) median(b.cPitches) else null,
                cDistCm = if (ok) median(b.dists) else null,
                cReprojPx = if (ok) median(b.reprojs) else null,
            )
        )
    }

    private fun stopCamera(b: Burst) {
        b.analysis?.let { analysis ->
            analysis.clearAnalyzer()
            provider?.unbind(analysis)
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun std(values: List<Double>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    companion object {
        private const val TAG = "NeckCameraSampler"
    }
}
