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

    private fun safe() = state(15, 4, TestData.partyRegions()).copy(inMenu = false, inCutscene = false, inGummi = false)

    @Test fun canActOnlyWhenEveryFlagReadsFalseAndMpIsKnown() {
        val v = buildMapView(data, safe())
        assertEquals(true, v.canAct)
        assertNull(v.busyReason)
    }

    @Test fun busyFlagsDisableActionsWithNeutralReasons() {
        val cases = mapOf(
            safe().copy(inCutscene = true) to BUSY_GAME,
            safe().copy(inMenu = true) to BUSY_MENU,
            safe().copy(inGummi = true) to BUSY_GUMMI,
        )
        for ((s, reason) in cases) {
            val v = buildMapView(data, s)
            assertEquals(false, v.canAct)
            assertEquals(reason, v.busyReason)
        }
        assertEquals("Game busy (cutscene, dialogue or loading)", BUSY_GAME)
        assertEquals("Menu open", BUSY_MENU)
        assertEquals("Gummi Ship", BUSY_GUMMI)
    }

    @Test fun unknownFlagsOrMpAreNotSafe() {
        for (s in listOf(safe().copy(inCutscene = null), safe().copy(inMenu = null), safe().copy(inGummi = null))) {
            val v = buildMapView(data, s)
            assertEquals(false, v.canAct)
            assertEquals(BUSY_UNKNOWN, v.busyReason)
        }
        val noMp = buildMapView(data, safe().copy(regions = emptyList()))
        assertEquals(false, noMp.canAct)
        assertEquals(BUSY_NO_MP, noMp.busyReason)
        assertEquals(false, buildMapView(data, state(15, 4, TestData.partyRegions())).canAct) // no flags at all
    }

    @Test fun noReasonEverSaysCutscenePlaying() {
        for (r in listOf(BUSY_GAME, BUSY_MENU, BUSY_GUMMI, BUSY_UNKNOWN, BUSY_NO_MP)) {
            assertTrue(r, !r.contains("Cutscene playing", ignoreCase = true))
        }
    }

    @Test fun unknownRoomIsNamedByNumber() {
        assertEquals("Room 99", buildMapView(data, state(15, 99, null)).roomName)
    }
}
