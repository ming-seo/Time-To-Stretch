package com.example.tts.neck

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends window/cycle results to daily CSV files in app-specific external storage
 * (Android/data/<package>/files/neck_logs/), readable over USB or `adb pull`.
 */
class NeckCsvLogger(context: Context) {

    val dir: File = logDir(context)

    fun logWindow(w: WindowResult) = append(
        "windows",
        WINDOW_HEADER,
        listOf(
            w.startMs, w.endMs, w.screenInUse, w.excluded,
            w.tiltCount, fmt(w.tiltMean), fmt(w.tiltStd),
            w.linAccCount, fmt(w.linAccMean), fmt(w.linAccMax),
            w.staticDevice, w.excludeReasons.sorted().joinToString("|"),
        ).joinToString(","),
        w.startMs,
    )

    /** [gCata] is g(CATA) and [gSigma] the residual std of g(); both empty while g() is not ready. */
    fun logCycle(c: CycleResult, state: NeckState, debt: Double, gCata: Double?, gSigma: Double?) = append(
        "cycles",
        CYCLE_HEADER,
        listOf(
            c.startMs, c.endMs, c.windowCount, c.validWindowCount,
            fmt(c.cata), fmt(c.tiltStd), fmt(c.linAccMean), fmt(c.linAccMax),
            c.staticWindowCount, state, fmt(debt), fmt(gCata), fmt(gSigma),
        ).joinToString(","),
        c.startMs,
    )

    fun logSample(s: FaceSample) = append(
        "samples",
        SAMPLE_HEADER,
        listOf(
            s.startMs, s.endMs, s.frames, s.faceFrames, s.validFrames,
            fmt(s.phoneTilt), fmt(s.neckDeg), fmt(s.facePitch), s.failReason ?: "", fmt(s.neckStd),
        ).joinToString(","),
        s.startMs,
    )

    private fun append(prefix: String, header: String, line: String, timeMs: Long) {
        val file = File(dir, "${prefix}_${DATE_FORMAT.format(Date(timeMs))}.csv")
        val isNew = !file.exists()
        file.appendText((if (isNew) header + "\n" else "") + line + "\n")
    }

    private fun fmt(v: Double?): String = v?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    companion object {
        fun logDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "neck_logs").apply { mkdirs() }

        private const val WINDOW_HEADER =
            "start_ms,end_ms,screen_in_use,excluded,tilt_n,tilt_mean_deg,tilt_std_deg,linacc_n,linacc_mean,linacc_max," +
                "static_device,exclude_reason"
        private const val CYCLE_HEADER =
            "start_ms,end_ms,windows,valid_windows,cata_deg,tilt_std_deg,linacc_mean,linacc_max," +
                "static_windows,state,d_n,g_cata_deg,g_sigma_deg"
        private const val SAMPLE_HEADER =
            "start_ms,end_ms,frames,face_frames,valid_frames,theta_phone_deg,theta_neck_deg,face_pitch_deg,fail_reason,theta_neck_std_deg"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
