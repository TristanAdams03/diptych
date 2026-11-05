package com.diptych.app.kh1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diptych.app.R

/** Style tokens from the approved mockup (docs/superpowers/specs/mockups/kh1-style/Main.dc.html). */
object Kh1Colors {
    val Ground = Color(0xFF0B1433)
    val Panel = Color(0xFF15235A)
    val Rim = Color(0xFF9FC0FF)
    val Gold = Color(0xFFFFD166)
    val You = Color(0xFF3F8CFF)
    val YouCount = Color(0xFFFFF3CC)
    val Text = Color(0xFFF2F6FF)
    val Muted = Color(0xFFA9B9E6)
    val Dim = Color(0xFF8A9BC9)
    val TabText = Color(0xFFDCE6FF)
    val Hp = Color(0xFF4FE08A)
    val HpLow = Color(0xFFFFB84F)
    val Mp = Color(0xFF5AA8FF)
    val Room = Color(0xFF0E1A45)
    val RoomRim = Color(0xFF7F9DE0)
    val Edge = Color(0xFF6F8FD8)
    val KeyText = Color(0xFFC3CFF0)
    val KeyRim = Color(0xFF2C3F80)
}

val PanelShape = RoundedCornerShape(14.dp)

/** The standard panel: `#15235a` with a 2 dp `#9fc0ff` rim, radius 14 dp. */
fun Modifier.kh1Panel(): Modifier =
    background(Kh1Colors.Panel, PanelShape).border(2.dp, Kh1Colors.Rim, PanelShape)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Fredoka (variable, OFL): headings and tabs. */
val Fredoka = FontFamily(variable(R.font.fredoka, 500), variable(R.font.fredoka, 600), variable(R.font.fredoka, 700))

/** Nunito (variable, OFL): body text. */
val Nunito = FontFamily(variable(R.font.nunito, 500), variable(R.font.nunito, 700), variable(R.font.nunito, 800))
