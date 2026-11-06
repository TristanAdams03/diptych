package com.diptych.app.kh1

import com.diptych.app.kh1.TestData.data
import com.diptych.app.net.RegionHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(listOf("Sora", "Tarzan", "Ariel"), decodeParty(data.party, TestData.partyRegions("0306")).map { it.name })
        assertEquals(listOf("Sora", "Aladdin", "Jack"), decodeParty(data.party, TestData.partyRegions("0507")).map { it.name })
        assertEquals(listOf("Sora", "Pooh", "Beast"), decodeParty(data.party, TestData.partyRegions("0409")).map { it.name })
    }

    @Test fun storyStepDecodesFirstStepAboveProgressByte() {
        val hbStory = data.world(15)!!.story!!
        val step0 = decodeStoryStep(hbStory, listOf(RegionHex(TestData.HB_STORY, "00")))!!
        assertEquals(1, step0.nextRoom)
        val step60 = decodeStoryStep(hbStory, listOf(RegionHex(TestData.HB_STORY, "3c")))!!
        assertEquals(11, step60.nextRoom) // 0x3C < 0x46: Ansem/Riku cutscene, Castle Chapel (11)
        val stepDone = decodeStoryStep(hbStory, listOf(RegionHex(TestData.HB_STORY, "ff")))!!
        assertNull(stepDone.nextRoom)
        assertNull(decodeStoryStep(hbStory, emptyList()))
    }

    @Test fun soraStatusDecodesStatsSpellsAndEquippedItems() {
        val status = decodeSoraStatus(data.party, TestData.soraStatusRegions())
        assertEquals(42, status.level)
        assertEquals(38, status.ap)
        assertEquals(34, status.strength)
        assertEquals(32, status.defense)
        assertEquals("Divine Rose", status.weapon)
        assertEquals(
            listOf("Firaga", "Blizzara", "Thundara", "Cura", "Gravira", "Stopra"),
            status.spells.map { it.name },
        )
        // Equipped slots only: Ether, Elixir and Tent are stocked but not equipped, so they are not listed.
        assertEquals(listOf("Potion" to 6, "Hi-Potion" to 3), status.items.map { it.name to it.count })
        assertTrue(status.items.all { it.equipped })
        assertTrue(status.known)
        assertEquals(false, decodeSoraStatus(data.party, TestData.partyRegions()).known) // stats not watched
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

    @Test fun malformedHexDecodesToNullWithoutThrowing() {
        for (bad in listOf("0", "zz", "+f", "-1", "080", "")) {
            assertNull(bad, chestOpened(bitChest, listOf(RegionHex(100, bad))))
        }
        // short region: a battle block truncated below the member span gives null fields, not an exception
        val short = TestData.partyRegions().map { if (it.offset == TestData.PARTY_BASE) it.copy(hex = "3900") else it }
        assertEquals(PartyMember("Sora", null, null, null, null), decodeParty(data.party, short)[0])
        // chest straddling a region end
        assertNull(chestOpened(Chest("s", 0, 101, bit = 0), listOf(RegionHex(100, "01"))))
    }

    @Test fun highBitU32NeverGoesNegative() {
        val r = TestData.partyRegions().map { if (it.offset == TestData.PARTY_BASE) it.copy(hex = "ffffffff" + "39000000".repeat(3)) else it }
        assertEquals(Int.MAX_VALUE, decodeParty(data.party, r)[0].hp)
    }
}
