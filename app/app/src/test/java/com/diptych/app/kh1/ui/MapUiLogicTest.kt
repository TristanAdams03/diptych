package com.diptych.app.kh1.ui

import com.diptych.app.kh1.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Map viewport on the Thor bottom screen: 1240 px / 2.25 = 551.1 dp, minus 24 padding, 10 gap, 196 side, 4 rim.
    private val mapWidthDp = 551.1f - 24f - 10f - 196f - 4f

    @Test fun hollowBastionFitsTheMapWidth() {
        val cols = TestData.data.world(15)!!.rooms.maxOf { it.col } + 1
        assertEquals(4, cols)
        val m = gridMetrics(cols, mapWidthDp)
        assertTrue("content ${m.contentWidth(cols)} > viewport $mapWidthDp", m.contentWidth(cols) <= mapWidthDp)
        assertEquals(0f, clampPan(-100f, m.contentWidth(cols), mapWidthDp)) // no horizontal pan
        assertTrue(m.labelSp >= 11f)
    }

    @Test fun endOfTheWorldKeepsStandardCellsAndPans() {
        val cols = TestData.data.world(16)!!.rooms.maxOf { it.col } + 1
        assertEquals(7, cols)
        val m = gridMetrics(cols, mapWidthDp)
        assertEquals(124f, m.boxW)
        assertEquals(12f, m.labelSp)
        assertTrue(m.contentWidth(cols) > mapWidthDp)
    }

    @Test fun lastRowFullyVisibleAtMaxScroll() {
        val rows = TestData.data.world(16)!!.rooms.maxOf { it.row } + 1
        val m = gridMetrics(7, mapWidthDp)
        val viewportH = 280f // the grid slot between the title and the key bar
        val pan = clampPan(-1e6f, m.contentHeight(rows), viewportH)
        val lastBottom = m.boxY(rows - 1) + m.boxH + pan
        assertTrue("last row bottom $lastBottom > $viewportH", lastBottom <= viewportH)
        assertTrue(m.boxY(rows - 1) + pan >= 0f)
    }
}
