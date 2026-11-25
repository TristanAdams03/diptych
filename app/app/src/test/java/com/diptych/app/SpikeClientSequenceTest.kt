package com.diptych.app

import com.diptych.app.net.PressSequenceArgs
import com.diptych.app.net.SeqStep
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.SpikeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `kh1.pressSequence` waits for its own result, by id, and fails when the socket goes first. */
class SpikeClientSequenceTest {
    private val args = PressSequenceArgs(steps = listOf(SeqStep(1, 0x14, 100, 50)))

    /** The id of the first `kh1.pressSequence` frame [s] sent, waiting for it to be sent. */
    private fun sentSequenceId(s: FakeSocket): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val frame = synchronized(s) { s.sent.toList() }.map { Json.parseToJsonElement(it).jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content == "kh1.pressSequence" }
            if (frame != null) return frame["id"]!!.jsonPrimitive.content
            Thread.sleep(10)
        }
        throw AssertionError("no kh1.pressSequence sent: ${s.sent}")
    }

    private fun connected(welcome: String = Fixtures.load("welcome_steam.json")): Triple<SpikeClient, FakeSocket, FakeSocketFactory> {
        val net = FakeSocketFactory()
        val c = SpikeClient(http = net)
        c.start()
        val s = net.next()
        s.open()
        s.listener.onMessage(s, welcome)
        return Triple(c, s, net)
    }

    @Test fun theResultWithItsIdCompletesTheSequence() = runBlocking {
        val (c, s) = connected()
        try {
            assertTrue(c.canSequence)
            val r = async(Dispatchers.IO) { c.pressSequence(args) }
            val id = sentSequenceId(s)
            // Another command's result first: it must not complete this one.
            s.listener.onMessage(s, """{"type":"result","id":"other","ok":true,"error":null,"data":null}""")
            s.listener.onMessage(s, Fixtures.load("result_sequence.json").replace("\"c5\"", "\"$id\""))
            val result = withTimeout(5_000) { r.await() }!!
            assertEquals(id, result.id)
            assertTrue(result.ok)
        } finally {
            c.stop()
        }
    }

    @Test fun aTimeoutResultIsReturnedAsIs() = runBlocking {
        val (c, s) = connected()
        try {
            val r = async(Dispatchers.IO) { c.pressSequence(args) }
            val id = sentSequenceId(s)
            s.listener.onMessage(s, Fixtures.load("result_sequence_timeout.json").replace("\"c5\"", "\"$id\""))
            val result = withTimeout(5_000) { r.await() }!!
            assertFalse(result.ok)
            assertEquals("timeout", result.error)
        } finally {
            c.stop()
        }
    }

    @Test fun aSocketThatGoesFailsThePendingSequence() = runBlocking {
        val (c, s) = connected()
        try {
            val r = async(Dispatchers.IO) { c.pressSequence(args) }
            sentSequenceId(s)
            s.listener.onClosed(s, 1000, "game closed")
            val result = withTimeout(5_000) { r.await() }!!
            assertFalse(result.ok)
            assertEquals(SpikeClient.ERR_DISCONNECTED, result.error)
            assertFalse(c.canSequence) // the welcome went with the socket
        } finally {
            c.stop()
        }
    }

    @Test fun stopFailsThePendingSequence() = runBlocking {
        val (c, s) = connected()
        val r = async(Dispatchers.IO) { c.pressSequence(args) }
        sentSequenceId(s)
        c.stop()
        val result = withTimeout(5_000) { r.await() }!!
        assertEquals(SpikeClient.ERR_DISCONNECTED, result.error)
    }

    @Test fun aModWithoutTheSequenceFeatureGetsNothing() = runBlocking {
        val old = """{"type":"welcome","plugin":"0.1.0","protocol":0,"gameId":"kh1","gameBuild":"steam-1.0.0.2","supported":true,"features":["spike","watch"]}"""
        val (c, s) = connected(old)
        try {
            assertFalse(c.canSequence)
            assertNull(c.pressSequence(args))
            assertTrue(s.sent.none { it.contains("kh1.pressSequence") })
        } finally {
            c.stop()
        }
    }

    @Test fun notConnectedGetsNothing() = runBlocking {
        val c = SpikeClient(http = FakeSocketFactory())
        assertNull(c.pressSequence(args))
        c.stop()
    }

    @Test fun anUnsupportedBuildCannotSequence() {
        val (c, s) = connected(Fixtures.load("welcome_unsupported.json"))
        try {
            assertFalse(c.canSequence)
            assertTrue(s.sent.isNotEmpty()) // hello + subscribe went out
        } finally {
            c.stop()
        }
    }
}
