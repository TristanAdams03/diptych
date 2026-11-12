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
import com.diptych.app.ttw.TTW_ID
import com.diptych.app.ttw.TtwUi
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

/** Catalog id of Kingdom Hearts Final Mix, the game on port 7970. TTW's ([TTW_ID]) is on 7960. */
const val KH1_ID = "kh1"

/**
 * What the app knows about its game links right now. There is one link per supported game (KH1 on 7970, TTW on
 * 7960); each keeps trying its port, and whichever game is running answers.
 * - [ui]: the KH1 client's diagnostics, as the spike screen shows them. Its `welcome` is the last one ever seen.
 * - [view]: the last KH1 map the session built. It survives a disconnect, so the companion can keep showing it.
 * - [welcome]: the live handshake of the game that has the screen: set by a link's welcome, cleared when that socket
 *   closes. Use it, not `ui.welcome`, to decide whether a game is connected, and its `gameId` to tell which.
 * - [ttw]: the TTW client's handshake, last frame and link health.
 * - [live]: every link's live welcome by game id, in the order they went live. [welcome] is its first value.
 */
data class ConnectionSnapshot(
    val ui: SpikeUi = SpikeUi(),
    val view: MapView? = null,
    val welcome: ServerMsg.Welcome? = null,
    val ttw: TtwUi = TtwUi(),
    val live: Map<String, ServerMsg.Welcome> = emptyMap(),
)

/**
 * Applies one message from the events flow of [gameId]'s link, in arrival order, to the snapshot. Pure.
 * The first game to go live has the screen until its socket closes; then the next live one does (two games can't
 * really run at once, but both links may be live for a moment while one closes).
 * It never sets the view: only the KH1 session can build one (it needs the world data and the watch it sent),
 * so [ConnectionCore] copies the session's view in.
 */
fun reduceConnection(prev: ConnectionSnapshot, msg: ServerMsg, gameId: String = KH1_ID): ConnectionSnapshot = when (msg) {
    is ServerMsg.Welcome -> (prev.live + (gameId to msg)).let { prev.copy(live = it, welcome = it.values.first()) }
    ServerMsg.Disconnected -> (prev.live - gameId).let { prev.copy(live = it, welcome = it.values.firstOrNull()) } // the view stays
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

/**
 * The slice of a game's client the core needs. [events] carries that game's welcomes in the shared shape (its
 * `gameId` is the game's catalog id) and [ServerMsg.Disconnected]; it has no replay.
 */
interface GameLink {
    val events: Flow<ServerMsg>
    fun log(line: String)
    fun start()
    fun stop()
}

/** KH1's link; implemented by SpikeClient. */
interface Kh1GameLink : GameLink, Kh1Link {
    override val events: Flow<ServerMsg>
    val ui: StateFlow<SpikeUi>
}

/** TTW's link; implemented by TtwClient. */
interface TtwGameLink : GameLink {
    val ui: StateFlow<TtwUi>
}

/**
 * The game links for one service lifetime: one client per game, one KH1 session, one snapshot. Plain JVM, so tests
 * drive it. [open] creates and starts the links on its first call and returns the same KH1 link on every later call,
 * so however often the service is started or bound there is only ever one socket per game.
 * [newTtw] is null where only KH1 is wanted (tests).
 */
class ConnectionCore<L : Kh1GameLink>(
    private val newLink: () -> L,
    private val data: Kh1Data,
    private val scope: CoroutineScope,
    private val seen: SeenStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newTtw: (() -> TtwGameLink)? = null,
) {
    private val _state = MutableStateFlow(ConnectionSnapshot())
    val state: StateFlow<ConnectionSnapshot> = _state.asStateFlow()

    private var link: L? = null
    private var ttw: TtwGameLink? = null
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
        follow(KH1_ID, l) { forSession.trySend(it) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { l.ui.collect { u -> _state.update { it.copy(ui = u) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.view.collect { v -> _state.update { it.copy(view = v) } }
        }
        newTtw?.invoke()?.let { t ->
            ttw = t
            follow(TTW_ID, t)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { t.ui.collect { u -> _state.update { it.copy(ttw = u) } } }
            t.start()
        }
        l.start()
        return l
    }

    /**
     * Subscribes to [link]'s events before it starts (the collector starts undispatched): each message goes to
     * [also], then into the snapshot as [gameId]'s, and a welcome rewrites [gameId]'s SeenRecord.
     */
    private fun follow(gameId: String, link: GameLink, also: (ServerMsg) -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { m ->
                also(m)
                _state.update { reduceConnection(it, m, gameId) }
                if (m is ServerMsg.Welcome) seen.put(gameId, SeenRecord(m.plugin, m.gameBuild, m.supported, clock()))
            }
        }
    }

    /** Stops the links. The core never opens another one. */
    @Synchronized
    fun close() {
        closed = true
        link?.stop()
        ttw?.stop()
    }
}

/** The session's view of the link: the core's relayed events, the client's watch. */
private class RelayLink(private val link: Kh1Link, override val events: Flow<ServerMsg>) : Kh1Link {
    override fun watch(regions: List<Region>): String? = link.watch(regions)
}
