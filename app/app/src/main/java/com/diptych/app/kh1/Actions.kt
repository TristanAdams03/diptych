package com.diptych.app.kh1

import com.diptych.app.net.MemCondition
import com.diptych.app.net.PressSequenceArgs
import com.diptych.app.net.SeqStep
import com.diptych.app.net.SeqUntil
import com.diptych.app.net.ServerMsg

/** A shortcut cast, planned from one [MapView]: the sequence to send, or why there is none. */
sealed interface CastPlan {
    data class Ready(val args: PressSequenceArgs, val slot: Int) : CastPlan
    data class Refused(val reason: String) : CastPlan
}

/** What came of [Kh1Session.castShortcut]. */
sealed interface CastOutcome {
    /** Nothing was sent: [reason] says why (the same strings as [LearnedSpell.actionReason]). */
    data class Refused(val reason: String) : CastOutcome
    /** Not connected, or the mod has no `sequence` feature. */
    data object NotSent : CastOutcome
    /** The mod's result, sent when the sequence ended (`ok`, or `not safe now`, `timeout`, `busy`, ...). */
    data class Sent(val result: ServerMsg.Result) : CastOutcome
}

const val ACT_NOT_CONFIGURED = "Not available for this game build"
const val ACT_NOT_LEARNED = "Not learned yet"
const val ACT_SHORTCUTS_UNKNOWN = "Can't read the shortcuts"
const val ACT_NOT_ON_SHORTCUT = "Not on a shortcut"
const val ACT_SWAP_UNKNOWN = "Can't read the A/B setting"
const val ACT_MENU_UNKNOWN = "Can't read the command menu"
const val ACT_MENU_BUSY = "Command menu in use"
const val ACT_SORA_DOWN = "Sora is down"
const val ACT_HP_UNKNOWN = "Can't read Sora's HP"
const val ACT_PICK_TARGET = "Pick a target"
const val ACT_NO_TARGET = "No such party member"
fun actNeedsMp(cost: Int) = "Needs $cost MP"

/** Press timings (ms). A press must last a few game frames; the gap lets the game act on it. */
internal const val PRESS_HOLD_MS = 100
internal const val TAP_HOLD_MS = 66
internal const val STEP_GAP_MS = 50
/** How long the target picker may take to open after the shortcut press. */
internal const val PICKER_TIMEOUT_MS = 500
/** Without a known picker byte: a plain wait for the picker to open before moving in it. */
internal const val PICKER_BLIND_GAP_MS = 300

/**
 * Plans casting the spell with [spellBit] from its shortcut: L1 + the slot's face button, then (for a spell that opens
 * the target picker) D-pad down [targetIdx] times and confirm. Pure.
 *
 * Refused (nothing to send) when the game is busy, the build has no action data, the spell isn't learned or isn't on
 * a shortcut, Sora is down, MP is short, or the bytes the plan relies on are unknown. A target spell needs a
 * [targetIdx] in `0 until partyCount`; other spells ignore it.
 *
 * The plan's `require` repeats what it relied on (the A/B swap, the slot's spell, the command menu at its top level),
 * so the mod refuses it (`not safe now`) if the game changed since [view]'s frame. With a known picker byte the first
 * press waits for the picker (`until`, else `timeout`) and the rest run only while it is open (`onlyIf`); without one
 * it waits [PICKER_BLIND_GAP_MS] instead.
 */
fun planShortcutCast(
    config: PartyConfig,
    spellBit: Int,
    targetIdx: Int?,
    partyCount: Int,
    view: MapView,
): CastPlan {
    fun no(reason: String) = CastPlan.Refused(reason)
    val shortcutsDef = config.shortcuts ?: return no(ACT_NOT_CONFIGURED)
    val swapDef = config.swap ?: return no(ACT_NOT_CONFIGURED)
    val menuDef = config.commandMenu ?: return no(ACT_NOT_CONFIGURED)
    val buttons = config.buttons ?: return no(ACT_NOT_CONFIGURED)
    val spellDef = config.magic?.spells?.firstOrNull { it.bit == spellBit } ?: return no(ACT_NOT_CONFIGURED)

    view.busyReason?.let { return no(it) }
    if (view.sora.known && view.sora.spells.none { it.bit == spellBit }) return no(ACT_NOT_LEARNED)
    val actions = view.actions
    val shortcuts = actions.shortcuts ?: return no(ACT_SHORTCUTS_UNKNOWN)
    val slot = shortcuts.indexOf(spellBit)
    if (slot < 0) return no(ACT_NOT_ON_SHORTCUT)
    val shortcutMask = buttons.shortcutMasks.getOrNull(slot) ?: return no(ACT_NOT_CONFIGURED)
    val swap = actions.swap ?: return no(ACT_SWAP_UNKNOWN)
    val confirmMask = buttons.confirm[swap.toString()] ?: return no(ACT_NOT_CONFIGURED)
    val menu = actions.menu ?: return no(ACT_MENU_UNKNOWN)
    if (menu.type != 0) return no(ACT_MENU_BUSY)

    val sora = view.party.firstOrNull()
    val hp = sora?.hp ?: return no(ACT_HP_UNKNOWN)
    if (hp <= 0) return no(ACT_SORA_DOWN)
    val mp = sora.mp ?: return no(BUSY_NO_MP)
    if (mp < spellDef.mpCost) return no(actNeedsMp(spellDef.mpCost))

    val require = listOf(
        MemCondition(swapDef.offset, swap),
        MemCondition(shortcutsDef.offset + slot, spellBit),
        MemCondition(menuDef.typeOffset, 0),
    )
    val cast = SeqStep(buttons.shortcutByte, shortcutMask, PRESS_HOLD_MS, STEP_GAP_MS)
    if (!spellDef.needsTarget) return CastPlan.Ready(PressSequenceArgs(require, listOf(cast)), slot)

    val target = targetIdx ?: return no(ACT_PICK_TARGET)
    if (target !in 0 until partyCount) return no(ACT_NO_TARGET)
    val picker = menuDef.targetPickerType
    val pickerOpen = picker?.let { MemCondition(menuDef.typeOffset, it) }
    val first = if (picker != null) {
        cast.copy(until = SeqUntil(menuDef.typeOffset, picker, timeoutMs = PICKER_TIMEOUT_MS))
    } else {
        cast.copy(gapMs = PICKER_BLIND_GAP_MS)
    }
    val moves = List(target) { SeqStep(buttons.dpadByte, buttons.dpadDown, TAP_HOLD_MS, STEP_GAP_MS, onlyIf = pickerOpen) }
    val confirm = SeqStep(buttons.faceByte, confirmMask, TAP_HOLD_MS, STEP_GAP_MS, onlyIf = pickerOpen)
    return CastPlan.Ready(PressSequenceArgs(require, listOf(first) + moves + confirm), slot)
}

/**
 * Why [spell] can't be cast from the companion now, or null when it can. A target spell is judged as if a target
 * were picked: choosing one is the next tap, not a reason to grey the spell out.
 */
fun castReason(config: PartyConfig, spell: LearnedSpell, view: MapView): String? {
    val plan = planShortcutCast(config, spell.bit, if (spell.needsTarget) 0 else null, view.party.size, view)
    return (plan as? CastPlan.Refused)?.reason
}

/** [view] with each learned spell's [LearnedSpell.shortcutSlot] and [LearnedSpell.actionReason] filled in. */
fun withSpellActions(config: PartyConfig, view: MapView): MapView {
    val shortcuts = view.actions.shortcuts
    val spells = view.sora.spells.map { sp ->
        sp.copy(
            shortcutSlot = shortcuts?.indexOf(sp.bit)?.takeIf { it >= 0 },
            actionReason = castReason(config, sp, view),
        )
    }
    return view.copy(sora = view.sora.copy(spells = spells))
}
