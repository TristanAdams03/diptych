package com.diptych.app.ttw

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.diptych.app.Fixtures
import com.diptych.app.LoopStatus
import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.shell.CompanionFrame
import org.junit.Rule
import org.junit.Test

/** The Thor bottom screen: 1240x1080 px at 360 dpi, same config as MapScreenshotTest. */
class TtwScreenshotTest {
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

    private val state = (parseTtw(Fixtures.load("ttw/state_spike.json")) as TtwMsg.State).data
    private val welcome = parseTtw(Fixtures.load("ttw/welcome.json")) as TtwMsg.Welcome
    private val live = TtwUi(connected = true, welcome = welcome, state = state, loop = LoopStatus.Alive, lastResult = "c1 ok {\"formId\":\"0007843F\",\"name\":\"Lincoln's Repeater\"}")

    private fun snap(ui: TtwUi, connection: ConnectionLabel = ConnectionLabel.Connected) {
        paparazzi.snapshot {
            CompanionFrame(onChoose = {}, onDiagnostics = {}) { TtwScreen(ui, connection, onEquip = {}, onDumpWorld = {}) }
        }
    }

    /** Megaton, in the game's own (amber) Pip-Boy colour. */
    @Test fun capitalWasteland() = snap(live)

    /** No colours reported: stock green. An interior cell in the Mojave, hardcore, the loop stalled. */
    @Test fun mojaveInteriorStalled() = snap(
        live.copy(
            state = state.copy(
                colors = null, bInDCWasteland = 0, bInNVWasteland = 1, worldspace = null, cell = "Lucky 38 Presidential Suite",
                interior = true, hardcore = true, hp = 61.0,
            ),
            loop = LoopStatus.Stalled,
            lastResult = null,
        ),
    )

    @Test fun waiting() = snap(TtwUi(), ConnectionLabel.Waiting)

    @Test fun ttwNotLoaded() = snap(live, ConnectionLabel.Unsupported)

    @Test fun noSave() = snap(live.copy(state = state.copy(saveLoaded = false, hp = null, hpMax = null)))

    @Test fun diagnostics() {
        paparazzi.snapshot {
            TtwDiagnostics(live, listOf("12:00:00.000 connected", "12:00:00.050 welcome plugin=0.0.1-spike"), focusOn = true, {}, {}, {}, {})
        }
    }
}
