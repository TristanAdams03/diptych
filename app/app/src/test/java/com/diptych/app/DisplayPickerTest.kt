package com.diptych.app

import com.diptych.app.display.DisplayInfo
import com.diptych.app.display.RELOCATE_COOLDOWN_MS
import com.diptych.app.display.pickCompanionDisplay
import com.diptych.app.display.relocationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayPickerTest {
    private val main = DisplayInfo(0, 1920, 1080, isOn = true, isPrivate = false)
    private val bottom = DisplayInfo(4, 1240, 1080, isOn = true, isPrivate = false)
    private val thor = listOf(main, bottom)

    @Test fun thorPicksBottomScreen() = assertEquals(4, pickCompanionDisplay(thor))

    @Test fun privateVirtualDisplayIgnored() =
        assertEquals(4, pickCompanionDisplay(thor + DisplayInfo(7, 400, 400, isOn = true, isPrivate = true)))

    @Test fun singleDisplayIsNull() = assertNull(pickCompanionDisplay(listOf(main)))

    @Test fun bottomOffIsNull() = assertNull(pickCompanionDisplay(listOf(main, bottom.copy(isOn = false))))

    // Relocation: only to the companion display, never to display 0, once, with a cooldown.
    @Test fun relocatesFromGameDisplayToCompanion() =
        assertEquals(4, relocationTarget(current = 0, picked = 4, relocated = false, lastRelocateMs = 0, nowMs = 60_000))

    @Test fun noRelocationWhenAlreadyThere() =
        assertNull(relocationTarget(current = 4, picked = 4, relocated = false, lastRelocateMs = 0, nowMs = 60_000))

    @Test fun noRelocationWithoutCompanion() =
        assertNull(relocationTarget(current = 0, picked = null, relocated = false, lastRelocateMs = 0, nowMs = 60_000))

    @Test fun neverRelocatesToDisplayZero() =
        assertNull(relocationTarget(current = 4, picked = 0, relocated = false, lastRelocateMs = 0, nowMs = 60_000))

    @Test fun relocatesOnlyOnce() =
        assertNull(relocationTarget(current = 0, picked = 4, relocated = true, lastRelocateMs = 0, nowMs = 60_000))

    @Test fun cooldownBlocksRepeat() {
        val last = 50_000L
        assertNull(relocationTarget(0, 4, false, last, last + RELOCATE_COOLDOWN_MS - 1))
        assertEquals(4, relocationTarget(0, 4, false, last, last + RELOCATE_COOLDOWN_MS))
    }
}
