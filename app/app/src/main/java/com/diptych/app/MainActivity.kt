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
import com.diptych.app.kh1.Kh1Session
import com.diptych.app.kh1.loadKh1Data
import com.diptych.app.kh1.ui.MapScreen
import com.diptych.app.net.SpikeClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private lateinit var client: SpikeClient
    private var focusMode: FocusMode? = null
    private val focusOn = mutableStateOf(true)
    private val showDiagnostics = mutableStateOf(false)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val displayManager get() = getSystemService(DisplayManager::class.java)
    private val deviceLine get() = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maybeRelocate()) return

        client = SpikeClient()
        client.log("device $deviceLine, on display ${currentDisplayId()}")
        focusMode = FocusMode(this, client::log)
        // The session must exist before start(): the events flow has no replay, so a later one misses the first welcome.
        val session = Kh1Session(client, loadKh1Data(assets), sessionScope, client::log)
        client.start()

        setContent {
            val ui by client.ui.collectAsState()
            val log by client.log.collectAsState()
            val view by session.view.collectAsState()
            Box {
                if (showDiagnostics.value) {
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
                } else {
                    MapScreen(view, connectionLabel(ui), onReport = ::reportWrong)
                }
                // Long-press (>= 1 s) on the header strip toggles between the map and the spike diagnostics.
                LongPressStrip(Modifier.align(Alignment.TopStart)) { showDiagnostics.value = !showDiagnostics.value }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusMode?.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        focusMode?.onDestroy()
        sessionScope.cancel()
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

    private fun reportWrong() {
        copyReport()
        Toast.makeText(this, "Copied: paste it to Claude", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RELOCATED = "relocated"
        private const val PREFS = "spike"
        private const val KEY_LAST_RELOCATE = "lastRelocateMs"
    }
}

private const val LONG_PRESS_MS = 1000L

/** Transparent strip over the header; calls [onLongPress] after the pointer stays down for 1 s. Taps pass nothing on. */
@Composable
private fun LongPressStrip(modifier: Modifier, onLongPress: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(64.dp).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var held = true
                withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation(); held = false }
                if (held) onLongPress()
            }
        },
    )
}
