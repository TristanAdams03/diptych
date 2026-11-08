package com.diptych.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Two 9x16 dp outlined leaves (1.6 dp brass stroke, 2 dp radius) with a 2x8 dp brass pin between, 2 dp gaps. */
@Composable
fun HingeMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(9.dp + 2.dp + 2.dp + 2.dp + 9.dp, 16.dp)) {
        val stroke = 1.6.dp.toPx()
        val leafW = 9.dp.toPx()
        val leafH = 16.dp.toPx()
        val gap = 2.dp.toPx()
        val pinW = 2.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        fun leaf(x: Float) = drawRoundRect(
            ShellColors.Brass,
            topLeft = Offset(x + stroke / 2, stroke / 2),
            size = Size(leafW - stroke, leafH - stroke),
            cornerRadius = r,
            style = Stroke(stroke),
        )
        leaf(0f)
        drawRoundRect(
            ShellColors.Brass,
            topLeft = Offset(leafW + gap, (leafH - 8.dp.toPx()) / 2),
            size = Size(pinW, 8.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        leaf(leafW + gap + pinW + gap)
    }
}
