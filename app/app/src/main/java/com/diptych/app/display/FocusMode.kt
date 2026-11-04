package com.diptych.app.display

import android.app.Activity
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Keeps input focus on the game while the companion screen is touched.
 *
 * After the first `onWindowFocusChanged(true)` it adds a 1x1 `TYPE_APPLICATION_PANEL` anchor
 * (`FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL`) on the activity's token, then sets
 * `FLAG_NOT_FOCUSABLE` on the main window. Turning it off clears the flag and removes the anchor.
 * Default on.
 */
class FocusMode(private val activity: Activity, private val log: (String) -> Unit = {}) {
    var enabled: Boolean = true
        private set
    private var hadFocus = false
    private var anchor: View? = null

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus || hadFocus) return
        hadFocus = true
        if (enabled) apply()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        log("focus mode ${if (on) "on" else "off"}")
        if (!hadFocus) return // applied on the first focus gain
        if (on) apply() else remove()
    }

    fun toggle() = setEnabled(!enabled)

    fun onDestroy() = removeAnchor()

    private fun apply() {
        if (anchor == null) {
            val token = activity.window.decorView.windowToken
            if (token == null) {
                log("focus mode: no window token yet")
                return
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.token = token
                gravity = Gravity.TOP or Gravity.START
                title = "diptych-focus-anchor"
            }
            val v = View(activity)
            try {
                activity.windowManager.addView(v, lp)
                anchor = v
            } catch (e: RuntimeException) {
                log("focus mode: anchor failed: ${e.message}")
                return
            }
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        log("focus mode applied (anchor + FLAG_NOT_FOCUSABLE)")
    }

    private fun remove() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        removeAnchor()
        log("focus mode removed")
    }

    private fun removeAnchor() {
        anchor?.let { runCatching { activity.windowManager.removeViewImmediate(it) } }
        anchor = null
    }
}
