package com.example.tts.stretch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** Restarts background monitoring after reboot or app update if the user had it on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Stretch.isEnabled(context)) return
        // A health-type foreground service cannot start on Android 14+ without this permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACTIVITY_RECOGNITION revoked; not restarting")
            return
        }
        Log.d(TAG, "restarting monitoring after ${intent.action}")
        Stretch.enable(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
