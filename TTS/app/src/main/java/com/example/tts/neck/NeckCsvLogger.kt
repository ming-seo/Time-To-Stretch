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
        ).joinToString(","),
        w.startMs,
    )

    fun logCycle(c: CycleResult) = append(
        "cycles",
        CYCLE_HEADER,
        listOf(
            c.startMs, c.endMs, c.windowCount, c.validWindowCount,
            fmt(c.cata), fmt(c.tiltStd), fmt(c.linAccMean), fmt(c.linAccMax),
        ).joinToString(","),
        c.startMs,
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
            "start_ms,end_ms,screen_in_use,excluded,tilt_n,tilt_mean_deg,tilt_std_deg,linacc_n,linacc_mean,linacc_max"
        private const val CYCLE_HEADER =
            "start_ms,end_ms,windows,valid_windows,cata_deg,tilt_std_deg,linacc_mean,linacc_max"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
