package com.diptych.app.ttw

import com.diptych.app.Fixtures
import com.diptych.app.LoopStatus
import com.diptych.app.net.ServerMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames are fed straight into a client that was never started: no socket. */
class TtwClientTest {
    private fun fx(name: String) = Fixtures.load("ttw/$name")

    @Test fun unsolicitedWelcomeReplacesEarlierOne() {
        // The plugin answers `hello` with ttw:false before DeferredInit, then pushes a welcome with ttw:true.
        val c = TtwClient()
        c.handle(fx("welcome.json").replace("\"ttw\":true", "\"ttw\":false"))
        assertFalse(c.ui.value.welcome!!.ttw)
        c.handle(fx("welcome.json"))
        assertTrue(c.ui.value.welcome!!.ttw)
        assertTrue(c.log.value.last().endsWith("ttw=true"))
        c.stop()
    }

    @Test fun onlyWelcomesReachTheEventsFlowAsLinkWelcomes() {
        val c = TtwClient()
        val got = mutableListOf<ServerMsg>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { got += it } }
        c.handle(fx("welcome.json"))
        c.handle(fx("state_spike.json"))
        c.handle(fx("pong.json"))
        c.handle(fx("result_ok.json"))
        assertEquals(1, got.size)
        assertEquals("ttw", (got[0] as ServerMsg.Welcome).gameId)
        scope.cancel()
        c.stop()
    }

    @Test fun stateAndResultsLandInTheUi() {
        var now = 1_000L
        val c = TtwClient(clock = { now })
        c.handle(fx("state_spike.json"))
        assertEquals("Megaton", c.ui.value.state!!.cell)
        now += 100
        c.handle("""{"type":"pong","t":1050}""")
        assertEquals(50L, c.ui.value.rttMs)
        assertEquals(LoopStatus.Alive, c.ui.value.loop)
        c.handle(fx("result_no_save.json"))
        assertEquals("c1 FAIL no save loaded", c.ui.value.lastResult)
        c.handle("""{"type":"state","topic":"other","data":{"frame":1,"ms":1,"saveLoaded":true}}""")
        assertEquals("Megaton", c.ui.value.state!!.cell) // a frame for another topic changes nothing
        c.stop()
    }

    @Test fun commandsNeedASocket() {
        val c = TtwClient()
        assertNull(c.equipFirstWeapon())
        assertNull(c.dumpWorld())
        assertTrue(c.log.value.last().contains("not connected"))
        c.stop()
    }
}
