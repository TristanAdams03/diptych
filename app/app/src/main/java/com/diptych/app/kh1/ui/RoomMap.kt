package com.diptych.app.kh1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diptych.app.kh1.MapView
import com.diptych.app.kh1.RoomView
import kotlin.math.roundToInt

// Grid geometry in dp. The title and key bar are their own layout slots, so the grid only needs a small margin.
private const val BOX_W = 124f
private const val BOX_H = 52f
private const val PITCH_X = 138f
private const val PITCH_Y = 62f
private const val PAD_X = 12f
private const val PAD_Y = 8f
private const val FIT_COLS = 4
private const val FIT_PAD_X = 8f
private const val FIT_GAP = 8f
private const val FIT_BOX_H = 48f
private const val FIT_GAP_Y = 6f
private const val FIT_PAD_Y = 4f

/** Cell geometry for one world. Worlds of ≤ [FIT_COLS] columns are sized to the viewport width (no horizontal pan). */
internal data class GridMetrics(
    val boxW: Float, val boxH: Float, val pitchX: Float, val pitchY: Float, val padX: Float, val padY: Float,
    val labelSp: Float, val innerPad: Float,
) {
    fun boxX(col: Int) = padX + col * pitchX
    fun boxY(row: Int) = padY + row * pitchY
    fun contentWidth(cols: Int) = padX * 2 + (cols - 1) * pitchX + boxW
    fun contentHeight(rows: Int) = padY * 2 + (rows - 1) * pitchY + boxH
}

internal fun gridMetrics(cols: Int, viewportWidthDp: Float): GridMetrics {
    val standard = GridMetrics(BOX_W, BOX_H, PITCH_X, PITCH_Y, PAD_X, PAD_Y, 12f, 6f)
    if (cols > FIT_COLS || standard.contentWidth(cols) <= viewportWidthDp) return standard
    val pitch = (viewportWidthDp - 2 * FIT_PAD_X + FIT_GAP) / cols
    // Tighter rows too (11 sp label: two lines plus the count fit in 48 dp), so a 5-row world usually fits without panning.
    return GridMetrics(pitch - FIT_GAP, FIT_BOX_H, pitch, FIT_BOX_H + FIT_GAP_Y, FIT_PAD_X, FIT_PAD_Y, 11f, 4f)
}

/** "o / t", "n / n ✓" when every chest is opened, "? / t" when unreadable, "no chests" for none. */
internal fun chestText(opened: Int?, total: Int): String = when {
    total == 0 -> "no chests"
    opened == null -> "? / $total"
    opened >= total -> "$opened / $total ✓"
    else -> "$opened / $total"
}

/** One pan axis, kept so the content never leaves a gap at either edge; 0 when the content fits. */
internal fun clampPan(pan: Float, content: Float, viewport: Float): Float =
    if (content <= viewport + 0.5f) 0f else pan.coerceIn(viewport - content, 0f)

/** Pan that puts [target] (content px) at the viewport center, clamped; zero when the room is unknown. */
internal fun centeredPan(target: Offset?, cw: Float, ch: Float, vw: Float, vh: Float): Offset =
    if (target == null) Offset.Zero
    else Offset(clampPan(vw / 2 - target.x, cw, vw), clampPan(vh / 2 - target.y, ch, vh))

/** The map panel: title, pannable room grid, key bar; or "No map for this world yet". */
@Composable
fun RoomMap(view: MapView, modifier: Modifier = Modifier) {
    Column(modifier.kh1Panel().padding(2.dp).clip(RoundedCornerShape(12.dp))) {
        Text(
            if (view.chestsTotal == 0) "ROOMS" else "ROOMS · CHESTS ${view.chestsOpened ?: "?"} / ${view.chestsTotal}",
            Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
            color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (view.rooms.isEmpty()) {
                Text(
                    "No map for this world yet",
                    Modifier.align(Alignment.Center).padding(16.dp),
                    color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                PannableGrid(view)
            }
        }
        if (view.rooms.isNotEmpty()) KeyBar(Modifier)
    }
}

@Composable
private fun PannableGrid(view: MapView) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val px = with(density) { 1.dp.toPx() }
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val cols = (view.rooms.maxOf { it.col } + 1)
        val rows = (view.rooms.maxOf { it.row } + 1)
        val m = gridMetrics(cols, maxWidth.value)
        val cw = m.contentWidth(cols) * px
        val ch = m.contentHeight(rows) * px
        val here = view.rooms.firstOrNull { it.here }
        // Re-center on world change, viewport change, or when the current room first becomes known; drags move it after that.
        var pan by remember(view.worldName, vw, vh, here != null) {
            val target = here?.let { Offset((m.boxX(it.col) + m.boxW / 2) * px, (m.boxY(it.row) + m.boxH / 2) * px) }
            mutableStateOf(centeredPan(target, cw, ch, vw, vh))
        }
        val byId = view.rooms.associateBy { it.id }
        Box(
            Modifier.fillMaxSize().pointerInput(view.worldName, cw, ch, vw, vh) {
                detectDragGestures { change, drag ->
                    change.consume()
                    pan = Offset(clampPan(pan.x + drag.x, cw, vw), clampPan(pan.y + drag.y, ch, vh))
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for ((a, b) in view.edges) {
                    val ra = byId[a] ?: continue
                    val rb = byId[b] ?: continue
                    fun center(r: RoomView) =
                        Offset((m.boxX(r.col) + m.boxW / 2) * px + pan.x, (m.boxY(r.row) + m.boxH / 2) * px + pan.y)
                    drawLine(Kh1Colors.Edge, center(ra), center(rb), strokeWidth = 3 * px)
                }
            }
            for (r in view.rooms) {
                RoomBox(
                    r, m,
                    Modifier.offset {
                        IntOffset((m.boxX(r.col) * px + pan.x).roundToInt(), (m.boxY(r.row) * px + pan.y).roundToInt())
                    },
                )
            }
        }
    }
}

private val RoomShape = RoundedCornerShape(9.dp)

@Composable
private fun RoomBox(r: RoomView, m: GridMetrics, modifier: Modifier) {
    val fill = if (r.here) Kh1Colors.You else Kh1Colors.Room
    Box(
        modifier.requiredSize(m.boxW.dp, m.boxH.dp)
            .background(fill, RoomShape)
            .border(if (r.here) 2.dp else 1.5.dp, if (r.here) Color.White else Kh1Colors.RoomRim, RoomShape)
            .padding(horizontal = m.innerPad.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    append(r.label)
                    if (r.here) withStyle(SpanStyle(fontWeight = FontWeight.W800)) { append(" · you") }
                },
                color = if (r.here) Color.White else Kh1Colors.Text,
                fontFamily = Nunito, fontWeight = if (r.here) FontWeight.W800 else FontWeight.W700,
                fontSize = m.labelSp.sp, lineHeight = (m.labelSp + 2).sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                chestText(r.opened, r.total),
                color = when {
                    r.here -> Kh1Colors.YouCount
                    r.total == 0 -> Kh1Colors.Dim
                    else -> Kh1Colors.Gold
                },
                fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 13.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyBar(modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth().height(28.dp)
            .background(Kh1Colors.Room, shape).border(1.dp, Kh1Colors.KeyRim, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyItem("Current") {
            Box(
                Modifier.size(14.dp, 9.dp).background(Kh1Colors.You, RoundedCornerShape(3.dp))
                    .border(1.5.dp, Color.White, RoundedCornerShape(3.dp)),
            )
        }
        KeyItem("Cleared") { KeyText("✓", Kh1Colors.Gold) }
        KeyItem("Chests") { KeyText("2 / 4", Kh1Colors.Gold) }
    }
}

@Composable
private fun KeyItem(label: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(6.dp))
        KeyText(label, Kh1Colors.KeyText)
    }
}

@Composable
private fun KeyText(s: String, color: Color) {
    Text(s, color = color, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp, letterSpacing = 0.3.sp, maxLines = 1)
}
