package com.diptych.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** The Thor bottom screen: 1240x1080 px at 360 dpi (about 551x480 dp), same config as MapScreenshotTest. */
class ShellScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1240,
            screenHeight = 1080,
            xdpi = 360,
            ydpi = 360,
            density = Density.create(360),
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        showSystemUi = false,
        useDeviceResolution = true,
    )

    @Test fun topBar() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(ShellColors.Ground)) {
                TopBar(active = ShellTab.Shelf, onSelect = {}, onSettings = {})
            }
        }
    }

    private fun entry(id: String) = CATALOG.first { it.id == id }

    @Test fun coverFallback() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(ShellColors.Ground)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    CoverTile(entry("kh1"), selected = true, statusInfo = StatusInfo(GameStatus.SetUp, ""), artworkEnabled = false)
                    CoverTile(entry("kh2"), selected = false, statusInfo = StatusInfo(GameStatus.Soon, ""), artworkEnabled = false)
                    CoverTile(entry("kh1"), selected = false, statusInfo = StatusInfo(GameStatus.Ready, ""), artworkEnabled = false)
                    CoverTile(entry("kh1"), selected = false, statusInfo = StatusInfo(GameStatus.Update, ""), artworkEnabled = false)
                }
            }
        }
    }

    @Test fun coverTtwSplit() {
        paparazzi.snapshot {
            val offline = offlineImageLoader(LocalContext.current)
            Box(Modifier.fillMaxSize().background(ShellColors.Ground)) {
                CoverTile(
                    entry("ttw"), selected = false, statusInfo = StatusInfo(GameStatus.Ready, ""),
                    artworkEnabled = true, modifier = Modifier.padding(16.dp), imageLoader = offline,
                )
            }
        }
    }
}
