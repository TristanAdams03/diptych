package com.diptych.app

import com.diptych.app.net.SpikeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mod answers `hello` with a welcome, and pushes an unsolicited `welcome` again when it
 * re-classifies its build. The client must replace the shown welcome with the latest one.
 */
class SpikeClientWelcomeTest {
    @Test fun unsolicitedWelcomeReplacesEarlierOne() {
        val c = SpikeClient()  // never started: no socket, frames fed directly
        c.handle(Fixtures.load("welcome_unsupported.json"))
        assertNotNull(c.ui.value.welcome)
        assertFalse(c.ui.value.welcome!!.supported)
        assertEquals("egs-1.0.0.10", c.ui.value.welcome!!.gameBuild)

        c.handle(Fixtures.load("welcome_steam.json"))
        assertTrue(c.ui.value.welcome!!.supported)
        assertEquals("steam-1.0.0.2", c.ui.value.welcome!!.gameBuild)
        assertTrue(c.log.value.last(), c.log.value.last().endsWith("build=steam-1.0.0.2 supported=true"))
        c.stop()
    }
}
