package com.diptych.app

import com.diptych.app.net.ClientMsg
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.encodeClient
import com.diptych.app.net.parseServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun fx(name: String) = Fixtures.load(name)
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test fun welcomeSteamFixtureParses() {
        val m = parseServer(fx("welcome_steam.json"))
        assertTrue(m is ServerMsg.Welcome)
        m as ServerMsg.Welcome
        assertEquals("0.0.1-spike", m.plugin)
        assertEquals(0, m.protocol)
        assertEquals("kh1", m.gameId)
        assertEquals("steam-1.0.0.2", m.gameBuild)
        assertTrue(m.supported)
        assertEquals(listOf("spike"), m.features)
    }

    @Test fun welcomeUnsupportedFixtureParses() {
        val m = parseServer(fx("welcome_unsupported.json"))
        assertTrue(m is ServerMsg.Welcome)
        m as ServerMsg.Welcome
        assertEquals("egs-1.0.0.10", m.gameBuild)
        assertFalse(m.supported)
    }

    @Test fun stateFixtureParses() {
        val m = parseServer(fx("state_spike.json"))
        assertTrue(m is ServerMsg.State)
        m as ServerMsg.State
        assertEquals("kh1.spike", m.topic)
        val d = m.data
        assertEquals(42L, d.seq)
        assertEquals(205750L, d.ms)
        assertEquals(3, d.world)
        assertEquals(2, d.room)
        assertEquals(3, d.worldAlt)
        assertEquals(52, d.soraHp)
        assertEquals(60, d.soraHpMax)
        assertEquals(false, d.inMenu)
        assertEquals(false, d.inCutscene)
        assertEquals(false, d.inGummi)
        assertEquals("00000000", d.inputBytes)
    }

    @Test fun unsupportedStateIsAllNullExceptSeqMs() {
        val m = parseServer(fx("state_unsupported.json"))
        assertTrue(m is ServerMsg.State)
        val d = (m as ServerMsg.State).data
        assertEquals(7L, d.seq)
        assertEquals(41200L, d.ms)
        assertNull(d.world); assertNull(d.room); assertNull(d.worldAlt)
        assertNull(d.soraHp); assertNull(d.soraHpMax)
        assertNull(d.inMenu); assertNull(d.inCutscene); assertNull(d.inGummi)
        assertNull(d.inputBytes)
    }

    @Test fun resultFixturesParse() {
        val probe = parseServer(fx("result_probe.json")) as ServerMsg.Result
        assertEquals("c1", probe.id)
        assertTrue(probe.ok)
        assertNull(probe.error)
        assertEquals("03000000", probe.data!!["hex"]!!.jsonPrimitive.content)

        val log = parseServer(fx("result_log.json")) as ServerMsg.Result
        assertTrue(log.ok)
        assertEquals(3, log.data!!["lines"]!!.jsonArray.size)

        val refused = parseServer(fx("result_press_refused.json"))
        assertTrue(refused is ServerMsg.Result)
        refused as ServerMsg.Result
        assertEquals("c2", refused.id)
        assertFalse(refused.ok)
        assertEquals("not safe now", refused.error)
        assertNull(refused.data)
    }

    @Test fun badCommandWithEmptyIdParses() {
        val m = parseServer("""{"type":"result","id":"","ok":false,"error":"bad command","data":null}""")
        assertEquals(ServerMsg.Result("", false, "bad command", null), m)
    }

    @Test fun pongFixtureParses() = assertEquals(ServerMsg.Pong(5), parseServer(fx("pong.json")))

    @Test fun errorFixtureParses() = assertEquals(ServerMsg.Error("invalid json"), parseServer(fx("error.json")))

    @Test fun unknownTypeIsUnknown() {
        val m = parseServer("""{"type":"bogus","x":1}""")
        assertTrue(m is ServerMsg.Unknown)
        assertEquals("bogus", (m as ServerMsg.Unknown).type)
    }

    @Test fun malformedIsUnknown() {
        assertTrue(parseServer("not json") is ServerMsg.Unknown)
        assertTrue(parseServer("[1,2]") is ServerMsg.Unknown)
        assertTrue(parseServer("""{"type":"pong"}""") is ServerMsg.Unknown)
        assertTrue(parseServer("""{"type":"state","topic":"kh1.spike","data":{}}""") is ServerMsg.Unknown)
    }

    @Test fun clientMessagesMatchFixtures() {
        assertEquals(json(fx("hello.json")), json(encodeClient(ClientMsg.Hello("diptych-app", 0))))
        assertEquals(json(fx("subscribe.json")), json(encodeClient(ClientMsg.Subscribe(listOf("kh1.spike"), 5))))
        assertEquals(json(fx("ping.json")), json(encodeClient(ClientMsg.Ping(5))))
        assertEquals(json(fx("command_log.json")), json(encodeClient(ClientMsg.Command("c3", "kh1.log"))))
        assertEquals(json(fx("command_probe.json")), json(encodeClient(ClientMsg.Command("c1", "kh1.probe",
            JsonObject(mapOf("offset" to JsonPrimitive(36961940), "length" to JsonPrimitive(4)))))))
        assertEquals(json(fx("command_press.json")), json(encodeClient(ClientMsg.Command("c2", "kh1.pressInput",
            JsonObject(mapOf("byteIndex" to JsonPrimitive(0), "mask" to JsonPrimitive(16),
                "durationMs" to JsonPrimitive(100)))))))
    }
}
