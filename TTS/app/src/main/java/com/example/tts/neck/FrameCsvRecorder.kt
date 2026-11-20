package com.example.tts.neck

import java.io.BufferedWriter
import java.io.File

/** Writes one mode B frame per CSV row (raw values only; missing values are empty cells). */
class FrameCsvRecorder(val file: File) {

    private val writer: BufferedWriter = file.bufferedWriter().apply {
        write(HEADER)
        newLine()
    }

    var frameCount = 0
        private set

    fun write(values: List<Any?>) {
        writer.write(values.joinToString(",") { it?.toString() ?: "" })
        writer.newLine()
        frameCount++
    }

    fun close() = writer.close()

    companion object {
        const val HEADER =
            "ts_ms,image_w,image_h,phone_tilt_deg,phone_roll_deg,face_pitch,face_yaw,face_roll," +
                "nose_x,nose_y,eye_l_x,eye_l_y,eye_r_x,eye_r_y," +
                "sh_l_x,sh_l_y,sh_l_likelihood,sh_r_x,sh_r_y,sh_r_likelihood," +
                "norm_ref,neck_pred_deg"
    }
}
