package com.example.tts.stretch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Marks power-off so the service stop it triggers isn't taken as the user turning monitoring off,
 * and restarts monitoring after reboot or app update if the user had it on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SHUTDOWN, ACTION_QUICKBOOT_POWEROFF -> {
                Log.d(TAG, "shutdown: keeping monitoring state")
                Stretch.onShutdown(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> restartIfEnabled(context, intent.action!!)
        }
    }

    private fun restartIfEnabled(context: Context, action: String) {
        Stretch.onBoot(context)
        if (!Stretch.isEnabled(context)) return
        // A health-type foreground service cannot start on Android 14+ without this permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACTIVITY_RECOGNITION revoked; not restarting")
            return
        }
        Log.d(TAG, "restarting monitoring after $action")
        Stretch.enable(context)
    }

    companion object {
        private const val TAG = "TtsBootReceiver"
        private const val ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF"
    }
}
