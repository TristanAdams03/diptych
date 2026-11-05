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
        val v = buildMapView(data, state(3, 2, TestData.partyRegions()))
        assertEquals("World 3", v.worldName)
        assertTrue(v.rooms.isEmpty())
        assertEquals(57, v.party[0].hp)
    }

    @Test fun unknownRoomIsNamedByNumber() {
        assertEquals("Room 99", buildMapView(data, state(15, 99, null)).roomName)
    }
}
