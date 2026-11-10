package com.diptych.app

import com.diptych.app.net.ServerMsg
import com.diptych.app.net.SpikeClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A socket that never touches the network: the test plays OkHttp's reader thread through its listener. */
private class FakeSocket(private val req: Request, val listener: WebSocketListener) : WebSocket {
    val sent = mutableListOf<String>()
    override fun request() = req
    override fun queueSize() = 0L
    @Synchronized override fun send(text: String): Boolean { sent += text; return true }
    override fun send(bytes: ByteString) = true
    override fun close(code: Int, reason: String?) = true
    override fun cancel() {}

    fun open() = listener.onOpen(
        this,
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build(),
    )
}

/** Hands each connect attempt's socket to the test instead of dialing. */
private class FakeSocketFactory : OkHttpClient() {
    val sockets = LinkedBlockingQueue<FakeSocket>()
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        FakeSocket(request, listener).also { sockets.put(it) }

    fun next(): FakeSocket = sockets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the client never dialed")
}

/** Review Focus 4 rests on this: when the game closes, the events flow says so, after the socket's last frame. */
class SpikeClientDisconnectTest {
    private val welcome = Fixtures.load("welcome_steam.json")

    @Test fun aSocketCloseEmitsDisconnectedAfterItsLastFrame() = runBlocking {
        val net = FakeSocketFactory()
        val c = SpikeClient(http = net)
        // The events flow has no replay: subscribe before anything can arrive.
        val got = async(start = CoroutineStart.UNDISPATCHED) { c.events.take(2).toList() }
        c.start()
        try {
            val s = net.next()
            s.open()
            s.listener.onMessage(s, welcome)
            s.listener.onClosed(s, 1000, "game closed")
            val msgs = withTimeout(5_000) { got.await() }
            assertTrue(msgs[0].toString(), msgs[0] is ServerMsg.Welcome)
            assertEquals(ServerMsg.Disconnected, msgs[1])
        } finally {
            c.stop()
        }
    }

    @Test fun aReadFailureAfterOpeningAlsoEmitsDisconnected() = runBlocking {
        val net = FakeSocketFactory()
        val c = SpikeClient(http = net)
        val got = async(start = CoroutineStart.UNDISPATCHED) { c.events.take(2).toList() }
        c.start()
        try {
            val s = net.next()
            s.open()
            s.listener.onMessage(s, welcome)
            s.listener.onFailure(s, java.io.EOFException("game process exited"), null)
            val msgs = withTimeout(5_000) { got.await() }
            assertEquals(ServerMsg.Disconnected, msgs[1])
        } finally {
            c.stop()
        }
    }

    @Test fun aConnectThatNeverOpenedEmitsNothing() = runBlocking {
        val net = FakeSocketFactory()
        val c = SpikeClient(http = net)
        val got = async(start = CoroutineStart.UNDISPATCHED) { c.events.take(2).toList() }
        c.start()
        try {
            // Mod not running yet: the first dial is refused before it opens. No welcome was live, so no Disconnected.
            val refused = net.next()
            refused.listener.onFailure(refused, java.net.ConnectException("Connection refused"), null)
            // The retry (after the 0.5 s backoff) opens, says welcome, then the game closes.
            val s = net.next()
            s.open()
            s.listener.onMessage(s, welcome)
            s.listener.onClosed(s, 1000, "game closed")
            val msgs = withTimeout(5_000) { got.await() }
            assertTrue(msgs[0].toString(), msgs[0] is ServerMsg.Welcome)
            assertEquals(ServerMsg.Disconnected, msgs[1])
        } finally {
            c.stop()
        }
    }
}
