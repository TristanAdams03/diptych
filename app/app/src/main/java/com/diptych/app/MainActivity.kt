package com.diptych.app

import android.Manifest
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.diptych.app.display.FocusMode
import com.diptych.app.display.pickCompanionDisplay
import com.diptych.app.display.relocationTarget
import com.diptych.app.display.snapshot
import com.diptych.app.kh1.ui.MapScreen
import com.diptych.app.service.ConnectionService
import com.diptych.app.service.ConnectionSnapshot
import com.diptych.app.service.shouldAskNotifications
import com.diptych.app.service.shouldStartService
import com.diptych.app.shell.CATALOG
import com.diptych.app.shell.SEEN_PREFS
import com.diptych.app.shell.SeenRecord
import com.diptych.app.shell.SeenStore
import com.diptych.app.shell.ShellActions
import com.diptych.app.shell.ShellEnv
import com.diptych.app.shell.ShellRoot
import com.diptych.app.shell.companionLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private var focusMode: FocusMode? = null
    private val focusOn = mutableStateOf(true)
    private val showDiagnostics = mutableStateOf(false)
    /** The last mod handshake per game id; the service writes it, a listener keeps this current. */
    private val seen = mutableStateOf<Map<String, SeenRecord>>(emptyMap())
    private val gameNative = mutableStateOf<String?>(null)
    // Held here: SharedPreferences keeps only a weak reference to its listeners.
    private val seenListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readSeen() }

    /** The connection service's binder once connected. The service owns the socket; this activity only reads it. */
    private val service = mutableStateOf<ConnectionService.LocalBinder?>(null)
    private var bindRequested = false
    /** Lines logged before the service connects; flushed into the client's log when it does. */
    private val pendingLog = mutableListOf<String>()

    private val displayManager get() = getSystemService(DisplayManager::class.java)
    private val deviceLine get() = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as ConnectionService.LocalBinder
            pendingLog.forEach(b.client::log)
            pendingLog.clear()
            service.value = b
            ensureStarted(bound = true, running = b.running) // a start that failed in onCreate is retried here
        }

        // Only when the service's process dies, which is ours too. Android reconnects when the service comes back.
        override fun onServiceDisconnected(name: ComponentName) {
            service.value = null
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        log(if (granted) "notifications allowed" else "notifications denied: the service runs, its notification hidden")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maybeRelocate()) return

        log("device $deviceLine, on display ${currentDisplayId()}")
        focusMode = FocusMode(this, ::log)
        ensureStarted(bound = false, running = ConnectionService.running)
        bindService(Intent(this, ConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)
        bindRequested = true
        maybeAskNotifications()

        val seenPrefs = getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
        seenPrefs.registerOnSharedPreferenceChangeListener(seenListener)
        readSeen()

        val actions = ShellActions(
            play = { playGameNative() },
            openUrl = ::openUrl,
            toggleDiagnostics = { showDiagnostics.value = !showDiagnostics.value },
        )
        setContent {
            val b = service.value
            // A StateFlow hands over its current value on collect, so a rebind shows the true state at once.
            val snap by (b?.state ?: UNBOUND).collectAsState()
            val log by (b?.client?.log ?: NO_LOG).collectAsState()
            ShellRoot(
                snapshot = snap,
                env = ShellEnv(seen = seen.value, gameNativeVersion = gameNative.value),
                actions = actions,
                showDiagnostics = showDiagnostics.value,
                diagnostics = {
                    SpikeScreen(
                        ui = snap.ui,
                        log = log,
                        focusOn = focusOn.value,
                        onFetchModLog = { b?.client?.fetchModLog() },
                        onTestJump = { b?.client?.testPress(0x20, 150) },
                        onTestCast = { b?.client?.testPress(0x14, 300) },
                        onToggleFocus = {
                            focusMode?.toggle()
                            focusOn.value = focusMode?.enabled ?: false
                        },
                        onCopyReport = { copyReport() },
                    )
                },
                // Read-only: only the diagnostics screen's Test press buttons send input.
                companion = { MapScreen(view = snap.view, connection = companionLabel(snap), onReport = ::reportWrong) },
                clock = SystemClock::elapsedRealtime, // monotonic: the 10 s grace and the idle dim
            )
        }
    }

    override fun onResume() {
        super.onResume()
        gameNative.value = gameNativeVersion() // installed or updated while we were away
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusMode?.onWindowFocusChanged(hasFocus)
        // Diptych stays resumed on the bottom screen while GameNative is installed or updated on top; a touch back
        // here re-reads the version, so the status line can't go stale.
        if (hasFocus) gameNative.value = gameNativeVersion()
    }

    override fun onDestroy() {
        getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(seenListener)
        focusMode?.onDestroy()
        // Unbind only: the service keeps the link (and keeps reconnecting) after this activity is gone.
        if (bindRequested) runCatching { unbindService(connection) }
        bindRequested = false
        service.value = null
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = display?.displayId ?: 0

    private fun log(line: String) {
        val client = service.value?.client
        if (client != null) client.log(line) else pendingLog += line
    }

    private fun ensureStarted(bound: Boolean, running: Boolean) {
        if (!shouldStartService(bound, running)) return
        try {
            startForegroundService(Intent(this, ConnectionService::class.java))
        } catch (e: RuntimeException) { // e.g. ForegroundServiceStartNotAllowedException; the bind still connects
            log("service start failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** API 33+: ask for POST_NOTIFICATIONS once, on first launch. Denied is fine: the service runs regardless. */
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // granted at install; nothing to ask
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!shouldAskNotifications(Build.VERSION.SDK_INT, prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false), granted)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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

    private fun readSeen() {
        val store = SeenStore(getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE))
        seen.value = CATALOG.mapNotNull { e -> store.get(e.id)?.let { e.id to it } }.toMap()
    }

    private fun gameNativeVersion(): String? = try {
        @Suppress("DEPRECATION") // the PackageInfoFlags overload is API 33+; minSdk is 30
        packageManager.getPackageInfo(GAMENATIVE, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Play: GameNative's own launch intent, on the game display (0). Started from here without a display, Android
     * would open it on this (the companion) display. Only GameNative ever goes to display 0, never this activity.
     */
    private fun playGameNative() {
        val launch = packageManager.getLaunchIntentForPackage(GAMENATIVE)
        if (launch == null) {
            Toast.makeText(this, "GameNative not found", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        } catch (e: RuntimeException) { // SecurityException etc. No retry without a display: it would land down here.
            log("play on display 0 failed: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "Couldn't open GameNative on the top screen", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser to open $url", Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the diagnostics report; false before the service has connected. */
    private fun copyReport(): Boolean {
        val client = service.value?.client ?: return false
        val report = buildReport(client.ui.value, client.log.value, deviceLine, displayManager.snapshot())
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Diptych spike report", report))
        client.log("report copied (${report.length} chars)")
        return true
    }

    private fun reportWrong() {
        val copied = copyReport()
        Toast.makeText(this, if (copied) "Copied: paste it to Claude" else "Not connected yet", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RELOCATED = "relocated"
        private const val PREFS = "spike"
        private const val KEY_LAST_RELOCATE = "lastRelocateMs"
        private const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
        private const val GAMENATIVE = "app.gamenative"
    }
}

/** What the screens show in the moment before the service connects. */
private val UNBOUND: StateFlow<ConnectionSnapshot> = MutableStateFlow(ConnectionSnapshot()).asStateFlow()
private val NO_LOG: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
