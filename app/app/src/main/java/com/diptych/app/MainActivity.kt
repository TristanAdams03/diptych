package com.diptych.app

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.diptych.app.display.FocusMode
import com.diptych.app.display.pickCompanionDisplay
import com.diptych.app.display.relocationTarget
import com.diptych.app.display.snapshot
import com.diptych.app.net.SpikeClient

class MainActivity : ComponentActivity() {
    private lateinit var client: SpikeClient
    private var focusMode: FocusMode? = null
    private val focusOn = mutableStateOf(true)

    private val displayManager get() = getSystemService(DisplayManager::class.java)
    private val deviceLine get() = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maybeRelocate()) return

        client = SpikeClient()
        client.log("device $deviceLine, on display ${currentDisplayId()}")
        focusMode = FocusMode(this, client::log)
        client.start()

        setContent {
            val ui by client.ui.collectAsState()
            val log by client.log.collectAsState()
            SpikeScreen(
                ui = ui,
                log = log,
                focusOn = focusOn.value,
                onFetchModLog = { client.fetchModLog() },
                onTestJump = { client.testPress(0x20, 150) },
                onTestCast = { client.testPress(0x14, 300) },
                onToggleFocus = {
                    focusMode?.toggle()
                    focusOn.value = focusMode?.enabled ?: false
                },
                onCopyReport = ::copyReport,
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusMode?.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        focusMode?.onDestroy()
        if (::client.isInitialized) client.stop()
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = display?.displayId ?: 0

    /**
     * If we're not on the companion display, relaunch there once (10 s cooldown) and finish.
     * Only ever relaunches *to* the picked companion display, never to display 0.
     */
    private fun maybeRelocate(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val target = relocationTarget(
            current = currentDisplayId(),
            picked = pickCompanionDisplay(displayManager.snapshot()),
            relocated = intent.getBooleanExtra(EXTRA_RELOCATED, false),
            lastRelocateMs = prefs.getLong(KEY_LAST_RELOCATE, 0L),
            nowMs = now,
        ) ?: return false
        prefs.edit().putLong(KEY_LAST_RELOCATE, now).apply()
        val relaunch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_RELOCATED, true)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        return try {
            startActivity(relaunch, options.toBundle())
            finishAndRemoveTask()
            true
        } catch (e: RuntimeException) { // SecurityException etc.: stay where we are
            Toast.makeText(this, "relocate to display $target failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun copyReport() {
        val report = buildReport(client.ui.value, client.log.value, deviceLine, displayManager.snapshot())
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Diptych spike report", report))
        client.log("report copied (${report.length} chars)")
    }

    companion object {
        const val EXTRA_RELOCATED = "relocated"
        private const val PREFS = "spike"
        private const val KEY_LAST_RELOCATE = "lastRelocateMs"
    }
}
