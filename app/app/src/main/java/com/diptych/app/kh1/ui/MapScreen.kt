package com.diptych.app.kh1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diptych.app.kh1.MapView

enum class ConnectionLabel { Connected, Waiting, Unsupported }

/**
 * The KH1 map tab (bottom screen). Shows a centered banner while [connection] is not Connected, or before the first
 * [view] arrives.
 */
@Composable
fun MapScreen(view: MapView?, connection: ConnectionLabel, onReport: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kh1Colors.Ground)) {
        when {
            connection == ConnectionLabel.Unsupported -> Banner("This KH1 build isn't supported yet (needs Steam 1.0.0.2)")
            connection == ConnectionLabel.Waiting || view == null -> Banner("Waiting for Kingdom Hearts…")
            else -> MapLayout(view, onReport)
        }
    }
}

@Composable
private fun MapLayout(view: MapView, onReport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(view)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoomMap(view, Modifier.weight(1f).fillMaxSize())
            Column(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PartyPanel(view.party, Modifier.weight(1f).fillMaxWidth())
                ReportButton(onReport)
            }
        }
        Tabs()
    }
}

@Composable
private fun Header(view: MapView) {
    Box(Modifier.fillMaxWidth().kh1Panel().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            buildAnnotatedString {
                append(view.worldName.uppercase())
                withStyle(SpanStyle(color = Kh1Colors.Muted, fontWeight = FontWeight.W600)) { append(" · ${view.roomName.uppercase()}") }
            },
            color = Kh1Colors.Text, fontFamily = Fredoka, fontWeight = FontWeight.W700, fontSize = 17.sp,
            letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReportButton(onReport: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onReport),
        contentAlignment = Alignment.Center,
    ) {
        Text("This looks wrong", color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp)
    }
}

@Composable
private fun Tabs() {
    Row(Modifier.fillMaxWidth().kh1Panel().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tab("MAP", active = true, Modifier.weight(1f))
        Tab("MAGIC & ITEMS", active = false, Modifier.weight(1.3f))
        Tab("PARTY", active = false, Modifier.weight(1f))
        Tab("JOURNAL", active = false, Modifier.weight(1f))
    }
}

/** Only MAP is live in M2; the others are dimmed, marked "soon" and have no action. */
@Composable
private fun Tab(label: String, active: Boolean, modifier: Modifier) {
    Column(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) Kh1Colors.You else Color.Transparent)
            .then(if (active) Modifier else Modifier.semantics { disabled() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label, color = if (active) Color.White else Kh1Colors.Dim, fontFamily = Fredoka,
            fontWeight = if (active) FontWeight.W700 else FontWeight.W600, fontSize = 14.sp, letterSpacing = 1.sp,
            lineHeight = 16.sp, maxLines = 1,
        )
        if (!active) {
            Text("soon", color = Kh1Colors.Dim.copy(alpha = 0.8f), fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun Banner(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 420.dp).kh1Panel().padding(horizontal = 28.dp, vertical = 22.dp)) {
            Text(
                message, color = Kh1Colors.Text, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 18.sp,
                letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
