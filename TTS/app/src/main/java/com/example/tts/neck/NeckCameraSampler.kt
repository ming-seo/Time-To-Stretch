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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

/** Result of one periodic camera burst. Medians are over valid frames; null when the burst failed. */
data class FaceSample(
    val startMs: Long,
    val endMs: Long,
    /** Frames run through face/pose detection. */
    val frames: Int,
    val faceFrames: Int,
    /** Frames with a neck angle estimate (face, both shoulders, Norm_ref, model, phone tilt). */
    val validFrames: Int,
    val phoneTilt: Double?,
    val neckDeg: Double?,
    val facePitch: Double?,
    /** Population standard deviation of the per-frame neck angles (°); spread of the estimate within the burst. */
    val neckStd: Double?,
    /** null on success; otherwise no_permission, no_model, no_norm_ref, camera_error, too_few_frames, cancelled. */
    val failReason: String?,
) {
    val ok: Boolean get() = failReason == null
}

/**
 * Mode B measurement from inside [NeckSensingService]: opens the front camera (analysis only, no preview)
 * for [burstMs], runs face/pose detection at about [fps], estimates the neck angle per frame and reports
 * the medians. Runs on the main thread; [phoneTilt] supplies the service's latest tilt for each frame.
 */
class NeckCameraSampler(
    private val context: Context,
    private val phoneTilt: () -> Double?,
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
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private val neckModel: WatanabeNeckModel? = WatanabeNeckModel.load(context)

    private var burst: Burst? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile
    private var lastFrameMs = 0L

    val busy: Boolean get() = burst != null

    private class Burst(val startMs: Long, val normRef: Float, val onDone: (FaceSample) -> Unit) {
        var analysis: ImageAnalysis? = null
        var frames = 0
        var faceFrames = 0
        val tilts = mutableListOf<Double>()
        val necks = mutableListOf<Double>()
        val pitches = mutableListOf<Double>()
    }

    /** Starts one burst; [onDone] is called once on the main thread, also when the burst fails or is cancelled. */
    fun start(onDone: (FaceSample) -> Unit) {
        if (burst != null) return
        val now = System.currentTimeMillis()
        val normRef = context.getSharedPreferences(ModeBFeatures.PREFS, Context.MODE_PRIVATE)
            .getFloat(ModeBFeatures.KEY_NORM_REF, Float.NaN).takeUnless { it.isNaN() }
        val precondition = when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                "no_permission"
            neckModel == null -> "no_model"
            normRef == null -> "no_norm_ref"
            else -> null
        }
        if (precondition != null) {
            onDone(FaceSample(now, now, 0, 0, 0, null, null, null, null, precondition))
            return
        }
        val b = Burst(now, normRef!!, onDone)
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

    /** Stops any burst without reporting and releases detectors. Call from the service's onDestroy. */
    fun release() {
        burst?.let { stopCamera(it) }
        burst = null
        handler.removeCallbacksAndMessages(null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        analysisExecutor.shutdown()
        faceDetector.close()
        poseDetector.close()
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
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val faceTask = faceDetector.process(input)
        val poseTask = poseDetector.process(input)
        Tasks.whenAllComplete(faceTask, poseTask)
            .addOnSuccessListener(ContextCompat.getMainExecutor(context)) {
                if (burst !== b) return@addOnSuccessListener
                b.frames++
                val face = if (faceTask.isSuccessful) {
                    faceTask.result.maxByOrNull { it.boundingBox.width() }?.let { FaceFeatures.from(it) }
                } else null
                val shoulders = if (poseTask.isSuccessful) ShoulderFeatures.from(poseTask.result) else null
                if (face != null) b.faceFrames++
                val tilt = phoneTilt()
                val neck = ModeBFeatures.predictNeck(neckModel, b.normRef, tilt, face, shoulders)
                if (neck != null && tilt != null && face != null) {
                    b.tilts += tilt
                    b.necks += neck
                    b.pitches += face.pitch.toDouble()
                }
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
        b.onDone(
            FaceSample(
                startMs = b.startMs,
                endMs = System.currentTimeMillis(),
                frames = b.frames,
                faceFrames = b.faceFrames,
                validFrames = valid,
                phoneTilt = if (reason == null) median(b.tilts) else null,
                neckDeg = if (reason == null) median(b.necks) else null,
                facePitch = if (reason == null) median(b.pitches) else null,
                neckStd = if (reason == null) std(b.necks) else null,
                failReason = reason,
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
