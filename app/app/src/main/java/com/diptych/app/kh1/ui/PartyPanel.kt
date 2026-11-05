package com.diptych.app.kh1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diptych.app.kh1.PartyMember

/** value / max in 0..1, or null when either is unknown or max is 0. */
internal fun barFraction(value: Int?, max: Int?): Float? =
    if (value == null || max == null || max <= 0) null else (value.toFloat() / max).coerceIn(0f, 1f)

/** HP is `#4fe08a`, turning `#ffb84f` at 40% or less. */
internal fun hpColor(fraction: Float?): Color = if (fraction != null && fraction <= 0.4f) Kh1Colors.HpLow else Kh1Colors.Hp

@Composable
fun PartyPanel(party: List<PartyMember>, modifier: Modifier = Modifier) {
    Column(
        modifier.kh1Panel().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("PARTY", color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp, letterSpacing = 1.5.sp)
        if (party.isEmpty()) {
            Text("No party data", color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp)
        }
        for (m in party) MemberRow(m)
    }
}

@Composable
private fun MemberRow(m: PartyMember) {
    val hp = barFraction(m.hp, m.maxHp)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                m.name, Modifier.weight(1f, fill = false).padding(end = 6.dp),
                color = Kh1Colors.Text, fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 13.sp,
                lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${m.hp ?: "--"} / ${m.maxHp ?: "--"}",
                color = Kh1Colors.Text, fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 13.sp,
                lineHeight = 15.sp, maxLines = 1,
            )
        }
        Bar(hp, hpColor(hp), 7.dp)
        Bar(barFraction(m.mp, m.maxMp), Kh1Colors.Mp, 5.dp)
    }
}

@Composable
private fun Bar(fraction: Float?, color: Color, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Kh1Colors.Ground)) {
        if (fraction != null && fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}
