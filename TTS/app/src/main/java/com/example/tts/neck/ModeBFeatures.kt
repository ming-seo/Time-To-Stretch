package com.example.tts.neck

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.hypot

/**
 * Face features: head pose relative to the camera and landmark positions (image px).
 * Mode B uses nose and eyes; mode C also uses the mouth and cheek landmarks and the face box center.
 */
data class FaceFeatures(
    val pitch: Float,
    val yaw: Float,
    val roll: Float,
    val nose: PointF?,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val mouthLeft: PointF? = null,
    val mouthRight: PointF? = null,
    val mouthBottom: PointF? = null,
    val leftCheek: PointF? = null,
    val rightCheek: PointF? = null,
    /** Center of the face bounding box. */
    val center: PointF? = null,
) {
    /** Mode C landmarks in [FaceModel.LANDMARKS] order; null entries were not detected. */
    fun modeCLandmarks(): List<PointF?> =
        listOf(leftEye, rightEye, nose, mouthLeft, mouthRight, mouthBottom, leftCheek, rightCheek)

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
            mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position,
            mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position,
            mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position,
            leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position,
            rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position,
            center = PointF(face.boundingBox.exactCenterX(), face.boundingBox.exactCenterY()),
        )
    }
}

/** Shoulder keypoints (image px) and their in-frame likelihoods; neck base = shoulder midpoint. */
data class ShoulderFeatures(
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

/** Mode B feature math shared by the collection screen and the periodic sampler in the service. */
object ModeBFeatures {
    /** SharedPreferences file and key holding Norm_ref (set on the collection screen). */
    const val PREFS = "neck"
    const val KEY_NORM_REF = "norm_ref"
    const val MIN_SHOULDER_LIKELIHOOD = 0.5f

    /** nose–neck distance / eye distance (image px), before dividing by Norm_ref. */
    fun rawRatio(face: FaceFeatures?, shoulders: ShoulderFeatures?): Float? {
        val nose = face?.nose ?: return null
        val eyeDist = face.eyeDistance() ?: return null
        if (shoulders == null || eyeDist <= 0f) return null
        return hypot(nose.x - shoulders.neck.x, nose.y - shoulders.neck.y) / eyeDist
    }

    /** Neck angle (°) from the Watanabe model; null without model, Norm_ref, phone tilt, face or both shoulders. */
    fun predictNeck(
        model: WatanabeNeckModel?,
        normRef: Float?,
        phoneTilt: Double?,
        face: FaceFeatures?,
        shoulders: ShoulderFeatures?,
    ): Double? {
        if (model == null || normRef == null || phoneTilt == null) return null
        if (face == null || shoulders == null ||
            shoulders.leftLikelihood < MIN_SHOULDER_LIKELIHOOD || shoulders.rightLikelihood < MIN_SHOULDER_LIKELIHOOD
        ) return null
        val ratio = rawRatio(face, shoulders) ?: return null
        return model.predict((ratio / normRef).toDouble(), phoneTilt, face.pitch.toDouble())
    }
}
