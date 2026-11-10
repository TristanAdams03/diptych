package com.diptych.app.shell

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val SHELL_PREFS = "shell"
const val KEY_ARTWORK = "steamArtwork"

/** Steam artwork on or off; on until the user switches it off. */
fun SharedPreferences.artworkEnabled(): Boolean = getBoolean(KEY_ARTWORK, true)

fun SharedPreferences.setArtworkEnabled(on: Boolean) = edit().putBoolean(KEY_ARTWORK, on).apply()

/** The bundled OFL fonts: label and the license file under assets/licenses. */
val LICENSES = listOf(
    "Fraunces" to "OFL-Fraunces.txt",
    "Manrope" to "OFL-Manrope.txt",
    "Fredoka" to "OFL-Fredoka.txt",
    "Nunito" to "OFL-Nunito.txt",
)

/** Settings: Steam artwork, the app version, and the licenses. A scrim and a panel over the shell. */
@Composable
fun SettingsSheet(
    artworkEnabled: Boolean,
    onArtwork: (Boolean) -> Unit,
    appVersion: String,
    onClose: () -> Unit,
    readLicense: (String) -> String = rememberLicenseReader(),
    initiallyOpenLicense: String? = null,
) {
    var openLicense by remember { mutableStateOf(initiallyOpenLicense) }
    Box(
        Modifier.fillMaxSize().background(ShellColors.Ground.copy(alpha = 0.8f)).clickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(ShellColors.Raised)
                .clickable(enabled = false) {} // taps on the panel don't reach the scrim
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", Modifier.weight(1f), color = ShellColors.Ink, style = displayText(20.sp))
                ShellButton("Done", primary = true, onClick = onClose)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Steam artwork", color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
                    Text(
                        if (artworkEnabled) "Covers load from Steam's servers." else "Off: nothing is fetched from the internet.",
                        color = ShellColors.Muted, style = bodyText(13.sp),
                    )
                }
                Switch(
                    checked = artworkEnabled, onCheckedChange = onArtwork,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ShellColors.OnBrass, checkedTrackColor = ShellColors.Brass,
                        uncheckedThumbColor = ShellColors.Muted, uncheckedTrackColor = ShellColors.Surface,
                        uncheckedBorderColor = ShellColors.Line,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Diptych", Modifier.weight(1f), color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
                Text("Version $appVersion", color = ShellColors.Muted, style = bodyText(13.sp))
            }
            Text("Licenses", color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
            Text("Fonts bundled with Diptych, all under the SIL Open Font License.", color = ShellColors.Muted, style = bodyText(13.sp))
            LICENSES.forEach { (name, file) ->
                val open = openLicense == file
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ShellColors.Surface)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { openLicense = if (open) null else file }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.weight(1f), color = ShellColors.Ink, style = bodyText(14.sp))
                        Text(if (open) "Hide" else "Open", color = ShellColors.Brass, style = bodyText(13.sp))
                    }
                    if (open) {
                        Text(
                            readLicense(file),
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = ShellColors.Muted,
                            style = bodyText(12.sp).copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberLicenseReader(): (String) -> String {
    val ctx = LocalContext.current
    return remember(ctx) { { file: String -> readLicenseAsset(ctx, file) } }
}

private fun readLicenseAsset(ctx: Context, file: String): String =
    try { ctx.assets.open("licenses/$file").bufferedReader().use { it.readText() } } catch (e: java.io.IOException) { "License text not found." }
