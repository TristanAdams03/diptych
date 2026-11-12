package com.diptych.app.ttw

import com.diptych.app.LoopStatus
import com.diptych.app.net.ServerMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manual smoke test against tools/mockserver/ttw_mock.py on :7960. Skipped unless run with -Pmock=ttw:
 *   tools/mockserver/.venv/bin/python tools/mockserver/ttw_mock.py            (host)
 *   ./gradlew testDebugUnitTest -Pmock=ttw --tests '*TtwClientSmokeTest'       (container: --network host)
 */
class TtwClientSmokeTest {
    @Test fun welcomeStateAndCommandAgainstMock() {
        assumeTrue("set -Pmock=ttw to run against the TTW mock", System.getProperty("mock") == "ttw")
        val c = TtwClient()
        val events = CopyOnWriteArrayList<ServerMsg>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { events += it } }
        c.start()
        try {
            val t0 = System.currentTimeMillis()
            while (c.ui.value.loop != LoopStatus.Alive && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(50)
            assertEquals(c.log.value.joinToString("\n"), LoopStatus.Alive, c.ui.value.loop)
            val w = events.first() as ServerMsg.Welcome
            assertEquals("ttw", w.gameId)
            assertTrue(c.ui.value.state!!.saveLoaded)
            val id = c.equipFirstWeapon()
            Thread.sleep(500)
            assertTrue(c.ui.value.lastResult.toString(), c.ui.value.lastResult!!.startsWith("$id ok"))
            println("SMOKE ttw welcome=$w state=${c.ui.value.state} result=${c.ui.value.lastResult}")
        } finally {
            c.stop()
            scope.cancel()
        }
    }
}
