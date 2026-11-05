package com.diptych.app

import com.diptych.app.net.SpikeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual smoke test against tools/mockserver/kh1_mock.py. Skipped unless run with -Pmock=1:
 *   tools/mockserver/.venv/bin/python tools/mockserver/kh1_mock.py --stall-after 10   (host)
 *   ./gradlew testDebugUnitTest -Pmock=1 --tests '*SpikeClientSmokeTest'          (container: --network host)
 * The mock's stall clock starts at its launch; start it once this test prints "SMOKE start"
 * (the client keeps reconnecting until it is up).
 */
class SpikeClientSmokeTest {
    private fun waitFor(c: SpikeClient, want: LoopStatus, timeoutMs: Long): Long {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (c.ui.value.loop == want) return System.currentTimeMillis() - t0
            Thread.sleep(50)
        }
        throw AssertionError("never reached $want in $timeoutMs ms; ui=${c.ui.value}\n" + c.log.value.joinToString("\n"))
    }

    @Test fun aliveThenStalledAgainstMock() {
        assumeTrue("set -Pmock=1 to run against the mock server", System.getProperty("mock") == "1")
        println("SMOKE start")
        System.out.flush()
        val c = SpikeClient()
        c.start()
        try {
            val tAlive = waitFor(c, LoopStatus.Alive, 20_000)
            println("SMOKE alive after ${tAlive}ms")
            val w = c.ui.value.welcome
            assertNotNull(w)
            println("SMOKE welcome=$w")
            assertEquals("kh1", w!!.gameId)
            Thread.sleep(1500)
            val rate = c.ui.value.seqRate
            println("SMOKE seqPerSec=$rate state=${c.ui.value.state}")
            assertTrue("seqRate=$rate", rate != null && rate > 3) // subscribed at 5 Hz

            val id = c.fetchModLog()
            Thread.sleep(500)
            println("SMOKE $id -> ${c.ui.value.lastResult} modLog=${c.ui.value.modLog}")
            assertTrue(c.ui.value.lastResult.toString(), c.ui.value.lastResult!!.startsWith("$id ok"))
            assertTrue(c.ui.value.modLog.toString(), c.ui.value.modLog!!.any { it.contains("127.0.0.1:7970") })

            val tStalled = waitFor(c, LoopStatus.Stalled, 15_000)
            println("SMOKE stalled after a further ${tStalled}ms")
            // pongs keep flowing from the network thread: must stay STALLED, not SOCKET DEAD
            Thread.sleep(6_000)
            println("SMOKE 6s later loop=${c.ui.value.loop} rtt=${c.ui.value.rttMs}")
            assertEquals(LoopStatus.Stalled, c.ui.value.loop)
            println("SMOKE log:\n" + c.log.value.joinToString("\n"))
        } finally {
            c.stop()
        }
    }
}
