package com.example.tts

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * From targetSdk 35 the system draws every screen edge-to-edge. Screens with a top bar keep the bar below
 * the status bar, but their content still runs under the gesture/navigation bar, so pad it here once for
 * all of them instead of in each layout. MainActivity handles its own insets.
 */
class TtsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) return
                val content = activity.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
                    view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
                    insets
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
