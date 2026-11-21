package com.example.tts.neck

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.max

/** Front camera focal length for mode C (design doc §11.3). The principal point is taken as the image center. */
object CameraIntrinsics {

    /** Focal length (px) on the sensor array of [width]×[height], and where it came from ("intrinsic" / "focal_length"). */
    class ArrayFocal(val focalPx: Double, val width: Int, val height: Int, val source: String)

    /**
     * Focal length (px) of an analysis buffer [bufferWidth]×[bufferHeight] (sensor orientation), assumed to be a
     * centered crop of the array scaled uniformly to fill the buffer. Rotation to upright does not change it.
     */
    fun focalPx(array: ArrayFocal, bufferWidth: Int, bufferHeight: Int): Double =
        array.focalPx * max(bufferWidth.toDouble() / array.width, bufferHeight.toDouble() / array.height)

    /** Reads the first front camera's characteristics; null if they are missing. */
    fun frontArrayFocal(context: Context): ArrayFocal? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return null
        val ch = manager.getCameraCharacteristics(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cal = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val array = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            if (cal != null && cal.isNotEmpty() && cal[0] > 0f && array != null) {
                return ArrayFocal(cal[0].toDouble(), array.width(), array.height(), "intrinsic")
            }
        }
        val focalMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixels = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        return ArrayFocal(focalMm / physical.width * pixels.width.toDouble(), active.width(), active.height(), "focal_length")
    }
}
