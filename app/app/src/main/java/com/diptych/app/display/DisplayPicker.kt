package com.diptych.app.display

import android.hardware.display.DisplayManager
import android.view.Display

data class DisplayInfo(val id: Int, val w: Int, val h: Int, val isOn: Boolean, val isPrivate: Boolean)

const val RELOCATE_COOLDOWN_MS = 10_000L

/** Display 0 is the game display on the Thor. */
const val GAME_DISPLAY = Display.DEFAULT_DISPLAY

/** The smallest-area display that is on and not private, only when at least two qualify; else null. */
fun pickCompanionDisplay(displays: List<DisplayInfo>): Int? {
    val usable = displays.filter { it.isOn && !it.isPrivate }
    if (usable.size < 2) return null
    return usable.minWith(compareBy<DisplayInfo> { it.w.toLong() * it.h }.thenBy { it.id }).id
}

/**
 * Where to relaunch the activity, or null to stay. Relocates once (the `relocated` extra), at most every
 * [RELOCATE_COOLDOWN_MS], and never to display 0 (the game display): the app must never start an
 * activity there.
 */
fun relocationTarget(current: Int, picked: Int?, relocated: Boolean, lastRelocateMs: Long, nowMs: Long): Int? {
    if (picked == null || picked == current || picked == GAME_DISPLAY) return null
    if (relocated) return null
    if (nowMs - lastRelocateMs < RELOCATE_COOLDOWN_MS) return null
    return picked
}

/** Snapshot of the current displays for [pickCompanionDisplay] (and the report). */
fun DisplayManager.snapshot(): List<DisplayInfo> = displays.map { d ->
    val mode = d.mode
    DisplayInfo(
        id = d.displayId,
        w = mode.physicalWidth,
        h = mode.physicalHeight,
        isOn = d.state == Display.STATE_ON,
        isPrivate = (d.flags and Display.FLAG_PRIVATE) != 0,
    )
}
