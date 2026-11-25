package com.diptych.app.ttw

import com.diptych.app.FpsMeter
import com.diptych.app.LoopStatus
import com.diptych.app.StallDetector
import com.diptych.app.net.ClientMsg
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.encodeClient
import com.diptych.app.service.TtwGameLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Everything the TTW screens show: the plugin's handshake, the last `spike` frame, and the link's health. */
data class TtwUi(
    val connected: Boolean = false,
    val welcome: TtwMsg.Welcome? = null,
    val state: TtwState? = null,
    val loop: LoopStatus = LoopStatus.SocketDead,
    /** Game frames per second, from `frame` deltas. */
    val fps: Double? = null,
    val rttMs: Long? = null,
    val lastResult: String? = null,
)

/**
 * WebSocket client for the TTW xNVSE plugin: hello -> subscribe{spike, 5}; ping every 1 s; reconnects forever with
 * backoff 0.5 s doubling to a 3 s cap. Plain JVM (no Android types) so it runs in JVM tests.
 *
 * The request carries only OkHttp's defaults: `Host: 127.0.0.1:7960` and no `Origin`
 * (the plugin rejects any Origin header, or a Host other than 127.0.0.1/localhost).
 *
 * Its [events] flow speaks the connection engine's language: each plugin `welcome` as a link welcome
 * ([TtwMsg.Welcome.toLink]) and [ServerMsg.Disconnected] when an opened socket goes away. State frames stay in [ui].
 */
class TtwClient(
    private val url: String = TTW_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : TtwGameLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stall = StallDetector()
    private val fps = FpsMeter()
    private val nextId = AtomicInteger(0)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private val _ui = MutableStateFlow(TtwUi())
    override val ui: StateFlow<TtwUi> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _events = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val events: Flow<ServerMsg> = _events

    @Volatile private var ws: WebSocket? = null
    /** The socket of the current attempt from newWebSocket until it closes, so [stop] can cancel a handshake. */
    @Volatile private var pending: WebSocket? = null
    @Volatile private var stopped = false
    private var job: Job? = null

    override fun log(line: String) {
        val stamped = "${synchronized(timeFmt) { timeFmt.format(Date(clock())) }} $line"
        _log.update { (it + stamped).takeLast(LOG_LINES) }
    }

    override fun start() {
        if (job != null) return
        job = scope.launch { connectLoop() }
        scope.launch { pingLoop() }
        scope.launch { statusLoop() }
    }

    /**
     * Closes the open socket, cancels a connect still in its handshake, and shuts down [http]'s dispatcher, so pass a
     * client of its own. Final: [start] does nothing afterwards.
     */
    override fun stop() {
        stopped = true
        ws?.close(1000, "bye")
        ws = null
        pending?.cancel()
        pending = null
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    /** Sends a `command` (ids c1, c2, ...). Returns the id, or null when not connected. */
    fun command(name: String): String? {
        val socket = ws ?: run { log("command $name: not connected"); return null }
        val id = "c${nextId.incrementAndGet()}"
        socket.send(encodeClient(ClientMsg.Command(id, name)))
        log("-> $id $name")
        return id
    }

    /** Equips the first weapon in the inventory that isn't already equipped. The plugin refuses in menus and loads. */
    fun equipFirstWeapon(): String? = command(CMD_EQUIP)

    /** Asks for the player's worldspace, cell and position; the answer lands in [TtwUi.lastResult]. */
    fun dumpWorld(): String? = command(CMD_DUMP_WORLD)

    private suspend fun connectLoop() {
        var backoff = BACKOFF_START_MS
        var quiet = false
        while (scope.isActive) {
            val closed = CompletableDeferred<Unit>()
            var opened = false
            // Most of the time no New Vegas is running: say so once, not every three seconds, so the 50-line log
            // keeps what matters.
            if (!quiet) log("connecting $url")
            val request = Request.Builder().url(url).build()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stopped) { // stop() raced the handshake: never adopt this socket
                        webSocket.cancel()
                        return
                    }
                    opened = true
                    val now = clock()
                    stall.reset(now)
                    fps.reset()
                    ws = webSocket
                    webSocket.send(encodeClient(ClientMsg.Hello(CLIENT_NAME)))
                    webSocket.send(encodeClient(ClientMsg.Subscribe(listOf(TTW_TOPIC), RATE_HZ)))
                    _ui.update { it.copy(connected = true) }
                    log("connected")
                    applyLoop(stall.status(now))
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    log("closed $code $reason")
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (opened || !quiet) {
                        log("failure: ${t.javaClass.simpleName}: ${t.message}" +
                            (response?.let { " (HTTP ${it.code})" } ?: ""))
                    }
                    closed.complete(Unit)
                }
            })
            pending = socket
            if (stopped) socket.cancel() // stop() ran between the loop check and here
            closed.await()
            if (pending === socket) pending = null
            if (ws === socket) ws = null
            _ui.update { it.copy(connected = false, fps = null) }
            applyLoop(LoopStatus.SocketDead)
            if (opened) {
                // OkHttp's reader thread delivers frames and then the close (or a read failure), so this follows the
                // socket's last frame. emit, not tryEmit: a full buffer waits instead of dropping it.
                _events.emit(ServerMsg.Disconnected)
                backoff = BACKOFF_START_MS
                quiet = false
            } else {
                if (!quiet) log("no plugin on $url yet; retrying quietly")
                quiet = true
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_CAP_MS)
        }
    }

    /**
     * Handles one plugin text frame. Internal for tests.
     * Any `welcome` (the reply to hello, or the unsolicited one at DeferredInit) replaces the shown one.
     */
    internal fun handle(text: String) {
        val now = clock()
        when (val m = parseTtw(text)) {
            is TtwMsg.Welcome -> {
                _ui.update { it.copy(welcome = m) }
                if (!_events.tryEmit(m.toLink())) log("events buffer full: welcome dropped")
                log("welcome plugin=${m.plugin} game=${m.game} xnvse=${m.xnvse} ttw=${m.ttw}")
            }
            is TtwMsg.State -> {
                if (m.topic != TTW_TOPIC) {
                    log("<- state for unexpected topic ${m.topic}")
                    return
                }
                stall.onState(now)
                val f = fps.onFrame(m.data.frame, now)
                _ui.update { it.copy(state = m.data, fps = f ?: it.fps) }
                applyLoop(stall.status(now))
            }
            is TtwMsg.Pong -> {
                stall.onPong(now)
                _ui.update { it.copy(rttMs = (now - m.t).takeIf { d -> d >= 0 }) }
                applyLoop(stall.status(now))
            }
            is TtwMsg.Result -> {
                val id = m.id.ifEmpty { "(no id)" }
                val line = "$id " + if (m.ok) "ok ${m.data ?: ""}".trimEnd() else "FAIL ${m.error ?: "(no error string)"}"
                _ui.update { it.copy(lastResult = line) }
                log("<- $line")
            }
            is TtwMsg.Error -> log("<- error: ${m.message}")
            is TtwMsg.Unknown -> log("<- unknown ${m.type}: ${m.raw.take(120)}")
        }
    }

    private val loopLock = Any()

    /** Publishes the loop status; logs each transition once. Stalled -> fps reads 0; socket dead -> null. */
    private fun applyLoop(status: LoopStatus) {
        val prev = synchronized(loopLock) {
            val p = _ui.value.loop
            _ui.update {
                it.copy(loop = status, fps = when (status) {
                    LoopStatus.Alive -> it.fps
                    LoopStatus.SocketDead -> null // no link, no rate: the screens show "-"
                    LoopStatus.Stalled -> 0.0
                })
            }
            p
        }
        if (prev != status) log("loop ${prev.label} -> ${status.label}")
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            ws?.send(encodeClient(ClientMsg.Ping(clock())))
            delay(PING_MS)
        }
    }

    /** Status depends on elapsed time, so re-evaluate it even when nothing arrives. */
    private suspend fun statusLoop() {
        while (scope.isActive) {
            delay(STATUS_TICK_MS)
            val socket = ws ?: continue
            val status = stall.status(clock())
            applyLoop(status)
            if (status == LoopStatus.SocketDead) {
                log("no pong for ${stall.socketDeadMs} ms, reconnecting")
                ws = null
                socket.cancel()
            }
        }
    }

    companion object {
        const val CLIENT_NAME = "diptych-app"
        const val RATE_HZ = 5
        const val PING_MS = 1000L
        const val STATUS_TICK_MS = 250L
        const val BACKOFF_START_MS = 500L
        const val BACKOFF_CAP_MS = 3000L
        const val LOG_LINES = 50
    }
}
