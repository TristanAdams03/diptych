package com.diptych.app

import com.diptych.app.kh1.CastOutcome
import com.diptych.app.kh1.Kh1Session
import com.diptych.app.kh1.MapView
import com.diptych.app.kh1.loadKh1Data
import com.diptych.app.net.SequenceData
import com.diptych.app.net.SpikeClient
import com.diptych.app.net.sequenceData
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual smoke test of the KH1 session against the mock; skipped unless run with -Pmock=1:
 *   python tools/mockserver/kh1_mock.py --world 15 --room 6
 *   ./gradlew :app:testDebugUnitTest -Pmock=1 --tests '*Kh1SessionSmokeTest'   (container: --network host)
 * Checks the full chain: welcome -> kh1.watch -> regions in state frames -> a decoded MapView -> M3 castShortcut.
 */
class Kh1SessionSmokeTest {
    @Test fun watchAndRegionsAgainstMock() {
        assumeTrue("set -Pmock=1 to run against the mock server", System.getProperty("mock") == "1")
        val data = loadKh1Data { File("src/main/assets/kh1/$it").inputStream() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = System.getenv("MOCK_URL")?.let { SpikeClient(it) } ?: SpikeClient() // MOCK_URL when :7970 is taken
        val session = Kh1Session(c, data, scope, c::log) // before start(): the events flow has no replay
        c.start()
        try {
            val t0 = System.currentTimeMillis()
            var v: MapView? = null
            while (System.currentTimeMillis() - t0 < 15_000) {
                v = session.view.value
                if (v != null && v.party.isNotEmpty() && v.sora?.spells?.isNotEmpty() == true) break
                Thread.sleep(50)
            }
            println("SMOKE view=$v\nSMOKE state regions=${c.ui.value.state?.regions}")
            assertTrue("no view with party; log:\n" + c.log.value.joinToString("\n"), v != null && v.party.isNotEmpty())
            assertEquals("Hollow Bastion", v!!.worldName)
            assertEquals(55, v.party.first().hp) // the mock's Sora HP, read through kh1.watch regions
            assertTrue(v.rooms.any { it.here })
            assertEquals(ConnectionLabelProbe.label(c.ui.value), "Connected")

            runBlocking {
                val fire = session.castShortcut(spellBit = 0, targetIdx = null)
                assertTrue("fire cast=$fire", fire is CastOutcome.Sent && fire.result.ok)
                assertEquals(SequenceData(steps = 1, skipped = 0), (fire as CastOutcome.Sent).result.sequenceData())

                val cure = session.castShortcut(spellBit = 3, targetIdx = 1)
                assertTrue("cure cast=$cure", cure is CastOutcome.Sent && cure.result.ok)
                assertEquals(SequenceData(steps = 3, skipped = 0), (cure as CastOutcome.Sent).result.sequenceData())
            }
            c.fetchModLog()
            val logStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - logStart < 2_000) {
                if (c.ui.value.modLog?.any { it.contains("target picker confirmed") } == true) break
                Thread.sleep(50)
            }
            assertTrue("modLog=${c.ui.value.modLog}", c.ui.value.modLog?.any { it.contains("target picker confirmed") } == true)
            println("SMOKE report:\n" + buildReport(c.ui.value, emptyList(), "mock", emptyList()))
        } finally {
            c.stop()
            scope.cancel()
        }
    }
}

private object ConnectionLabelProbe {
    fun label(ui: SpikeUi) = connectionLabel(ui).name
}
