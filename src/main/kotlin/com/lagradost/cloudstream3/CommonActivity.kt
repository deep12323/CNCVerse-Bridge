package com.lagradost.cloudstream3

import android.app.Activity
import android.util.DisplayMetrics

/**
 * Desktop stub of CloudStream's CommonActivity. Plugins reference it for
 * toasts/activity lookups; calls are harmless no-ops on desktop. `activity`
 * defaults to DesktopContext (an AppCompatActivity) so plugins that grab
 * their SharedPreferences from it at construction keep working on desktop.
 */
object CommonActivity {
    var activity: Activity? = android.content.DesktopContext
        private set

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    val displayMetrics: DisplayMetrics = DisplayMetrics()

    fun showToast(message: String?, duration: Int? = null) {
        com.cncverse.stremiobridge.state.ServerState.info("[Toast] $message")
    }
}
