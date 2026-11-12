package com.diptych.app.ttw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diptych.app.LoopStatus
import com.diptych.app.kh1.ui.ConnectionLabel
import java.util.Locale

/** The Pip-Boy's stock green: the theme until the game reports its own colour, or when that one is unreadable. */
val PIP_GREEN = Color(0xFF1AFF80)

/** Which of TTW's two wastelands the player is in, from its `bInDCWasteland` / `bInNVWasteland` globals. */
enum class Wasteland(val label: String) {
    Capital("CAPITAL WASTELAND"), Mojave("MOJAVE WASTELAND"), Unknown("WASTELAND UNKNOWN")
}

fun wasteland(s: TtwState?): Wasteland = when {
    s?.bInDCWasteland == 1 -> Wasteland.Capital
    s?.bInNVWasteland == 1 -> Wasteland.Mojave
    else -> Wasteland.Unknown
}

/** HP as 0..1 for the gauge; null when either value is missing or the maximum isn't positive. */
fun hpFraction(s: TtwState?): Float? {
    val hp = s?.hp ?: return null
    val max = s.hpMax ?: return null
    if (max <= 0.0) return null
    return (hp / max).toFloat().coerceIn(0f, 1f)
}

private fun whole(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "-"

fun hpText(s: TtwState?): String = "${whole(s?.hp)} / ${whole(s?.hpMax)}"

/** The theme colour: the game's own Pip-Boy colour when it is a readable `[r,g,b]`, else [PIP_GREEN]. */
fun pipColor(s: TtwState?): Color {
    val rgb = s?.colors?.pipboy ?: return PIP_GREEN
    if (rgb.size < 3 || rgb.take(3).any { it !in 0..255 }) return PIP_GREEN
    val c = Color(rgb[0], rgb[1], rgb[2])
    return if (c.luminance() < MIN_LUMINANCE) PIP_GREEN else c
}

/** Below this a colour can't be read on the screen's black. */
private const val MIN_LUMINANCE = 0.08f

/** Why there is no dashboard, or null when there is one to show. */
fun ttwBanner(connection: ConnectionLabel, ui: TtwUi): String? = when {
    connection == ConnectionLabel.Unsupported -> TTW_NOT_LOADED
    connection == ConnectionLabel.Waiting || ui.state == null -> "Waiting for the wasteland…"
    !ui.state.saveLoaded -> "Load a save to begin"
    else -> null
}

const val TTW_NOT_LOADED = "Tale of Two Wastelands isn't loaded in this New Vegas"

/** A line for a link that is up but not delivering frames, or null while the game loop runs. */
fun stallNote(ui: TtwUi): String? = when (ui.loop) {
    LoopStatus.Alive -> null
    LoopStatus.Stalled -> "GAME LOOP PAUSED (loading or menu)"
    LoopStatus.SocketDead -> "LINK LOST"
}

/** (cell, worldspace) as shown; an interior cell has no worldspace of its own. */
fun locationLines(s: TtwState?): Pair<String, String> =
    (s?.cell ?: "-") to (s?.worldspace ?: if (s?.interior == true) "Interior" else "-")

private val Ground = Color(0xFF020503)

private fun mono(color: Color, size: Int, bold: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Monospace,
    color = color,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = 0.5.sp,
)

/**
 * The TTW companion screen (bottom display): a Pip-Boy status page in the game's own Pip-Boy colour. HP, which
 * wasteland, the cell, and the two commands the spike plugin has. The top-left 56x48 dp belongs to the hinge
 * button, so the header starts to its right.
 */
@Composable
fun TtwScreen(ui: TtwUi, connection: ConnectionLabel, onEquip: () -> Unit, onDumpWorld: () -> Unit) {
    val pip = pipColor(ui.state)
    Box(Modifier.fillMaxSize().background(Ground)) {
        val banner = ttwBanner(connection, ui)
        if (banner != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    banner,
                    Modifier.widthIn(max = 420.dp).border(1.dp, pip).padding(horizontal = 28.dp, vertical = 22.dp),
                    style = mono(pip, 18, bold = true),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Dashboard(ui, ui.state!!, pip, onEquip, onDumpWorld)
        }
    }
}

@Composable
private fun Dashboard(ui: TtwUi, s: TtwState, pip: Color, onEquip: () -> Unit, onDumpWorld: () -> Unit) {
    val dim = pip.copy(alpha = 0.55f)
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        // Header, clear of the hinge button (56 dp) on the left.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("STAT", style = mono(pip, 20, bold = true))
            Spacer(Modifier.weight(1f))
            Text(wasteland(s).label, style = mono(pip, 14, bold = true))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(pip))
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text("HP", style = mono(pip, 28, bold = true))
            if (s.hardcore == true) {
                Text(
                    "HARDCORE",
                    Modifier.padding(start = 12.dp, bottom = 7.dp).border(1.dp, pip).padding(horizontal = 6.dp, vertical = 1.dp),
                    style = mono(pip, 11, bold = true),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(hpText(s), style = mono(pip, 28, bold = true))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(22.dp).border(2.dp, pip).padding(4.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(hpFraction(s) ?: 0f).background(pip))
        }
        Spacer(Modifier.height(24.dp))

        val (cell, world) = locationLines(s)
        Text("LOCATION", style = mono(dim, 12, bold = true))
        Spacer(Modifier.height(4.dp))
        Text(cell, style = mono(pip, 24, bold = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(world, style = mono(dim, 14), maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.weight(1f))
        stallNote(ui)?.let {
            Text(it, Modifier.fillMaxWidth().background(pip).padding(horizontal = 8.dp, vertical = 4.dp), style = mono(Ground, 13, bold = true))
            Spacer(Modifier.height(10.dp))
        }
        Text("> ${ui.lastResult ?: "-"}", style = mono(dim, 12), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PipButton("EQUIP FIRST WEAPON", pip, Modifier.weight(1f), onEquip)
            PipButton("WHERE AM I", pip, Modifier.weight(1f), onDumpWorld)
        }
    }
}

@Composable
private fun PipButton(label: String, pip: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).border(2.dp, pip).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = mono(pip, 14, bold = true)) }
}
