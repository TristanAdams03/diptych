package com.diptych.app.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.diptych.app.R

/** Warm charcoal and brass (spec §6; docs/superpowers/specs/mockups/diptych-v4.html). Never navy in the shell. */
object ShellColors {
    val Ground = Color(0xFF151412)
    val Surface = Color(0xFF1E1C19)
    val Raised = Color(0xFF26231F)
    val Ink = Color(0xFFF2ECE1)
    val Muted = Color(0xFFA39A8B)
    val Brass = Color(0xFFC9A45C)
    val Line = Color(0xFF3A352E)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Display = Fraunces 700 (variable, OFL); body = Manrope (variable, OFL). */
object ShellType {
    val display = FontFamily(variable(R.font.fraunces, 500), variable(R.font.fraunces, 700))
    val body = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 600), variable(R.font.manrope, 700))
}
