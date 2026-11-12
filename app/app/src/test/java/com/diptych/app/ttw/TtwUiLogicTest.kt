package com.diptych.app.ttw

import androidx.compose.ui.graphics.Color
import com.diptych.app.Fixtures
import com.diptych.app.LoopStatus
import com.diptych.app.SpikeUi
import com.diptych.app.net.ServerMsg
import com.diptych.app.service.ConnectionSnapshot
import com.diptych.app.display.DisplayInfo
import com.diptych.app.kh1.ui.ConnectionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtwUiLogicTest {
    private val state = (parseTtw(Fixtures.load("ttw/state_spike.json")) as TtwMsg.State).data
    private val welcome = parseTtw(Fixtures.load("ttw/welcome.json")) as TtwMsg.Welcome
    private val live = TtwUi(connected = true, welcome = welcome, state = state, loop = LoopStatus.Alive)

    @Test fun wastelandComesFromTheTtwGlobals() {
        assertEquals(Wasteland.Capital, wasteland(state))
        assertEquals(Wasteland.Mojave, wasteland(state.copy(bInDCWasteland = 0, bInNVWasteland = 1)))
        assertEquals(Wasteland.Unknown, wasteland(state.copy(bInDCWasteland = null, bInNVWasteland = null)))
        assertEquals(Wasteland.Unknown, wasteland(state.copy(bInDCWasteland = 0, bInNVWasteland = 0)))
        assertEquals(Wasteland.Unknown, wasteland(null))
        assertEquals("CAPITAL WASTELAND", Wasteland.Capital.label)
        assertEquals("MOJAVE WASTELAND", Wasteland.Mojave.label)
    }

    @Test fun hpFractionIsClampedAndNullWithoutBothValues() {
        assertEquals(245f / 280f, hpFraction(state)!!, 1e-6f)
        assertEquals(1f, hpFraction(state.copy(hp = 400.0))!!, 0f)
        assertEquals(0f, hpFraction(state.copy(hp = -5.0))!!, 0f)
        assertNull(hpFraction(state.copy(hpMax = 0.0)))
        assertNull(hpFraction(state.copy(hp = null)))
        assertEquals("245 / 280", hpText(state))
        assertEquals("- / -", hpText(state.copy(hp = null, hpMax = null)))
    }

    @Test fun theThemeIsTheGamesPipBoyColor() {
        assertEquals(Color(255, 182, 66), pipColor(state))
        assertEquals(PIP_GREEN, pipColor(state.copy(colors = null)))
        assertEquals(PIP_GREEN, pipColor(null))
        // A colour too dark to read on black falls back to the default.
        assertEquals(PIP_GREEN, pipColor(state.copy(colors = state.colors!!.copy(pipboy = listOf(4, 4, 4)))))
        assertEquals(PIP_GREEN, pipColor(state.copy(colors = state.colors!!.copy(pipboy = listOf(255)))))
    }

    @Test fun theBannerExplainsWhyThereIsNoDashboard() {
        assertNull(ttwBanner(ConnectionLabel.Connected, live))
        assertEquals("Waiting for the wasteland…", ttwBanner(ConnectionLabel.Waiting, live))
        assertEquals("Waiting for the wasteland…", ttwBanner(ConnectionLabel.Connected, live.copy(state = null)))
        assertEquals("Tale of Two Wastelands isn't loaded in this New Vegas", ttwBanner(ConnectionLabel.Unsupported, live))
        assertEquals("Load a save to begin", ttwBanner(ConnectionLabel.Connected, live.copy(state = state.copy(saveLoaded = false))))
    }

    @Test fun aStalledLoopIsCalledOut() {
        assertNull(stallNote(live))
        assertEquals("GAME LOOP PAUSED (loading or menu)", stallNote(live.copy(loop = LoopStatus.Stalled)))
        assertEquals("LINK LOST", stallNote(live.copy(loop = LoopStatus.SocketDead)))
    }

    @Test fun locationLinesNameTheCellAndWorldspace() {
        assertEquals("Megaton" to "Wasteland", locationLines(state))
        assertEquals("Megaton" to "Interior", locationLines(state.copy(interior = true, worldspace = null)))
        assertEquals("-" to "-", locationLines(state.copy(cell = null, worldspace = null, interior = null)))
    }

    @Test fun reportListsTheTtwFields() {
        val r = buildTtwReport(live.copy(lastResult = "c1 ok"), listOf("a line"), "AYN Thor", listOf(DisplayInfo(0, 1920, 1080, true, false)))
        assertTrue(r.startsWith("Diptych TTW spike report\n"))
        for (line in listOf("plugin=0.0.1-spike", "game=FalloutNV 1.4.0.525", "xnvse=6.4.9", "ttw=true", "hp=245.0/280.0",
            "bInDCWasteland=1", "cell=Megaton", "lastResult=c1 ok", "--- log (1) ---", "a line")) {
            assertTrue(line, r.lines().contains(line))
        }
        assertTrue(r.contains("pipboy=#FFB642"))
    }

    @Test fun diagnosticsFollowTheGameThatIsTalking() {
        val kh1 = ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true)
        assertEquals("kh1", diagnosticsGame(ConnectionSnapshot()))
        assertEquals("kh1", diagnosticsGame(ConnectionSnapshot(welcome = kh1, ttw = live)))
        assertEquals("ttw", diagnosticsGame(ConnectionSnapshot(welcome = welcome.toLink())))
        // New Vegas without TTW: no takeover, but its diagnostics are the ones worth reading.
        assertEquals("ttw", diagnosticsGame(ConnectionSnapshot(welcome = welcome.toLink().copy(supported = false))))
        assertEquals("ttw", diagnosticsGame(ConnectionSnapshot(ttw = live)))
        assertEquals("kh1", diagnosticsGame(ConnectionSnapshot(ui = SpikeUi(connected = true), ttw = live)))
    }
}
