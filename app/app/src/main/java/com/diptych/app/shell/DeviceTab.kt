package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** GameNative's lowest supported version. */
const val MIN_GAMENATIVE = "1.3.0"

/** How long "Identify screens" keeps the display id on screen. */
const val IDENTIFY_MS = 3_000L

/** True when [version] ("1.3.0", "v1.4", "1.3.0-beta") is at least [min]. Missing parts count as 0; junk is false. */
fun versionAtLeast(version: String?, min: String = MIN_GAMENATIVE): Boolean {
    fun parts(v: String): List<Int>? {
        val nums = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: return null }
        return nums.takeIf { it.isNotEmpty() }
    }
    val have = parts(version ?: return false) ?: return false
    val want = parts(min) ?: return false
    for (i in 0 until maxOf(have.size, want.size)) {
        val a = have.getOrElse(i) { 0 }
        val b = want.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}

enum class CheckState { Ok, Fix, Check }

/** One Device row: what is checked, its state, what was found, and the plain way to fix it. */
data class DeviceCheck(val title: String, val state: CheckState, val found: String, val fix: String?)

fun deviceChecks(env: ShellEnv): List<DeviceCheck> = listOf(
    when {
        env.gameNativeVersion == null -> DeviceCheck(
            "GameNative", CheckState.Fix, "Not found",
            "Install GameNative $MIN_GAMENATIVE or newer, then come back here.",
        )
        !versionAtLeast(env.gameNativeVersion) -> DeviceCheck(
            "GameNative", CheckState.Fix, "Version ${env.gameNativeVersion}, needs $MIN_GAMENATIVE or newer",
            "Update GameNative to $MIN_GAMENATIVE or newer.",
        )
        else -> DeviceCheck("GameNative", CheckState.Ok, "Version ${env.gameNativeVersion}", null)
    },
    when (env.batteryExempt) {
        true -> DeviceCheck("Battery for GameNative", CheckState.Ok, "Unrestricted", null)
        false -> DeviceCheck(
            "Battery for GameNative", CheckState.Fix, "Android may stop it while you play",
            "Settings → Apps → GameNative → Battery → Unrestricted",
        )
        null -> DeviceCheck("Battery for GameNative", CheckState.Check, "Can't tell yet", null)
    },
    DeviceCheck(
        "Focus Lock", CheckState.Check, "Check this",
        "Not needed on your Thor so far: touches didn't steal the controller in the TTW test",
    ),
)

/** Device: one row per thing that can go wrong, each with a plain way to fix it, and the screens. */
@Composable
fun DeviceTab(env: ShellEnv, modifier: Modifier = Modifier, initiallyIdentifying: Boolean = false) {
    var identifying by remember { mutableStateOf(initiallyIdentifying) }
    LaunchedEffect(identifying) {
        if (identifying && !initiallyIdentifying) {
            delay(IDENTIFY_MS)
            identifying = false
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Before you play", color = ShellColors.Ink, style = displayText(20.sp))
            deviceChecks(env).forEach { CheckRow(it) }
            ScreensRow(env, onIdentify = { identifying = true })
        }
        if (identifying) {
            Box(Modifier.fillMaxSize().background(ShellColors.Ground.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This screen", color = ShellColors.Muted, style = bodyText(14.sp))
                    Text("${env.companionDisplayId}", color = ShellColors.Brass, style = displayText(120.sp))
                }
            }
        }
    }
}

@Composable
private fun CheckRow(c: DeviceCheck) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ShellColors.Surface).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val color = when (c.state) { CheckState.Ok -> ShellColors.Ok; CheckState.Fix -> ShellColors.Amber; CheckState.Check -> ShellColors.Brass }
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.title, color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
            Text(c.found, color = color, style = bodyText(13.sp))
            if (c.fix != null) Text(c.fix, color = ShellColors.Muted, style = bodyText(13.sp))
        }
    }
}

@Composable
private fun ScreensRow(env: ShellEnv, onIdentify: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ShellColors.Surface).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Screens", color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
            Text(
                "Diptych is on display ${env.companionDisplayId}" + (if (env.companionSize.isNotEmpty()) ", ${env.companionSize}" else ""),
                color = ShellColors.Muted, style = bodyText(13.sp),
            )
            Text("The game runs on display 0, the top screen.", color = ShellColors.Muted, style = bodyText(13.sp))
        }
        ShellButton("Identify screens", primary = false, onClick = onIdentify)
    }
}
