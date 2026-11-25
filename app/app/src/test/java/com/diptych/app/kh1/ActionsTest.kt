package com.diptych.app.kh1

import com.diptych.app.kh1.TestData.data
import com.diptych.app.net.MemCondition
import com.diptych.app.net.PressSequenceArgs
import com.diptych.app.net.RegionHex
import com.diptych.app.net.SeqStep
import com.diptych.app.net.SeqUntil
import com.diptych.app.net.SpikeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRE = 0
private const val THUNDER = 2
private const val CURE = 3
private const val AERO = 6
private const val PICKER = 4 // party.json targetPickerType (a placeholder until the live PC check)

class ActionsTest {
    private val members = TestData.partyRegions().filter { it.offset != TestData.SLOTS }

    /** A safe Hollow Bastion frame: Sora 57/57 HP, 5 MP; Fire, Blizzard, Thunder, Cure, Gravity, Stop learned. */
    private fun frame(
        shortcuts: String? = "0003ff",
        swap: String? = "00",
        menuType: Int? = 0,
        sora: String = TestData.SORA,
        inMenu: Boolean = false,
    ): SpikeData {
        val regions = members.map { if (it.offset == TestData.PARTY_BASE) RegionHex(it.offset, sora) else it } +
            TestData.soraStatusRegions() + TestData.actionRegions(shortcuts, swap, menuType)
        return TestData.state(15, 4, regions).copy(inMenu = inMenu, inCutscene = false, inGummi = false)
    }

    private fun view(f: SpikeData = frame()) = buildMapView(data, f)

    private fun plan(bit: Int, target: Int? = null, v: MapView = view(), config: PartyConfig = data.party) =
        planShortcutCast(config, bit, target, v.party.size, v)

    private fun ready(p: CastPlan): PressSequenceArgs {
        assertTrue(p.toString(), p is CastPlan.Ready)
        return (p as CastPlan.Ready).args
    }

    private fun refused(p: CastPlan): String {
        assertTrue(p.toString(), p is CastPlan.Refused)
        return (p as CastPlan.Refused).reason
    }

    private val requireSwapA = MemCondition(TestData.SWAP, 0)
    private val menuTop = MemCondition(TestData.COMMAND_MENU, 0)

    @Test fun fireFromShortcutOneIsOnePress() {
        val args = ready(plan(FIRE))
        assertEquals(listOf(requireSwapA, MemCondition(TestData.SHORTCUTS, FIRE), menuTop), args.require)
        assertEquals(listOf(SeqStep(1, 0x14, 100, 50)), args.steps) // L1 + Y
    }

    @Test fun cureOnDonaldWaitsForThePickerMovesDownOnceAndConfirms() {
        val p = plan(CURE, target = 1)
        assertEquals(1, (p as CastPlan.Ready).slot)
        val args = p.args
        assertEquals(listOf(requireSwapA, MemCondition(TestData.SHORTCUTS + 1, CURE), menuTop), args.require)
        val open = MemCondition(TestData.COMMAND_MENU, PICKER)
        assertEquals(
            listOf(
                SeqStep(1, 0x24, 100, 50, until = SeqUntil(TestData.COMMAND_MENU, PICKER, timeoutMs = 500)), // L1 + B
                SeqStep(0, 0x40, 66, 50, onlyIf = open), // D-pad down
                SeqStep(1, 0x40, 66, 50, onlyIf = open), // A confirms (swap 0)
            ),
            args.steps,
        )
    }

    @Test fun cureOnSoraNeedsNoMove() {
        val steps = ready(plan(CURE, target = 0)).steps
        assertEquals(listOf(0x24, 0x40), steps.map { it.mask })
    }

    @Test fun swappedButtonsConfirmWithB() {
        val args = ready(plan(CURE, target = 2, v = view(frame(swap = "01"))))
        assertEquals(MemCondition(TestData.SWAP, 1), args.require[0])
        assertEquals(0x20, args.steps.last().mask)
        assertEquals(1, args.steps.last().byteIndex)
    }

    @Test fun everyPlanFitsTheModsSequenceLimits() {
        for (target in 0..2) {
            val args = ready(plan(CURE, target = target))
            assertTrue(args.steps.size in 1..16)
            assertTrue(args.require.size <= 16)
            assertTrue(args.steps.sumOf { it.holdMs + it.gapMs + (it.until?.timeoutMs ?: 0) } <= 5000)
            for (s in args.steps) {
                assertTrue(s.byteIndex in 0..1)
                assertTrue(s.holdMs in 16..1000 && s.gapMs in 16..1000)
                assertFalse("start is refused", s.byteIndex == 0 && s.mask and 0x08 != 0)
            }
            assertFalse(args.toJson().toString().contains("null"))
        }
    }

    @Test fun withoutAKnownPickerByteItWaitsBlind() {
        val blind = data.party.copy(commandMenu = data.party.commandMenu!!.copy(targetPickerType = null))
        val steps = ready(plan(CURE, target = 1, config = blind)).steps
        assertEquals(300, steps[0].gapMs)
        assertTrue(steps.all { it.until == null && it.onlyIf == null })
    }

    @Test fun refusals() {
        assertEquals(ACT_NOT_ON_SHORTCUT, refused(plan(THUNDER)))
        assertEquals(ACT_PICK_TARGET, refused(plan(CURE)))
        assertEquals(ACT_NO_TARGET, refused(plan(CURE, target = 3)))
        assertEquals(ACT_NOT_LEARNED, refused(plan(AERO, v = view(frame(shortcuts = "06ffff")))))
        assertEquals(BUSY_MENU, refused(plan(FIRE, v = view(frame(inMenu = true)))))
        assertEquals(ACT_MENU_BUSY, refused(plan(FIRE, v = view(frame(menuType = 2)))))
        assertEquals(ACT_MENU_UNKNOWN, refused(plan(FIRE, v = view(frame(menuType = null)))))
        assertEquals(ACT_SHORTCUTS_UNKNOWN, refused(plan(FIRE, v = view(frame(shortcuts = null)))))
        assertEquals(ACT_SWAP_UNKNOWN, refused(plan(FIRE, v = view(frame(swap = null)))))
        assertEquals(ACT_NOT_CONFIGURED, refused(plan(FIRE, config = data.party.copy(shortcuts = null))))
        // Sora at 0 HP, and Sora with 0 MP.
        assertEquals(ACT_SORA_DOWN, refused(plan(FIRE, v = view(frame(sora = "00000000390000000500000005000000")))))
        assertEquals(actNeedsMp(1), refused(plan(FIRE, v = view(frame(sora = "39000000390000000000000005000000")))))
    }

    @Test fun theViewCarriesEachSpellsSlotAndReason() {
        val spells = view().sora.spells.associateBy { it.bit }
        assertEquals(0, spells.getValue(FIRE).shortcutSlot)
        assertNull(spells.getValue(FIRE).actionReason)
        assertEquals(1, spells.getValue(CURE).shortcutSlot)
        assertNull(spells.getValue(CURE).actionReason) // a target spell isn't greyed out for want of a target
        assertNull(spells.getValue(THUNDER).shortcutSlot)
        assertEquals(ACT_NOT_ON_SHORTCUT, spells.getValue(THUNDER).actionReason)
        // Busy: every spell says why.
        assertTrue(view(frame(inMenu = true)).sora.spells.all { it.actionReason == BUSY_MENU })
    }

    @Test fun aViewWithoutActionBytesStillBuilds() {
        // Old frames (no M3 regions watched) keep working: the spells are listed, each with its reason.
        val v = buildMapView(data, TestData.state(15, 4, members + TestData.soraStatusRegions())
            .copy(inMenu = false, inCutscene = false, inGummi = false))
        assertEquals(ActionState(), v.actions)
        assertTrue(v.sora.spells.isNotEmpty())
        assertTrue(v.sora.spells.all { it.actionReason == ACT_SHORTCUTS_UNKNOWN })
    }
}
