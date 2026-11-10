package com.diptych.app.service

import com.diptych.app.SpikeUi
import com.diptych.app.kh1.Kh1Data
import com.diptych.app.kh1.Kh1Link
import com.diptych.app.kh1.Kh1Session
import com.diptych.app.kh1.MapView
import com.diptych.app.kh1.Region
import com.diptych.app.net.ServerMsg
import com.diptych.app.shell.SeenRecord
import com.diptych.app.shell.SeenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Catalog id of the game on the link's port (7970 is KH1's). Its SeenRecord is rewritten on every welcome. */
const val LINK_GAME_ID = "kh1"

/**
 * What the app knows about the game link right now.
 * - [ui]: the client's diagnostics, as the spike screen shows them. Its `welcome` is the last one ever seen.
 * - [view]: the last KH1 map the session built. It survives a disconnect, so the companion can keep showing it.
 * - [welcome]: the live handshake: set by the current connection's welcome, cleared when that socket closes.
 *   Use it, not `ui.welcome`, to decide whether a game is connected.
 */
data class ConnectionSnapshot(
    val ui: SpikeUi = SpikeUi(),
    val view: MapView? = null,
    val welcome: ServerMsg.Welcome? = null,
)

/**
 * Applies one message from the client's events flow, in arrival order, to the snapshot. Pure.
 * It never sets the view: only the KH1 session can build one (it needs the world data and the watch it sent),
 * so [ConnectionCore] copies the session's view in.
 */
fun reduceConnection(prev: ConnectionSnapshot, msg: ServerMsg): ConnectionSnapshot = when (msg) {
    is ServerMsg.Welcome -> prev.copy(welcome = msg)
    ServerMsg.Disconnected -> prev.copy(welcome = null) // the view stays
    else -> prev
}

/**
 * Review Focus 1: must the activity call `startForegroundService`? [running] means started and in the foreground in
 * this process ([ConnectionService.running]); [bound] means the activity holds a binding.
 */
fun shouldStartService(bound: Boolean, running: Boolean): Boolean = when {
    running -> false // binding reaches it and reads its current state; a second start would only re-run onStartCommand
    bound -> true // a service that is only bound dies with the activity and is never foreground
    else -> true // cold start, or Android killed the process and START_STICKY hasn't brought the service back yet
}

/** The activity asks for POST_NOTIFICATIONS once, on API 33+. Denied is fine: the service runs, its notification hidden. */
fun shouldAskNotifications(sdk: Int, asked: Boolean, granted: Boolean): Boolean = sdk >= 33 && !asked && !granted

/** The slice of the client the core needs; implemented by SpikeClient. */
interface GameLink : Kh1Link {
    val ui: StateFlow<SpikeUi>
    fun log(line: String)
    fun start()
    fun stop()
}

/**
 * The game link for one service lifetime: one client, one KH1 session, one snapshot. Plain JVM, so tests drive it.
 * [open] creates and starts the link on its first call and returns that same link on every later call, so however
 * often the service is started or bound there is only ever one socket.
 */
class ConnectionCore<L : GameLink>(
    private val newLink: () -> L,
    private val data: Kh1Data,
    private val scope: CoroutineScope,
    private val seen: SeenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ConnectionSnapshot())
    val state: StateFlow<ConnectionSnapshot> = _state.asStateFlow()

    private var link: L? = null
    private var closed = false

    @Synchronized
    fun open(): L {
        check(!closed) { "connection core is closed" }
        link?.let { return it }
        val l = newLink()
        link = l
        // The events flow has no replay, so its reader is subscribed before start(): this collector starts undispatched,
        // so it holds its subscription by the time launch returns. It hands each message to the KH1 session (created
        // here, before start) through an unbounded channel that keeps them until the session's coroutine first runs.
        val forSession = Channel<ServerMsg>(Channel.UNLIMITED)
        val session = Kh1Session(RelayLink(l, forSession.receiveAsFlow()), data, scope, l::log)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            l.events.collect { m ->
                forSession.trySend(m)
                _state.update { reduceConnection(it, m) }
                if (m is ServerMsg.Welcome) {
                    seen.put(LINK_GAME_ID, SeenRecord(m.plugin, m.gameBuild, m.supported, clock()))
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { l.ui.collect { u -> _state.update { it.copy(ui = u) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.view.collect { v -> _state.update { it.copy(view = v) } }
        }
        l.start()
        return l
    }

    /** Stops the link. The core never opens another one. */
    @Synchronized
    fun close() {
        closed = true
        link?.stop()
    }
}

/** The session's view of the link: the core's relayed events, the client's watch. */
private class RelayLink(private val link: Kh1Link, override val events: Flow<ServerMsg>) : Kh1Link {
    override fun watch(regions: List<Region>): String? = link.watch(regions)
}
