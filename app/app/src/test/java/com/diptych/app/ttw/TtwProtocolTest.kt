package com.diptych.app.ttw

import com.diptych.app.Fixtures
import com.diptych.app.net.ClientMsg
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.encodeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtwProtocolTest {
    private fun fx(name: String) = Fixtures.load("ttw/$name")
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test fun welcomeFixtureParses() {
        val m = parseTtw(fx("welcome.json")) as TtwMsg.Welcome
        assertEquals("0.0.1-spike", m.plugin)
        assertEquals(0, m.protocol)
        assertEquals("FalloutNV 1.4.0.525", m.game)
        assertEquals("6.4.9", m.xnvse)
        assertTrue(m.ttw)
        assertEquals(listOf("spike"), m.features)
    }

    @Test fun welcomeBecomesTheLinkWelcomeTheShellReads() {
        val w = (parseTtw(fx("welcome.json")) as TtwMsg.Welcome).toLink()
        assertEquals(ServerMsg.Welcome("0.0.1-spike", 0, "ttw", "FalloutNV 1.4.0.525 · xNVSE 6.4.9", true, listOf("spike")), w)
        // Before DeferredInit the plugin doesn't know TTW is loaded yet: that is "not supported" until it re-announces.
        val early = (parseTtw(fx("welcome.json").replace("\"ttw\":true", "\"ttw\":false")) as TtwMsg.Welcome).toLink()
        assertFalse(early.supported)
    }

    @Test fun stateFixtureParses() {
        val m = parseTtw(fx("state_spike.json")) as TtwMsg.State
        assertEquals("spike", m.topic)
        val d = m.data
        assertEquals(12345L, d.frame)
        assertEquals(205750L, d.ms)
        assertTrue(d.saveLoaded)
        assertEquals(245.0, d.hp!!, 0.0)
        assertEquals(280.0, d.hpMax!!, 0.0)
        assertEquals(false, d.hardcore)
        assertEquals(1, d.bInDCWasteland)
        assertEquals(0, d.bInNVWasteland)
        assertEquals("Wasteland", d.worldspace)
        assertEquals("Megaton", d.cell)
        assertEquals(false, d.interior)
        assertEquals(listOf(255, 182, 66), d.colors!!.hudMain)
        assertEquals(listOf(33, 231, 121), d.colors!!.terminal)
    }

    @Test fun stateWithNullsParses() {
        val text = """{"type":"state","topic":"spike","data":{"frame":7,"ms":100,"saveLoaded":false,
            "hp":null,"hpMax":null,"hardcore":null,"bInDCWasteland":null,"bInNVWasteland":null,
            "worldspace":null,"cell":null,"interior":null,"colors":null}}"""
        val d = (parseTtw(text) as TtwMsg.State).data
        assertEquals(7L, d.frame)
        assertFalse(d.saveLoaded)
        assertNull(d.hp); assertNull(d.hpMax); assertNull(d.hardcore)
        assertNull(d.bInDCWasteland); assertNull(d.bInNVWasteland)
        assertNull(d.worldspace); assertNull(d.cell); assertNull(d.interior); assertNull(d.colors)
    }

    @Test fun resultFixturesParse() {
        val ok = parseTtw(fx("result_ok.json")) as TtwMsg.Result
        assertEquals("c1", ok.id)
        assertTrue(ok.ok)
        assertNull(ok.error)
        assertEquals("Lincoln's Repeater", ok.data!!["name"]!!.jsonPrimitive.content)

        val noSave = parseTtw(fx("result_no_save.json")) as TtwMsg.Result
        assertFalse(noSave.ok)
        assertEquals("no save loaded", noSave.error)
        assertNull(noSave.data)
    }

    @Test fun pongAndErrorFixturesParse() {
        assertEquals(TtwMsg.Pong(5), parseTtw(fx("pong.json")))
        assertEquals(TtwMsg.Error("invalid json"), parseTtw(fx("error.json")))
    }

    @Test fun unknownAndMalformedAreUnknown() {
        assertEquals("bogus", (parseTtw("""{"type":"bogus","x":1}""") as TtwMsg.Unknown).type)
        assertTrue(parseTtw("not json") is TtwMsg.Unknown)
        assertTrue(parseTtw("[1,2]") is TtwMsg.Unknown)
        assertTrue(parseTtw("""{"type":"pong"}""") is TtwMsg.Unknown)
        // A KH1 welcome on this port is not a TTW welcome.
        assertTrue(parseTtw(Fixtures.load("welcome_steam.json")) is TtwMsg.Unknown)
    }

    @Test fun clientMessagesMatchFixtures() {
        // hello's client name is free text; the plugin only logs it.
        assertEquals(json(fx("hello.json").replace("ttwds-app", TtwClient.CLIENT_NAME)), json(encodeClient(ClientMsg.Hello(TtwClient.CLIENT_NAME, 0))))
        assertEquals(json(fx("subscribe.json")), json(encodeClient(ClientMsg.Subscribe(listOf(TTW_TOPIC), TtwClient.RATE_HZ))))
        assertEquals(json(fx("ping.json")), json(encodeClient(ClientMsg.Ping(5))))
        assertEquals(json(fx("command_equip.json")), json(encodeClient(ClientMsg.Command("c1", CMD_EQUIP))))
    }
}
