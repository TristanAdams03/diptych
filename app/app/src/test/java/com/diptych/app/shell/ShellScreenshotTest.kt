package com.diptych.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
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
}
