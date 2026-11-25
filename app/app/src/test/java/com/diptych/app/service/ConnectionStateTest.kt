package com.diptych.app.service

import com.diptych.app.SpikeUi
import com.diptych.app.kh1.Region
import com.diptych.app.kh1.TestData
import com.diptych.app.kh1.buildMapView
import com.diptych.app.net.ServerMsg
import com.diptych.app.shell.FakePrefs
import com.diptych.app.ttw.TtwUi
import com.diptych.app.shell.SeenRecord
import com.diptych.app.shell.SeenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

private val WELCOME = ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true, listOf("spike", "watch"))
private val WELCOME_EGS = ServerMsg.Welcome("0.1.0", 0, "kh1", "egs-1.0.0.10", false, listOf("spike"))

private val WELCOME_TTW = ServerMsg.Welcome("0.0.1-spike", 0, "ttw", "FalloutNV 1.4.0.525 · xNVSE 6.4.9", true, listOf("spike"))

private class FakeTtwLink : TtwGameLink {
    val bus = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val events: Flow<ServerMsg> = bus
    override val ui = MutableStateFlow(TtwUi())
    var starts = 0
    var stops = 0
    override fun log(line: String) {}
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

private fun frame(world: Int?, room: Int?, seq: Long = 1) =
    ServerMsg.State("kh1.spike", TestData.state(world, room, TestData.partyRegions(), seq))

/** Queues every dispatch until [runAll], so tests decide when coroutines get to run (like a busy device would). */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
    fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}

private class FakeGameLink(private val onStart: FakeGameLink.() -> Unit = {}) : Kh1GameLink {
    val bus = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val events: Flow<ServerMsg> = bus
    override val ui = MutableStateFlow(SpikeUi())
    val sent = mutableListOf<List<Region>>()
    var starts = 0
    var stops = 0
    override fun watch(regions: List<Region>): String? { sent += regions; return "c${sent.size}" }
    override fun log(line: String) {}
    override fun start() { starts++; onStart() }
    override fun stop() { stops++ }
}

class ConnectionStateTest {
    // --- the pure reducer ---

    @Test fun welcomeSetsTheWelcome() {
        val s = reduceConnection(ConnectionSnapshot(), WELCOME)
        assertEquals(WELCOME, s.welcome)
    }

    @Test fun laterWelcomeReplacesTheEarlierOne() {
        // The mod pushes an unsolicited welcome when it re-classifies its build.
        val s = reduceConnection(reduceConnection(ConnectionSnapshot(), WELCOME), WELCOME_EGS)
        assertEquals(WELCOME_EGS, s.welcome)
    }

    @Test fun disconnectClearsTheWelcomeButKeepsTheLastView() {
        val view = buildMapView(TestData.data, TestData.state(13, 0, TestData.partyRegions()))
        val ui = SpikeUi(connected = true, welcome = WELCOME)
        val s = reduceConnection(ConnectionSnapshot(ui, view, WELCOME), ServerMsg.Disconnected)
        assertNull(s.welcome)
        assertSame(view, s.view)
        assertEquals(ui, s.ui) // ui belongs to the client; the reducer leaves it alone
    }

    @Test fun theFirstLiveGameHoldsTheScreenUntilItGoes() {
        // Two games can't really run at once, but two links can both be live for a moment.
        var s = reduceConnection(ConnectionSnapshot(), WELCOME, "kh1")
        s = reduceConnection(s, WELCOME_TTW, "ttw")
        assertEquals(WELCOME, s.welcome)
        assertEquals(mapOf("kh1" to WELCOME, "ttw" to WELCOME_TTW), s.live)
        s = reduceConnection(s, ServerMsg.Disconnected, "kh1")
        assertEquals(WELCOME_TTW, s.welcome)
        s = reduceConnection(s, ServerMsg.Disconnected, "ttw")
        assertNull(s.welcome)
        assertTrue(s.live.isEmpty())
    }

    @Test fun aSupportedGameTakesTheScreenFromAnUnsupportedLiveOne() {
        // New Vegas at the main menu (before DeferredInit) reports ttw:false; KH1 then goes live supported.
        val ttwNotLoaded = WELCOME_TTW.copy(supported = false)
        var s = reduceConnection(ConnectionSnapshot(), ttwNotLoaded, "ttw")
        assertEquals(ttwNotLoaded, s.welcome) // alone, the unsupported one still has the screen (the Shelf says why)
        s = reduceConnection(s, WELCOME, "kh1")
        assertEquals(WELCOME, s.welcome)
        assertEquals(mapOf("ttw" to ttwNotLoaded, "kh1" to WELCOME), s.live)
        s = reduceConnection(s, ServerMsg.Disconnected, "kh1")
        assertEquals(ttwNotLoaded, s.welcome)
    }

    @Test fun aDisconnectOfTheOtherGameLeavesTheLiveOneAlone() {
        val s = reduceConnection(reduceConnection(ConnectionSnapshot(), WELCOME_TTW, "ttw"), ServerMsg.Disconnected, "kh1")
        assertEquals(WELCOME_TTW, s.welcome)
    }

    @Test fun otherFramesLeaveTheSnapshotAlone() {
        val prev = ConnectionSnapshot(SpikeUi(), null, WELCOME)
        for (m in listOf(
            frame(13, 0), ServerMsg.Pong(1), ServerMsg.Result("c1", true, null, JsonObject(emptyMap())),
            ServerMsg.Error("bad"), ServerMsg.Unknown("x", "{}"),
        )) assertSame(m.toString(), prev, reduceConnection(prev, m))
    }

    // --- the core the service runs: real Kh1Session, fake socket ---

    private val dispatcher = ManualDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val prefs = FakePrefs()
    private var now = 1_000L
    private var created = 0
    private var link = FakeGameLink()
    private val ttw = FakeTtwLink()
    private val core = ConnectionCore(
        newLink = { created++; link },
        newTtw = { ttw },
        data = TestData.data,
        scope = scope,
        seen = SeenStore(prefs),
        clock = { now },
    )

    @After fun tearDown() = scope.cancel()

    private fun emit(m: ServerMsg) { assertTrue(link.bus.tryEmit(m)); dispatcher.runAll() }

    @Test fun stateFrameUpdatesTheView() {
        core.open(); dispatcher.runAll()
        emit(WELCOME)
        assertNull(core.state.value.view)
        emit(frame(13, 0))
        val v = core.state.value.view
        assertNotNull(v)
        assertEquals("Neverland", v!!.worldName)
        emit(frame(15, 6, seq = 2))
        assertEquals("Hollow Bastion", core.state.value.view!!.worldName)
    }

    @Test fun disconnectKeepsTheLastViewAndClearsTheWelcome() {
        core.open(); dispatcher.runAll()
        emit(WELCOME)
        emit(frame(13, 0))
        val view = core.state.value.view
        assertNotNull(view)
        assertEquals(WELCOME, core.state.value.welcome)

        emit(ServerMsg.Disconnected)
        assertNull(core.state.value.welcome)
        assertEquals(view, core.state.value.view)

        emit(WELCOME_EGS) // reconnect: only the new connection's welcome is live
        assertEquals(WELCOME_EGS, core.state.value.welcome)
    }

    @Test fun snapshotCarriesTheClientUi() {
        core.open(); dispatcher.runAll()
        link.ui.value = SpikeUi(connected = true, rttMs = 3)
        dispatcher.runAll()
        assertEquals(SpikeUi(connected = true, rttMs = 3), core.state.value.ui)
    }

    @Test fun everyWelcomeWritesASeenRecordForKh1() {
        core.open(); dispatcher.runAll()
        emit(WELCOME)
        assertEquals(SeenRecord("0.1.0", "steam-1.0.0.2", true, 1_000L), SeenStore(prefs).get("kh1"))
        now = 2_000L
        emit(WELCOME_EGS)
        assertEquals(SeenRecord("0.1.0", "egs-1.0.0.10", false, 2_000L), SeenStore(prefs).get("kh1"))
    }

    @Test fun sessionSeesAWelcomeThatArrivesDuringStart() {
        // The events flow has no replay. Even if the welcome lands before any coroutine has been dispatched,
        // the KH1 session must still get it and send its watch.
        link = FakeGameLink(onStart = { assertTrue(bus.tryEmit(WELCOME)) })
        core.open()
        dispatcher.runAll()
        assertEquals(1, link.sent.size)
        assertEquals(TestData.data.party.watch, link.sent[0])
        assertEquals(WELCOME, core.state.value.welcome)
    }

    @Test fun repeatedStartsAndBindsShareOneLink() {
        // onStartCommand runs on every startForegroundService and onBind on every bind: none may open a second socket.
        val first = core.open()
        repeat(3) { assertSame(first, core.open()) }
        assertEquals(1, created)
        assertEquals(1, link.starts)
    }

    // --- the second link: TTW on 7960 ---

    @Test fun bothLinksStartOnceAndStopTogether() {
        core.open(); core.open()
        assertEquals(1, ttw.starts)
        core.close()
        assertEquals(1, ttw.stops)
    }

    @Test fun aTtwWelcomeIsLiveAndWritesItsOwnSeenRecord() {
        core.open(); dispatcher.runAll()
        assertTrue(ttw.bus.tryEmit(WELCOME_TTW)); dispatcher.runAll()
        assertEquals(WELCOME_TTW, core.state.value.welcome)
        assertEquals(SeenRecord("0.0.1-spike", "FalloutNV 1.4.0.525 · xNVSE 6.4.9", true, 1_000L), SeenStore(prefs).get("ttw"))
        assertNull(SeenStore(prefs).get("kh1"))
        assertTrue(link.sent.isEmpty()) // the KH1 session never sees another game's welcome

        assertTrue(ttw.bus.tryEmit(ServerMsg.Disconnected)); dispatcher.runAll()
        assertNull(core.state.value.welcome)
    }

    @Test fun snapshotCarriesTheTtwUi() {
        core.open(); dispatcher.runAll()
        ttw.ui.value = TtwUi(connected = true, rttMs = 4)
        dispatcher.runAll()
        assertEquals(TtwUi(connected = true, rttMs = 4), core.state.value.ttw)
    }

    @Test fun closeStopsTheLinkAndNeverReopens() {
        core.open()
        core.close()
        assertEquals(1, link.stops)
        assertEquals(1, created)
        assertTrue(runCatching { core.open() }.isFailure)
        assertEquals(1, created)
    }

    // --- Review Focus 1: when the activity must (re)start the service ---

    @Test fun startsWhenNothingIsRunning() = assertTrue(shouldStartService(bound = false, running = false))

    @Test fun bindsWithoutRestartingARunningService() = assertFalse(shouldStartService(bound = false, running = true))

    @Test fun boundAndRunningNeedsNothing() = assertFalse(shouldStartService(bound = true, running = true))

    @Test fun boundIsNotRunning() {
        // A service that is only bound dies with the activity and is never foreground.
        assertTrue(shouldStartService(bound = true, running = false))
    }

    // --- POST_NOTIFICATIONS is asked once, on API 33+ ---

    @Test fun asksForNotificationsOnceOnApi33() {
        assertTrue(shouldAskNotifications(sdk = 33, asked = false, granted = false))
        assertFalse(shouldAskNotifications(sdk = 33, asked = true, granted = false))
        assertFalse(shouldAskNotifications(sdk = 34, asked = false, granted = true))
        assertFalse(shouldAskNotifications(sdk = 32, asked = false, granted = false))
    }
}
