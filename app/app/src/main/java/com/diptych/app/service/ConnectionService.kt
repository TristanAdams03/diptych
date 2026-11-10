package com.diptych.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.diptych.app.R
import com.diptych.app.kh1.loadKh1Data
import com.diptych.app.net.SpikeClient
import com.diptych.app.shell.SEEN_PREFS
import com.diptych.app.shell.SeenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the game link for the process: one [SpikeClient] and its KH1 session, kept in a [ConnectionCore].
 * It runs in the foreground behind a silent, low-importance notification and is sticky, so the socket keeps
 * reconnecting after Android kills the activity, and comes back (with a null intent) after Android kills the process.
 * The activity binds to read [LocalBinder.state] and to reach [LocalBinder.client] for the diagnostics screen.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var core: ConnectionCore<SpikeClient>
    private var binder: LocalBinder? = null

    val state: StateFlow<ConnectionSnapshot> get() = core.state

    inner class LocalBinder(val client: SpikeClient) : Binder() {
        val state: StateFlow<ConnectionSnapshot> get() = this@ConnectionService.state
        val running: Boolean get() = ConnectionService.running
    }

    override fun onCreate() {
        super.onCreate()
        core = ConnectionCore(
            newLink = { SpikeClient() },
            data = loadKh1Data(assets),
            scope = scope,
            seen = SeenStore(getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val client = core.open() // the first start or bind opens the socket; every later one gets the same client
        client.log(if (intent == null) "service restarted by Android" else "service started")
        goForeground(client)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder ?: LocalBinder(core.open()).also { binder = it }

    override fun onDestroy() {
        running = false
        core.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(client: SpikeClient) {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hinge)
            .setContentTitle(getString(R.string.listening_notification))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (e: RuntimeException) {
            // Android 12+ refuses startForeground from the background (a sticky restart). Keep connecting anyway;
            // the next time the activity opens it reads running == false and starts the service from the foreground.
            client.log("foreground refused: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** Started and in the foreground in this process. It resets with the process, so a new process reads false. */
        @Volatile
        var running = false
            private set
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 7970
    }
}
