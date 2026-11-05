package com.diptych.app

import com.diptych.app.net.SpikeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Results are fed directly (no socket); the error string is shown verbatim. */
class SpikeClientResultTest {
    @Test fun failedResultShowsErrorVerbatim() {
        val c = SpikeClient()
        c.handle(Fixtures.load("result_press_refused.json"))
        assertEquals("c2 FAIL not safe now", c.ui.value.lastResult)
        c.stop()
    }

    @Test fun badCommandWithEmptyId() {
        val c = SpikeClient()
        c.handle("""{"type":"result","id":"","ok":false,"error":"bad command","data":null}""")
        assertEquals("(no id) FAIL bad command", c.ui.value.lastResult)
        c.stop()
    }

    @Test fun logResultFillsModLog() {
        val c = SpikeClient()
        assertNull(c.ui.value.modLog)
        c.handle(Fixtures.load("result_log.json"))
        assertEquals(
            listOf("diptych_kh1 0.0.1-spike loaded", "build steam-1.0.0.2 supported", "server listening on 127.0.0.1:7970"),
            c.ui.value.modLog,
        )
        assertEquals("c3 ok (3 log lines)", c.ui.value.lastResult)
        c.stop()
    }

    @Test fun stateUpdatesUi() {
        val c = SpikeClient()
        c.handle(Fixtures.load("state_spike.json"))
        assertEquals(52, c.ui.value.state!!.soraHp)
        c.handle(Fixtures.load("state_unsupported.json"))
        assertNull(c.ui.value.state!!.soraHp)
        c.stop()
    }
}
