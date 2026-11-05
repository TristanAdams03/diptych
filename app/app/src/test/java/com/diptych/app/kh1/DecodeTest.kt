package com.diptych.app.kh1

import com.diptych.app.kh1.TestData.data
import com.diptych.app.net.RegionHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeTest {
    @Test fun livePartyDecodes() {
        val p = decodeParty(data.party, TestData.partyRegions())
        assertEquals(
            listOf(
                PartyMember("Sora", 57, 57, 5, 5),
                PartyMember("Donald", 42, 42, 8, 8),
                PartyMember("Goofy", 63, 63, 4, 4),
            ),
            p,
        )
    }

    @Test fun emptySlotOmitsMemberAndUnknownIdIsGuest() {
        assertEquals(listOf("Sora", "Donald"), decodeParty(data.party, TestData.partyRegions("01ff")).map { it.name })
        assertEquals(listOf("Sora", "Guest", "Goofy"), decodeParty(data.party, TestData.partyRegions("5a02")).map { it.name })
        assertEquals(listOf("Sora", "Peter Pan"), decodeParty(data.party, TestData.partyRegions("08ff")).map { it.name })
    }

    @Test fun nullHexGivesNullFields() {
        val r = TestData.partyRegions().map { if (it.offset == TestData.PARTY_BASE + 256) it.copy(hex = null) else it }
        val p = decodeParty(data.party, r)
        assertEquals(PartyMember("Donald", null, null, null, null), p[1])
        assertEquals(57, p[0].hp)
    }

    @Test fun noRegionsGivesUnknownSoraOnly() {
        assertEquals(listOf(PartyMember("Sora", null, null, null, null)), decodeParty(data.party, emptyList()))
    }

    private val bitChest = Chest("c", 0, 100, bit = 3)
    private val atLeastChest = Chest("e", 0, 101, atLeast = 2)

    @Test fun chestBitAndAtLeast() {
        val r = listOf(RegionHex(100, "0801"))
        assertEquals(true, chestOpened(bitChest, r))
        assertEquals(false, chestOpened(atLeastChest, r))
        assertEquals(true, chestOpened(atLeastChest, listOf(RegionHex(100, "0802"))))
        assertEquals(false, chestOpened(bitChest, listOf(RegionHex(100, "f7"))))
    }

    @Test fun uncoveredOrFailedRegionIsNull() {
        assertNull(chestOpened(bitChest, listOf(RegionHex(200, "ff"))))
        assertNull(chestOpened(bitChest, listOf(RegionHex(100, null))))
        assertNull(chestOpened(atLeastChest, listOf(RegionHex(100, "08"))))
    }
}
