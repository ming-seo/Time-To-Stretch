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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Mode B data collection screen (step 2: front camera preview + face features). */
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

        faceDetector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                showFace(face?.let { FaceFeatures.from(it) }, uprightWidth, uprightHeight)
            }
            .addOnFailureListener { e -> Log.w(TAG, "Face detection failed", e) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun showFace(face: FaceFeatures?, imageWidth: Int, imageHeight: Int) {
        overlayView.setPoints(imageWidth, imageHeight, listOfNotNull(face?.nose, face?.leftEye, face?.rightEye))
        tvFeatures.text = if (face == null) {
            "얼굴: 없음"
        } else {
            val eyeDist = if (face.leftEye != null && face.rightEye != null) {
                fmt(hypot(face.leftEye.x - face.rightEye.x, face.leftEye.y - face.rightEye.y)) + " px"
            } else {
                "-"
            }
            "얼굴 pitch ${fmt(face.pitch)}° · yaw ${fmt(face.yaw)}° · roll ${fmt(face.roll)}°\n" +
                "눈 사이 거리: $eyeDist (${imageWidth}×${imageHeight})"
        }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    /** Face features for mode B: head pose relative to the camera and nose/eye positions (image px). */
    private data class FaceFeatures(
        val pitch: Float,
        val yaw: Float,
        val roll: Float,
        val nose: PointF?,
        val leftEye: PointF?,
        val rightEye: PointF?,
    ) {
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

    companion object {
        private const val TAG = "NeckCollectActivity"
    }
}
