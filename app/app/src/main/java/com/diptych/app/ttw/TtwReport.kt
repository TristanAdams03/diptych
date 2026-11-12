package com.diptych.app.ttw

import com.diptych.app.display.DisplayInfo
import com.diptych.app.display.pickCompanionDisplay
import com.diptych.app.service.ConnectionSnapshot
import com.diptych.app.service.KH1_ID
import java.util.Locale

/** Plain-text TTW diagnostics, one `key=value` per line, followed by the link's log. */
fun buildTtwReport(ui: TtwUi, log: List<String>, device: String, displays: List<DisplayInfo>): String {
    val w = ui.welcome
    val s = ui.state
    fun hex(c: List<Int>) = c.joinToString("", "#") { String.format(Locale.ROOT, "%02X", it and 0xFF) }
    return buildString {
        appendLine("Diptych TTW spike report")
        appendLine("device=$device")
        appendLine("displays=" + displays.joinToString(", ") {
            "${it.id}:${it.w}x${it.h}" + (if (it.isOn) " on" else " off") + (if (it.isPrivate) " private" else "")
        })
        appendLine("companionDisplay=${pickCompanionDisplay(displays) ?: "none"}")
        appendLine("connected=${ui.connected}")
        appendLine("plugin=${w?.plugin ?: "-"}")
        appendLine("protocol=${w?.protocol ?: "-"}")
        appendLine("game=${w?.game ?: "-"}")
        appendLine("xnvse=${w?.xnvse ?: "-"}")
        appendLine("ttw=${w?.ttw ?: "-"}")
        appendLine("loop=${ui.loop.label}")
        appendLine("fps=" + (ui.fps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"))
        appendLine("rttMs=${ui.rttMs ?: "-"}")
        appendLine("frame=${s?.frame ?: "-"}")
        appendLine("saveLoaded=${s?.saveLoaded ?: "-"}")
        appendLine("hp=${s?.hp ?: "-"}/${s?.hpMax ?: "-"}")
        appendLine("hardcore=${s?.hardcore ?: "-"}")
        appendLine("bInDCWasteland=${s?.bInDCWasteland ?: "-"}")
        appendLine("bInNVWasteland=${s?.bInNVWasteland ?: "-"}")
        appendLine("worldspace=${s?.worldspace ?: "-"}")
        appendLine("cell=${s?.cell ?: "-"}")
        appendLine("interior=${s?.interior ?: "-"}")
        appendLine("colors=" + (s?.colors?.named()?.joinToString(" ") { (n, c) -> "$n=${hex(c)}" } ?: "-"))
        appendLine("lastResult=${ui.lastResult ?: "-"}")
        appendLine("--- log (${log.size}) ---")
        log.forEach { appendLine(it) }
    }
}

/**
 * Whose diagnostics screen to show: the game with the live welcome; with none live, TTW only when its socket is the
 * one that is open. Otherwise KH1's, as before TTW had a link.
 */
fun diagnosticsGame(snapshot: ConnectionSnapshot): String = when {
    snapshot.welcome != null -> if (snapshot.welcome.gameId == TTW_ID) TTW_ID else KH1_ID
    snapshot.ttw.connected && !snapshot.ui.connected -> TTW_ID
    else -> KH1_ID
}
