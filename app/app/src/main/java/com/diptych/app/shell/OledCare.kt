package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How often the whole screen moves to the next pixel-shift position. */
const val SHIFT_PERIOD_MS = 60_000L

/** With no game connected and no touch for this long, the shell dims. */
const val IDLE_DIM_MS = 5 * 60_000L

/** The idle overlay's black: the shell is left at 10% of its brightness. */
const val IDLE_DIM_ALPHA = 0.9f

/** Pixel-shift positions in dp, visited in order, one per [SHIFT_PERIOD_MS]: a 2 dp square walked around its edge. */
val SHIFT_CYCLE: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)

fun pixelShift(step: Long): Pair<Int, Int> = SHIFT_CYCLE[Math.floorMod(step, SHIFT_CYCLE.size.toLong()).toInt()]

/** True once nothing has happened (no game connected, no touch) for [IDLE_DIM_MS]. */
fun idleDimmed(connected: Boolean, lastActivityMs: Long, nowMs: Long): Boolean =
    !connected && nowMs - lastActivityMs >= IDLE_DIM_MS

/**
 * OLED care for everything on the bottom screen: [content] moves to the next [SHIFT_CYCLE] position every minute,
 * and, when [dimAllowed] (not the companion), dims after [IDLE_DIM_MS] without a connection or a touch.
 * The touch that wakes a dimmed screen only wakes it, so it can't press something the user couldn't see.
 */
@Composable
fun OledCare(
    connected: Boolean,
    dimAllowed: Boolean,
    clock: () -> Long = System::currentTimeMillis,
    content: @Composable () -> Unit,
) {
    var step by remember { mutableLongStateOf(0L) }
    var lastActivity by remember { mutableLongStateOf(clock()) }
    var dimmed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(SHIFT_PERIOD_MS)
            step += 1
        }
    }
    LaunchedEffect(connected) {
        lastActivity = clock() // connecting, and losing the game, both count as activity
        dimmed = false
    }
    LaunchedEffect(connected, lastActivity) {
        while (!connected && !dimmed) {
            val now = clock()
            if (idleDimmed(connected, lastActivity, now)) dimmed = true
            else delay(lastActivity + IDLE_DIM_MS - now)
        }
    }

    val (dx, dy) = pixelShift(step)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Every touch counts, seen on its way down to the content and never consumed.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) lastActivity = clock()
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().offset(dx.dp, dy.dp)) { content() }
        if (dimAllowed && dimmed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = IDLE_DIM_ALPHA))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            dimmed = false
                            lastActivity = clock()
                        }
                    },
            )
        }
    }
}
