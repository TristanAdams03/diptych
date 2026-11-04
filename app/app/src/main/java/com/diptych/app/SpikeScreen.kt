package com.diptych.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Placeholder spike styling (not the Diptych look).
private val Bg = Color(0xFF101418)
private val Fg = Color(0xFFD8E1EA)
private val Accent = Color(0xFF7FB8FF)
private val Warn = Color(0xFFFFC857)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, color = Fg, fontSize = 14.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpikeScreen(
    ui: SpikeUi,
    log: List<String>,
    focusOn: Boolean,
    onFetchModLog: () -> Unit,
    onToggleFocus: () -> Unit,
    onCopyReport: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LoopBanner(ui)
        val w = ui.welcome
        if (w == null) {
            Line("build: -")
        } else {
            Line(
                "build ${w.gameBuild}  supported=${w.supported}   (${w.gameId}, mod ${w.plugin} p${w.protocol})",
                color = if (w.supported) Fg else Warn,
            )
        }
        val s = ui.state
        Line("world ${s?.world ?: "-"}  worldAlt ${s?.worldAlt ?: "-"}  room ${s?.room ?: "-"}   seq ${s?.seq ?: "-"}")
        HpBar(s?.soraHp, s?.soraHpMax)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flag("menu", s?.inMenu)
            Flag("cutscene", s?.inCutscene)
            Flag("gummi", s?.inGummi)
        }
        Line("inputBytes ${s?.inputBytes ?: "--------"}", size = 20)
        // FlowRow: the Thor's bottom panel is ~450 dp wide, too narrow for all buttons in one row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpikeButton("Copy report", onCopyReport)
            SpikeButton("Fetch mod log", onFetchModLog)
            SpikeButton("Focus mode ${if (focusOn) "on" else "off"}", onToggleFocus)
        }
        Line("last result: ${ui.lastResult ?: "-"}")
        LinesPane("mod log", ui.modLog ?: listOf("(not fetched)"), follow = false, Modifier.weight(1f))
        LinesPane("app log", log, follow = true, Modifier.weight(1f))
    }
}

@Composable
private fun LoopBanner(ui: SpikeUi) {
    val (fg, bg) = when (ui.loop) {
        LoopStatus.Alive -> Color.Black to Accent
        LoopStatus.Stalled -> Color.Black to Warn
        LoopStatus.SocketDead -> Fg to Color(0xFF6A1B1B)
    }
    val rate = ui.seqRate?.let { "%.1f seq/s".format(Locale.ROOT, it) } ?: "- seq/s"
    val rtt = ui.rttMs?.let { "  rtt ${it}ms" } ?: ""
    Box(Modifier.fillMaxWidth().background(bg).padding(vertical = 10.dp, horizontal = 12.dp)) {
        Text(
            "LOOP: ${ui.loop.label}   $rate$rtt",
            style = Mono.copy(color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Line(text: String, color: Color = Fg, size: Int = 14) =
    Text(text, style = Mono.copy(color = color, fontSize = size.sp))

@Composable
private fun HpBar(hp: Int?, max: Int?) {
    val frac = if (hp != null && max != null && max > 0) (hp.toFloat() / max).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("HP", style = Mono)
        Box(Modifier.width(200.dp).height(16.dp).border(1.dp, Fg)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color(0xFF4CC38A)))
        }
        Text("${hp ?: "-"} / ${max ?: "-"}", style = Mono)
    }
}

@Composable
private fun Flag(name: String, on: Boolean?) {
    val (fg, bg, label) = when (on) {
        true -> Triple(Color.Black, Warn, "$name ON")
        false -> Triple(Fg, Color(0xFF1E2730), "$name off")
        null -> Triple(Fg, Color(0xFF1E2730), "$name ?")
    }
    Box(Modifier.background(bg).border(1.dp, Fg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, style = Mono.copy(color = fg, fontSize = 13.sp))
    }
}

@Composable
private fun SpikeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2730), contentColor = Accent),
        modifier = Modifier.border(1.dp, Accent),
    ) { Text(label, style = Mono.copy(color = Accent)) }
}

/** Scrollable list of lines; [follow] keeps it scrolled to the newest line. */
@Composable
private fun LinesPane(title: String, lines: List<String>, follow: Boolean, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (lines.isNotEmpty()) state.scrollToItem(if (follow) lines.size - 1 else 0)
    }
    Column(modifier.fillMaxWidth().border(1.dp, Fg).padding(6.dp)) {
        Text("$title (${lines.size})", style = Mono.copy(fontSize = 11.sp, color = Accent))
        Spacer(Modifier.height(4.dp))
        LazyColumn(state = state) {
            items(lines) { Text(it, style = Mono.copy(fontSize = 11.sp)) }
        }
    }
}
