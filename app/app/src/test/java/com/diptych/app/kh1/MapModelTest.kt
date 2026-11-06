package com.diptych.app.kh1

import com.diptych.app.kh1.TestData.data
import com.diptych.app.kh1.TestData.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapModelTest {
    @Test fun hollowBastionCountsAndHere() {
        val v = buildMapView(data, state(15, 6, TestData.hbRegions() + TestData.partyRegions()))
        assertEquals("Hollow Bastion", v.worldName)
        assertEquals("Lift Stop", v.roomName)
        assertEquals(listOf(6), v.rooms.filter { it.here }.map { it.id })
        val r6 = v.rooms.first { it.id == 6 }
        assertEquals(3, r6.opened); assertEquals(5, r6.total)
        assertEquals(0, v.rooms.first { it.id == 0 }.opened)
        assertEquals(3, v.chestsOpened); assertEquals(39, v.chestsTotal)
        assertEquals(3, v.party.size)
        assertTrue(v.edges.isNotEmpty())
    }

    @Test fun failedRegionMakesRoomsUnknown() {
        val v = buildMapView(data, state(15, 6, TestData.partyRegions()))
        assertNull(v.rooms.first { it.id == 6 }.opened)
        assertNull(v.chestsOpened)
        assertEquals(39, v.chestsTotal)
    }

    @Test fun worldWithoutDataKeepsParty() {
        val v = buildMapView(data, state(99, 2, TestData.partyRegions()))
        assertEquals("World 99", v.worldName)
        assertTrue(v.rooms.isEmpty())
        assertEquals(57, v.party[0].hp)
    }

    @Test fun allFifteenPlayableWorldsHaveMaps() {
        val expectedIds = listOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 15, 16)
        assertEquals(expectedIds, data.worlds.map { it.id })
        for (w in data.worlds) {
            assertTrue("world ${w.name} has no rooms", w.rooms.isNotEmpty())
        }
    }

    @Test fun hollowBastionStoryHighlightsNextAndLockedRooms() {
        val v = buildMapView(data, state(15, 5, TestData.hbRegions(storyByte = 60) + TestData.partyRegions()))
        assertEquals("Library", v.roomName)
        // 0x3C: Riku I beaten (0x32), before the Ansem/Riku cutscene in the Castle Chapel (0x46).
        assertTrue(v.nextHint!!.contains("Castle Chapel"))
        assertEquals(listOf(11), v.rooms.filter { it.next }.map { it.id })
        assertTrue(14 in v.rooms.filter { it.locked }.map { it.id })
    }

    @Test fun hollowBastionHintsFollowTheSourcedThresholds() {
        fun hint(b: Int) = buildMapView(data, state(15, 5, TestData.hbRegions(storyByte = b))).nextHint!!
        assertTrue(hint(0x31).contains("Riku"))
        assertTrue(hint(0x50).contains("Maleficent") && !hint(0x50).contains("Dragon"))
        assertTrue(hint(0x5A).contains("Dragon Maleficent"))
        assertTrue(hint(0x6D).contains("Dragon Maleficent"))
        assertTrue(hint(0x6E).contains("Riku"))
        assertTrue(hint(0x82).contains("Behemoth"))
    }

    @Test fun busyMenuAndCutsceneDisableActions() {
        val menuState = state(15, 4, TestData.partyRegions()).copy(inMenu = true)
        val menuView = buildMapView(data, menuState)
        assertEquals(false, menuView.canAct)
        assertEquals("In game menu", menuView.busyReason)
    }

    @Test fun unknownRoomIsNamedByNumber() {
        assertEquals("Room 99", buildMapView(data, state(15, 99, null)).roomName)
    }
}
