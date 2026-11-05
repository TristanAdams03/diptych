package com.diptych.app.kh1

import com.diptych.app.Fixtures
import com.diptych.app.kh1.TestData.data
import com.diptych.app.kh1.TestData.state
import com.diptych.app.net.RegionHex
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.parseServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeLink : Kh1Link {
    val sent = mutableListOf<List<Region>>()
    val bus = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val events: Flow<ServerMsg> = bus
    var failNext = 0
    override fun watch(regions: List<Region>): String? {
        sent += regions
        if (failNext > 0) { failNext--; return null }
        return "c${sent.size}"
    }
}

class Kh1SessionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val link = FakeLink()
    private val session = Kh1Session(link, data, scope)

    @After fun tearDown() = scope.cancel()

    private fun welcome(features: List<String> = listOf("spike", "watch")) =
        link.bus.tryEmit(ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true, features))

    private fun frame(world: Int?, room: Int?, regions: List<com.diptych.app.net.RegionHex>?) =
        link.bus.tryEmit(ServerMsg.State("kh1.spike", state(world, room, regions)))

    private val hbWatch = data.party.watch + data.world(15)!!.watch

    @Test fun welcomeSendsOneWatch() {
        welcome()
        assertEquals(1, link.sent.size)
        assertEquals(data.party.watch, link.sent[0])
        assertEquals(4, link.sent[0].size)
        assertEquals(Region(TestData.PARTY_BASE + 256, 16), link.sent[0][1])
        assertEquals(Region(TestData.SLOTS, 2), link.sent[0][3])
    }

    @Test fun worldChangeResendsWithNewSpans() {
        welcome()
        frame(13, 0, TestData.partyRegions())
        assertEquals(2, link.sent.size)
        assertEquals(data.party.watch + data.world(13)!!.watch, link.sent[1])
        frame(13, 1, TestData.partyRegions())
        assertEquals(2, link.sent.size)
        frame(15, 6, TestData.partyRegions())
        assertEquals(3, link.sent.size)
        assertEquals(hbWatch, link.sent[2])
    }

    @Test fun staleRegionsDoNotChangeChestCounts() {
        welcome()
        // Hollow Bastion's span starts at 48143040, where 3 Neverland chests also live (room 0 bits 0,1; room 6 bit 2).
        val stale = RegionHex(48143040L, "07" + "00".repeat(44))
        frame(15, 6, TestData.partyRegions() + stale)
        // in-flight frame for the new world still carries Hollow Bastion's region; without the filter room 6 would read opened
        frame(13, 6, TestData.partyRegions() + stale)
        val v = session.view.value!!
        assertEquals("Neverland", v.worldName)
        assertNull(v.rooms.first { it.id == 6 }.opened)
        assertNull(v.rooms.first { it.id == 0 }.opened)
        assertNull(v.chestsOpened)
        assertEquals(57, v.party[0].hp)
        // a frame for the new watch is trusted
        val real = RegionHex(48143012L, "00".repeat(29))
        frame(13, 6, TestData.partyRegions() + stale + real)
        assertEquals(0, session.view.value!!.rooms.first { it.id == 6 }.opened)
    }

    @Test fun failedSendRetriesOnNextFrame() {
        welcome()
        link.failNext = 1
        frame(15, 6, TestData.partyRegions())
        assertEquals(2, link.sent.size)
        assertEquals(hbWatch, link.sent[1])
        frame(15, 6, TestData.partyRegions())
        assertEquals(3, link.sent.size)
        frame(15, 6, TestData.partyRegions())
        assertEquals(3, link.sent.size)
    }

    @Test fun overLimitWorldWatchesPartyOnlyAndLogs() {
        val big = WorldDef(99, "Big", watch = List(5) { Region(48143000L + it * 300, 256) })
        val logs = mutableListOf<String>()
        val l = FakeLink()
        val s = CoroutineScope(Dispatchers.Unconfined)
        Kh1Session(l, data.copy(worlds = data.worlds + big), s, logs::add)
        l.bus.tryEmit(ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true, listOf("watch")))
        l.bus.tryEmit(ServerMsg.State("kh1.spike", state(99, 0, null)))
        assertEquals(2, l.sent.size)
        assertEquals(data.party.watch, l.sent[1])
        assertEquals(1, logs.size)
        s.cancel()
    }

    @Test fun everyShippedWorldFitsTheWatchLimits() {
        for (w in data.worlds) {
            val c = combinedWatch(data.party, w)
            assertNotNull("world ${w.id} ${w.name}", c)
            assertEquals(true, withinWatchLimits(c!!))
        }
        assertNull(combinedWatch(data.party, WorldDef(1, "x", watch = List(5) { Region(1L + it * 1000, 10) })))
        assertNull(combinedWatch(data.party, WorldDef(1, "x", watch = listOf(Region(1, 257)))))
    }

    @Test fun secondWelcomeResendsWatch() {
        welcome()
        frame(15, 6, TestData.partyRegions())
        assertEquals(2, link.sent.size)
        welcome()
        assertEquals(3, link.sent.size)
        assertEquals(hbWatch, link.sent[2])
        // regions are not trusted until the new watch has been sent; they match the same offsets afterwards
        frame(15, 6, TestData.partyRegions() + TestData.hbRegions())
        assertEquals(3, session.view.value!!.chestsOpened)
    }

    @Test fun welcomeWithoutWatchSendsNothingAndPartyIsUnknown() {
        welcome(listOf("spike"))
        frame(15, 6, TestData.partyRegions())
        assertEquals(0, link.sent.size)
        val v = session.view.value!!
        assertEquals(listOf(PartyMember("Sora", null, null, null, null)), v.party)
        assertNull(v.chestsOpened)
    }

    @Test fun unsupportedWelcomeSendsNothing() {
        link.bus.tryEmit(parseServer(Fixtures.load("welcome_unsupported.json")))
        assertEquals(0, link.sent.size)
        assertNull(session.view.value)
    }

    @Test fun fixtureWelcomeAndWatchFrameFlow() {
        link.bus.tryEmit(parseServer(Fixtures.load("welcome_steam.json")))
        assertEquals(1, link.sent.size)
        val st = parseServer(Fixtures.load("state_watch.json")) as ServerMsg.State
        assertNotNull(st.data.regions)
        assertNull(st.data.regions!![1].hex)
    }
}
