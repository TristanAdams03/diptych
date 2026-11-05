package com.diptych.app.kh1.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapUiLogicTest {
    @Test fun chestTextCases() {
        assertEquals("2 / 4", chestText(2, 4))
        assertEquals("3 / 3 ✓", chestText(3, 3))
        assertEquals("? / 5", chestText(null, 5))
        assertEquals("no chests", chestText(0, 0))
    }

    @Test fun barFractionAndLowHp() {
        assertNull(barFraction(null, 40))
        assertNull(barFraction(10, null))
        assertNull(barFraction(10, 0))
        assertEquals(0.5f, barFraction(20, 40)!!, 0f)
        assertEquals(1f, barFraction(90, 40)!!, 0f)
        assertEquals(Kh1Colors.HpLow, hpColor(0.4f))
        assertEquals(Kh1Colors.Hp, hpColor(0.41f))
        assertEquals(Kh1Colors.Hp, hpColor(null))
    }

    @Test fun panClamp() {
        assertEquals(0f, clampPan(-50f, 300f, 400f)) // content fits: no pan
        assertEquals(0f, clampPan(30f, 800f, 400f)) // never past the left/top edge
        assertEquals(-400f, clampPan(-900f, 800f, 400f)) // never past the right/bottom edge
        assertEquals(-120f, clampPan(-120f, 800f, 400f))
    }
}
