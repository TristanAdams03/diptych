package com.diptych.app.net

import com.diptych.app.FpsMeter
import com.diptych.app.LoopStatus
import com.diptych.app.SpikeUi
import com.diptych.app.StallDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import com.diptych.app.kh1.Region
import com.diptych.app.service.GameLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

const val DEFAULT_URL = "ws://127.0.0.1:7970"
const val TOPIC = "kh1.spike"

/**
 * Spike WebSocket client for the KH1 mod: hello -> subscribe{kh1.spike, 5}; ping every 1 s; reconnects forever with
 * backoff 0.5 s doubling to a 3 s cap. Plain JVM (no Android types) so it runs in JVM tests.
 *
 * The request carries only OkHttp's defaults: `Host: 127.0.0.1:7970` and no `Origin`
 * (the mod rejects any Origin header, or a Host other than 127.0.0.1/localhost).
 */
class SpikeClient(
    private val url: String = DEFAULT_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : GameLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stall = StallDetector()
    private val fps = FpsMeter()
    private val nextId = AtomicInteger(0)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private val _ui = MutableStateFlow(SpikeUi())
    override val ui: StateFlow<SpikeUi> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _events = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)

    /** Every parsed server frame, for consumers such as the KH1 session. */
    override val events: Flow<ServerMsg> = _events

    @Volatile private var ws: WebSocket? = null
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

    override fun stop() {
        ws?.close(1000, "bye")
        ws = null
        scope.cancel()
    }

    /** Sends a `command` (ids c1, c2, ...). Returns the id, or null when not connected. */
    fun command(name: String, args: JsonObject = JsonObject(emptyMap())): String? {
        val socket = ws ?: run { log("command $name: not connected"); return null }
        val id = "c${nextId.incrementAndGet()}"
        socket.send(encodeClient(ClientMsg.Command(id, name, args)))
        log("-> $id $name")
        return id
    }

    /** `kh1.watch`: replaces the connection's watched regions (empty clears). The mod forgets it per connection. */
    override fun watch(regions: List<Region>): String? = command(
        "kh1.watch",
        JsonObject(mapOf("regions" to JsonArray(regions.map {
            JsonObject(mapOf("offset" to JsonPrimitive(it.offset), "length" to JsonPrimitive(it.length)))
        }))),
    )

    /** `kh1.log` (works on any build); the reply's lines land in [SpikeUi.modLog]. */
    fun fetchModLog(): String? = command("kh1.log")

    /** `kh1.pressInput` with a PS2-style mask in byte 1 (B 0x20, LB 0x04 + Y 0x10 = 0x14). */
    fun testPress(mask: Int, durationMs: Int): String? = command(
        "kh1.pressInput",
        JsonObject(mapOf("byteIndex" to JsonPrimitive(1), "mask" to JsonPrimitive(mask), "durationMs" to JsonPrimitive(durationMs))),
    )

    private suspend fun connectLoop() {
        var backoff = BACKOFF_START_MS
        while (scope.isActive) {
            val closed = CompletableDeferred<Unit>()
            var opened = false
            log("connecting $url")
            val request = Request.Builder().url(url).build()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    val now = clock()
                    stall.reset(now)
                    fps.reset()
                    ws = webSocket
                    webSocket.send(encodeClient(ClientMsg.Hello(CLIENT_NAME)))
                    webSocket.send(encodeClient(ClientMsg.Subscribe(listOf(TOPIC), RATE_HZ)))
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
                    log("failure: ${t.javaClass.simpleName}: ${t.message}" +
                        (response?.let { " (HTTP ${it.code})" } ?: ""))
                    closed.complete(Unit)
                }
            })
            closed.await()
            if (ws === socket) ws = null
            _ui.update { it.copy(connected = false, seqRate = null) }
            applyLoop(LoopStatus.SocketDead)
            if (opened) {
                // OkHttp's reader thread delivers frames and then the close (or a read failure), so this follows the
                // socket's last frame. emit, not tryEmit: a full buffer waits instead of dropping it.
                _events.emit(ServerMsg.Disconnected)
                backoff = BACKOFF_START_MS
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_CAP_MS)
        }
    }

    /**
     * Handles one server text frame. Internal for tests.
     * Any `welcome` (the reply to hello, or unsolicited when the mod re-classifies its build) replaces the shown one.
     */
    internal fun handle(text: String) {
        val now = clock()
        val msg = parseServer(text)
        _events.tryEmit(msg)
        when (val m = msg) {
            is ServerMsg.Welcome -> {
                _ui.update { it.copy(welcome = m) }
                log("welcome plugin=${m.plugin} game=${m.gameId} build=${m.gameBuild} supported=${m.supported}")
            }
            is ServerMsg.State -> {
                if (m.topic != TOPIC) {
                    log("<- state for unexpected topic ${m.topic}")
                    return
                }
                stall.onState(now)
                val r = fps.onFrame(m.data.seq, now)
                _ui.update { it.copy(state = m.data, seqRate = r ?: it.seqRate) }
                applyLoop(stall.status(now))
            }
            is ServerMsg.Pong -> {
                stall.onPong(now)
                _ui.update { it.copy(rttMs = (now - m.t).takeIf { d -> d >= 0 }) }
                applyLoop(stall.status(now))
            }
            is ServerMsg.Result -> {
                val lines = if (m.ok) logLines(m.data) else null
                val id = m.id.ifEmpty { "(no id)" }
                val line = "$id " + when {
                    !m.ok -> "FAIL ${m.error ?: "(no error string)"}" // shown verbatim
                    lines != null -> "ok (${lines.size} log lines)"
                    else -> "ok ${m.data ?: ""}".trimEnd()
                }
                _ui.update { if (lines != null) it.copy(lastResult = line, modLog = lines) else it.copy(lastResult = line) }
                log("<- $line")
            }
            is ServerMsg.Error -> log("<- error: ${m.message}")
            is ServerMsg.Unknown -> log("<- unknown ${m.type}: ${m.raw.take(120)}")
            ServerMsg.Disconnected -> Unit // never parsed from a frame
        }
    }

    private val loopLock = Any()

    /** `kh1.log` result data `{lines:[string]}` -> the lines; null for any other result. */
    private fun logLines(data: JsonObject?): List<String>? {
        val arr = data?.get("lines") as? JsonArray ?: return null
        return arr.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    }

    /** Publishes the loop status; logs each transition once. Not alive -> seq rate reads 0. */
    private fun applyLoop(status: LoopStatus) {
        val prev = synchronized(loopLock) {
            val p = _ui.value.loop
            _ui.update { it.copy(loop = status, seqRate = if (status == LoopStatus.Alive) it.seqRate else 0.0) }
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
