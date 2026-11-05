package com.diptych.app

import com.diptych.app.display.DisplayInfo
import com.diptych.app.display.pickCompanionDisplay
import java.util.Locale

/** Plain-text spike results, one `key=value` per line, followed by the mod log (if fetched) and the app log. */
fun buildReport(ui: SpikeUi, log: List<String>, device: String, displays: List<DisplayInfo>): String {
    val w = ui.welcome
    val s = ui.state
    return buildString {
        appendLine("Diptych KH1 spike report")
        appendLine("device=$device")
        appendLine("displays=" + displays.joinToString(", ") {
            "${it.id}:${it.w}x${it.h}" + (if (it.isOn) " on" else " off") + (if (it.isPrivate) " private" else "")
        })
        appendLine("companionDisplay=${pickCompanionDisplay(displays) ?: "none"}")
        appendLine("connected=${ui.connected}")
        appendLine("plugin=${w?.plugin ?: "-"}")
        appendLine("protocol=${w?.protocol ?: "-"}")
        appendLine("gameId=${w?.gameId ?: "-"}")
        appendLine("gameBuild=${w?.gameBuild ?: "-"}")
        appendLine("supported=${w?.supported ?: "-"}")
        appendLine("loop=${ui.loop.label}")
        appendLine("seqPerSec=" + (ui.seqRate?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"))
        appendLine("rttMs=${ui.rttMs ?: "-"}")
        appendLine("seq=${s?.seq ?: "-"}")
        appendLine("world=${s?.world ?: "-"}")
        appendLine("worldAlt=${s?.worldAlt ?: "-"}")
        appendLine("room=${s?.room ?: "-"}")
        appendLine("hp=${s?.soraHp ?: "-"}/${s?.soraHpMax ?: "-"}")
        appendLine("inMenu=${s?.inMenu ?: "-"}")
        appendLine("inCutscene=${s?.inCutscene ?: "-"}")
        appendLine("inGummi=${s?.inGummi ?: "-"}")
        appendLine("inputBytes=${s?.inputBytes ?: "-"}")
        appendLine("lastResult=${ui.lastResult ?: "-"}")
        ui.modLog?.let { lines ->
            appendLine("--- mod log (${lines.size}) ---")
            lines.forEach { appendLine(it) }
        }
        appendLine("--- log (${log.size}) ---")
        log.forEach { appendLine(it) }
    }
}
