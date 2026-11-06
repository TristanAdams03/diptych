package com.diptych.app.kh1.ui

import com.diptych.app.kh1.TestData
import androidx.compose.ui.geometry.Offset
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

    @Test fun centeredPanRecentersOnceTheRoomIsKnown() {
        // Unknown room on the first frame: no pan. Once it appears (same world) the pan centers on it, clamped.
        assertEquals(Offset.Zero, centeredPan(null, 800f, 600f, 400f, 300f))
        assertEquals(Offset(-300f, -250f), centeredPan(Offset(500f, 400f), 800f, 600f, 400f, 300f))
        assertEquals(Offset(0f, -300f), centeredPan(Offset(50f, 590f), 800f, 600f, 400f, 300f)) // clamped at edges
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

    // Grid slot height on the Thor with the two-line header (Next hint): ~262 dp between title and key bar.
    private val mapHeightDp = 262f

    @Test fun hollowBastionFitsBelowTheNextLine() {
        val cols = 4
        val rows = TestData.data.world(15)!!.rooms.maxOf { it.row } + 1
        assertEquals(5, rows)
        val m = gridMetrics(cols, mapWidthDp, rows, mapHeightDp)
        assertTrue("content ${m.contentHeight(rows)} > $mapHeightDp", m.contentHeight(rows) <= mapHeightDp)
        assertTrue(m.boxH >= FIT_MIN_BOX_H)
        assertEquals(0f, clampPan(-100f, m.contentHeight(rows), mapHeightDp)) // fits: no vertical pan
    }

    @Test fun everyWorldClearsTitleAndKeyBarAtBothPanEnds() {
        for (w in TestData.data.worlds) {
            val cols = w.rooms.maxOf { it.col } + 1
            val rows = w.rooms.maxOf { it.row } + 1
            for (vh in listOf(mapHeightDp, 200f, 150f)) {
                val m = gridMetrics(cols, mapWidthDp, rows, vh)
                val ch = m.contentHeight(rows)
                // Top end of the pan: the first row starts >= 6 dp below the title.
                assertTrue("${w.name} @$vh top", m.boxY(0) + clampPan(1e6f, ch, vh) >= GRID_EDGE_GAP)
                // Bottom end: the last row ends >= 6 dp above the key bar.
                val bottom = m.boxY(rows - 1) + m.boxH + clampPan(-1e6f, ch, vh)
                assertTrue("${w.name} @$vh bottom $bottom", vh - bottom >= GRID_EDGE_GAP - 0.01f)
            }
        }
    }

    @Test fun statusLineShowsTheReasonOrReady() {
        val base = com.diptych.app.kh1.buildMapView(TestData.data, TestData.state(15, 5, TestData.partyRegions()))
        assertEquals(com.diptych.app.kh1.BUSY_UNKNOWN, statusLine(base))
        assertEquals("Game ready", statusLine(base.copy(canAct = true, busyReason = null)))
        assertEquals("Tap-to-cast is coming next", QUICK_COMING_NEXT)
    }
}
