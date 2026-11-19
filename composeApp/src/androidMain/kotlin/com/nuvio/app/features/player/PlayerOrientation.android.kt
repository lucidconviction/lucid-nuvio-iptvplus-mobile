package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Holds the activity in landscape while ANY part of the player flow is on
 * screen — the loading overlay and the player itself both acquire it, so the
 * hand-over between them never flashes portrait. The last release restores the
 * orientation the activity had before playback.
 */
private object PlayerLandscape {
    private var holders = 0
    private var previous = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Synchronized
    fun acquire(activity: Activity) {
        if (holders == 0) {
            previous = activity.requestedOrientation
            runCatching {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        holders += 1
    }

    @Synchronized
    fun release(activity: Activity) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            // UNSPECIFIED makes the system re-evaluate the sensor lazily; on
            // several phones the HOME screen stayed landscape until the device
            // was physically turned. The app is portrait-first, so an
            // unspecified prior state comes back as portrait explicitly.
            val target = if (previous == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                previous
            }
            runCatching { activity.requestedOrientation = target }
        }
    }
}

@Composable
actual fun ApplyPlayerLandscape(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled, activity) {
        if (!enabled) return@DisposableEffect onDispose { }
        PlayerLandscape.acquire(activity)
        onDispose { PlayerLandscape.release(activity) }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
