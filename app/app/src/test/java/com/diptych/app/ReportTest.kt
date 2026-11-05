package com.diptych.app

import com.diptych.app.display.DisplayInfo
import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.net.RegionHex
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.parseServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val displays = listOf(
        DisplayInfo(0, 1920, 1080, isOn = true, isPrivate = false),
        DisplayInfo(4, 1240, 1080, isOn = true, isPrivate = false),
    )
    private val keys = listOf(
        "device=", "displays=", "plugin=", "gameBuild=", "supported=", "loop=", "seqPerSec=",
        "world=", "worldAlt=", "room=", "hp=", "inMenu=", "inCutscene=", "inGummi=", "inputBytes=",
    )

    private fun assertKeys(r: String) {
        for (key in keys) assertTrue("missing $key in:\n$r", r.lines().any { it.startsWith(key) })
    }

    @Test fun reportHasRequiredLines() {
        val ui = SpikeUi(
            connected = true,
            welcome = parseServer(Fixtures.load("welcome_steam.json")) as ServerMsg.Welcome,
            state = (parseServer(Fixtures.load("state_spike.json")) as ServerMsg.State).data,
            loop = LoopStatus.Alive,
            seqRate = 5.0,
            modLog = listOf("server listening on 127.0.0.1:7970"),
        )
        val r = buildReport(ui, listOf("12:00:00 connected"), "AYN Thor Android 13", displays)
        assertKeys(r)
        for (want in listOf(
            "plugin=0.1.0", "gameBuild=steam-1.0.0.2", "supported=true", "loop=ALIVE", "seqPerSec=5.0",
            "world=3", "worldAlt=3", "room=2", "hp=52/60", "inMenu=false", "inCutscene=false", "inGummi=false",
            "inputBytes=00000000", "device=AYN Thor Android 13", "4:1240x1080", "12:00:00 connected",
            "server listening on 127.0.0.1:7970",
        )) assertTrue("missing '$want' in:\n$r", r.contains(want))
    }

    @Test fun reportWorksDisconnected() {
        val r = buildReport(SpikeUi(), emptyList(), "x", emptyList())
        assertKeys(r)
        assertTrue(r, r.contains("loop=SOCKET DEAD"))
        assertTrue(r, r.contains("hp=-/-"))
    }

    @Test fun reportIncludesWorldRoomAndRegions() {
        val st = (parseServer(Fixtures.load("state_spike.json")) as ServerMsg.State).data.copy(
            world = 15, room = 6,
            regions = listOf(RegionHex(48142992, "00ff10"), RegionHex(48142999, null)),
        )
        val r = buildReport(SpikeUi(connected = true, state = st), emptyList(), "x", emptyList())
        for (want in listOf("world=15", "room=6", "region 48142992 00ff10", "region 48142999 unreadable"))
            assertTrue("missing '$want' in:\n$r", r.lines().contains(want))
    }

    @Test fun connectionLabelFollowsWelcome() {
        val ok = parseServer(Fixtures.load("welcome_steam.json")) as ServerMsg.Welcome
        assertEquals(ConnectionLabel.Waiting, connectionLabel(SpikeUi()))
        assertEquals(ConnectionLabel.Waiting, connectionLabel(SpikeUi(connected = true)))
        assertEquals(ConnectionLabel.Waiting, connectionLabel(SpikeUi(connected = false, welcome = ok)))
        assertEquals(ConnectionLabel.Connected, connectionLabel(SpikeUi(connected = true, welcome = ok)))
        assertEquals(ConnectionLabel.Unsupported, connectionLabel(SpikeUi(connected = true, welcome = ok.copy(supported = false))))
    }
}
