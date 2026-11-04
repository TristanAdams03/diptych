package com.diptych.app

import com.diptych.app.display.DisplayInfo
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.parseServer
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
            "plugin=0.0.1-spike", "gameBuild=steam-1.0.0.2", "supported=true", "loop=ALIVE", "seqPerSec=5.0",
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
}
