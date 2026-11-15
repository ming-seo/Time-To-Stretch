package com.example.tts.neck

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Mode B data collection screen (step 3: front camera preview + face and shoulder features). */
class NeckCollectActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: KeypointOverlayView
    private lateinit var tvFeatures: TextView

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                showFeatures(face, shoulders, uprightWidth, uprightHeight)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun showFeatures(face: FaceFeatures?, shoulders: ShoulderFeatures?, imageWidth: Int, imageHeight: Int) {
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
            val nose = face?.nose
            val noseToNeck = nose?.let { hypot(it.x - shoulders.neck.x, it.y - shoulders.neck.y) }
            val norm = if (noseToNeck != null && eyeDist != null && eyeDist > 0f) noseToNeck / eyeDist else null
            "어깨 신뢰도 L ${fmt(shoulders.leftLikelihood)} · R ${fmt(shoulders.rightLikelihood)}\n" +
                "코–목 / 눈 사이: ${norm?.let { fmt(it) } ?: "-"}"
        }
        tvFeatures.text = faceText + "\n" + shoulderText
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    /** Face features for mode B: head pose relative to the camera and nose/eye positions (image px). */
    private data class FaceFeatures(
        val pitch: Float,
        val yaw: Float,
        val roll: Float,
        val nose: PointF?,
        val leftEye: PointF?,
        val rightEye: PointF?,
    ) {
        fun eyeDistance(): Float? =
            if (leftEye != null && rightEye != null) hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y) else null

        companion object {
            fun from(face: Face) = FaceFeatures(
                pitch = face.headEulerAngleX,
                yaw = face.headEulerAngleY,
                roll = face.headEulerAngleZ,
                nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position,
                leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
            )
        }
    }

    /** Shoulder keypoints (image px) and their in-frame likelihoods; neck base = shoulder midpoint. */
    private data class ShoulderFeatures(
        val left: PointF,
        val right: PointF,
        val leftLikelihood: Float,
        val rightLikelihood: Float,
    ) {
        val neck: PointF get() = PointF((left.x + right.x) / 2, (left.y + right.y) / 2)

        companion object {
            fun from(pose: Pose): ShoulderFeatures? {
                val l = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: return null
                val r = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER) ?: return null
                return ShoulderFeatures(l.position, r.position, l.inFrameLikelihood, r.inFrameLikelihood)
            }
        }
    }

    companion object {
        private const val TAG = "NeckCollectActivity"
    }
}
