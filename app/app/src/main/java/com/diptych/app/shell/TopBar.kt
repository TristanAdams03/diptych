package com.diptych.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ShellTab(val label: String) { Shelf("Shelf"), Device("Device"), Guide("Guide") }

/** Hinge mark, "Diptych" wordmark with its DIP-tick, the three text tabs (active one underlined in brass) and a gear. */
@Composable
fun TopBar(active: ShellTab, onSelect: (ShellTab) -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ShellColors.Ground)
            .drawBehind {
                drawLine(ShellColors.Line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            }
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HingeMark()
        Spacer(Modifier.width(12.dp))
        Text(
            "Diptych",
            style = TextStyle(fontFamily = ShellType.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.2.sp),
            color = ShellColors.Ink,
        )
        Text(
            "DIP-tick",
            style = TextStyle(fontFamily = ShellType.body, fontSize = 12.sp),
            color = ShellColors.Muted,
            modifier = Modifier.padding(start = 6.dp, top = 5.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ShellTab.entries.forEach { tab -> TabLabel(tab, tab == active) { onSelect(tab) } }
        }
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = "Settings" }
                .clickable(role = Role.Button, onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { Gear() }
    }
}

@Composable
private fun TabLabel(tab: ShellTab, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .selectable(selected = on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tab.label,
            style = TextStyle(fontFamily = ShellType.body, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = if (on) ShellColors.Ink else ShellColors.Muted,
            modifier = Modifier.drawBehind {
                if (on) {
                    val y = size.height + 4.dp.toPx()
                    drawLine(ShellColors.Brass, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                }
            },
        )
    }
}

/** A drawn gear (the glyph is not in every font): a ring with eight teeth. */
@Composable
private fun Gear() {
    Canvas(Modifier.size(20.dp)) {
        val c = center
        val ringR = size.minDimension * 0.27f
        val w = size.minDimension * 0.11f
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawLine(
                    ShellColors.Muted,
                    Offset(c.x, c.y - ringR),
                    Offset(c.x, c.y - size.minDimension * 0.50f),
                    strokeWidth = size.minDimension * 0.20f,
                    cap = StrokeCap.Butt,
                )
            }
        }
        drawCircle(ShellColors.Muted, ringR + w / 2, c, style = Stroke(w))
    }
}
