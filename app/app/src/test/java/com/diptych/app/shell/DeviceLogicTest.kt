package com.diptych.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogicTest {
    @Test fun versionCompare() {
        assertTrue(versionAtLeast("1.3.0"))
        assertTrue(versionAtLeast("1.3"))
        assertTrue(versionAtLeast("v1.10.2"))
        assertTrue(versionAtLeast("2.0"))
        assertTrue(versionAtLeast("1.3.0-beta"))
        assertFalse(versionAtLeast("1.2.9"))
        assertFalse(versionAtLeast("0.9"))
        assertFalse(versionAtLeast(null))
        assertFalse(versionAtLeast("garbage"))
    }

    @Test fun checksSayHowToFix() {
        val missing = deviceChecks(ShellEnv(gameNativeVersion = null, batteryExempt = false))
        assertEquals(CheckState.Fix, missing[0].state)
        assertEquals("Settings \u2192 Apps \u2192 GameNative \u2192 Battery \u2192 Unrestricted", missing[1].fix)
        val old = deviceChecks(ShellEnv(gameNativeVersion = "1.2.0", batteryExempt = true))
        assertEquals(CheckState.Fix, old[0].state)
        assertEquals(CheckState.Ok, old[1].state)
        val good = deviceChecks(ShellEnv(gameNativeVersion = "1.3.0", batteryExempt = true))
        assertEquals(CheckState.Ok, good[0].state)
        assertEquals(CheckState.Check, good[2].state)
        assertTrue(good[2].fix!!.startsWith("Not needed on your Thor so far"))
    }

    @Test fun guideLinks() {
        assertEquals(KH1_GUIDE_URL, GUIDES[0].url)
        assertEquals(TTW_GUIDE_URL, GUIDES[1].url)
    }
}
