package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The hinge pull-down's choices, in order. */
enum class HingeChoice(val label: String) { Shelf("Shelf"), Device("Device"), Settings("Settings") }

/**
 * A companion screen, full-bleed, with Diptych's two ways out over it: the hinge button in the top-left corner (by
 * the device's hinge) and, across the companion's header strip, the 1 s hold that toggles diagnostics. The strip
 * leaves the hinge button's 48 dp and the header's right 72 dp (KH1's spoiler toggle) alone.
 */
@Composable
fun CompanionFrame(
    onChoose: (HingeChoice) -> Unit,
    onDiagnostics: () -> Unit,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        HoldStrip(Modifier.align(Alignment.TopStart).padding(start = HINGE_TARGET, end = 72.dp), onDiagnostics)
        HingeButton(onChoose, initiallyOpen = initiallyOpen)
    }
}

private val HINGE_TARGET = 48.dp

/**
 * A 56×48 dp corner button: a small (44×21 dp) charcoal tab with the hinge mark, hanging from the top-left corner,
 * clear of the companion's header text. It opens a
 * pull-down with Shelf, Device and Settings; a tap outside closes it.
 */
@Composable
fun HingeButton(onChoose: (HingeChoice) -> Unit, modifier: Modifier = Modifier, initiallyOpen: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box(modifier.fillMaxSize()) {
        if (open) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) {
                    detectTapGestures { open = false }
                },
            )
        }
        Box(
            Modifier
                .size(HINGE_TARGET + 8.dp, HINGE_TARGET)
                .semantics {
                    contentDescription = "Diptych menu"
                    stateDescription = if (open) "Open" else "Closed"
                }
                .clickable(role = Role.Button) { open = !open },
        ) {
            val tab = RoundedCornerShape(bottomEnd = 12.dp)
            // Nudged 1 dp past the corner so only the tab's inner edges show their brass line. 21 dp tall on screen, so
            // its edge clears the KH1 world name (cap tops at 24.4 dp on the single-line header) by 3 dp.
            Box(
                Modifier
                    .offset((-1).dp, (-1).dp)
                    .size(45.dp, 22.dp)
                    .clip(tab)
                    .background(if (open) ShellColors.Raised else ShellColors.Ground.copy(alpha = 0.94f))
                    .border(1.dp, ShellColors.Brass.copy(alpha = 0.7f), tab),
                contentAlignment = Alignment.Center,
            ) { HingeMark(Modifier.offset(0.5.dp, 0.5.dp)) }
        }
        if (open) {
            Menu(Modifier.padding(start = 8.dp, top = 32.dp)) { choice ->
                open = false
                onChoose(choice)
            }
        }
    }
}

@Composable
private fun Menu(modifier: Modifier, onChoose: (HingeChoice) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(212.dp)
            .clip(shape)
            .background(ShellColors.Surface)
            .border(1.dp, ShellColors.Line, shape)
            .padding(vertical = 6.dp),
    ) {
        // Names whose menu this is: it sits over a game's own screen.
        Text("Diptych", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = ShellColors.Muted, style = displayText(14.sp))
        HingeChoice.entries.forEach { choice ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(role = Role.Button) { onChoose(choice) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(choice.label, color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
