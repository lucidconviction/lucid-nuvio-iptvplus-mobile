package com.nuvio.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Process-wide foreground Activity reference. The plugin runtime is initialized lazily,
 * often after MainActivity has already resumed, so its own lifecycle callbacks can miss
 * the first Activity. This tracker is installed in Application.onCreate instead.
 */
object AppActivityTracker {
    @Volatile
    var current: Activity? = null
        private set

    private var installed = false

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    current = activity
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (current === activity) current = null
                }
            },
        )
    }
}
