package com.diptych.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.diptych.app.kh1.TestData
import com.diptych.app.kh1.buildMapView
import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.kh1.ui.Kh1Tab
import com.diptych.app.kh1.ui.MapScreen
import org.junit.Rule
import org.junit.Test
import java.time.Instant

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

    // --- Task 5: the Shelf, the fold-out and the hinge pull-down (artwork through the offline loader) ---

    private val seenKh1 = SeenRecord("0.1.0", "steam-1.0.0.2", true, Instant.parse("2026-11-08T20:15:00Z").toEpochMilli())

    @Composable
    private fun ShellAt(route: Route.Shell, seen: Map<String, SeenRecord>) {
        val env = ShellEnv(seen, gameNativeVersion = "1.3.0", imageLoader = offlineImageLoader(LocalContext.current))
        ShellScreen(
            route = route,
            env = env,
            games = shelfGames(seen, live = null),
            selectedId = "kh1",
            onSelect = {},
            onRoute = {},
            actions = ShellActions(),
        )
    }

    @Test fun shelfKh1Selected() {
        paparazzi.snapshot { ShellAt(Route.Shell(ShellTab.Shelf), mapOf("kh1" to seenKh1)) }
    }

    @Test fun foldOutKh1SetUp() {
        paparazzi.snapshot { ShellAt(Route.Shell(ShellTab.Shelf, foldOut = "kh1"), emptyMap()) }
    }

    @Test fun foldOutKh1Ready() {
        paparazzi.snapshot { ShellAt(Route.Shell(ShellTab.Shelf, foldOut = "kh1"), mapOf("kh1" to seenKh1)) }
    }

    @Test fun hingePullDown() {
        val state = TestData.state(3, 2, TestData.partyRegions()).copy(inMenu = false, inCutscene = false, inGummi = false)
        val view = buildMapView(TestData.data, state)
        paparazzi.snapshot {
            CompanionFrame(onChoose = {}, onDiagnostics = {}, initiallyOpen = true) {
                MapScreen(view, ConnectionLabel.Connected, onReport = {})
            }
        }
    }

    /**
     * The closed hinge tab over KH1's tightest header: Magic & Items (also Party, Journal) has a one-line row whose
     * world name starts 24.4 dp down. The tab's brass edge must sit above the "T" of TRAVERSE, touching no glyph.
     */
    @Test fun companionWithHinge() {
        val state = TestData.state(3, 2, TestData.partyRegions()).copy(inMenu = false, inCutscene = false, inGummi = false)
        val view = buildMapView(TestData.data, state)
        paparazzi.snapshot {
            CompanionFrame(onChoose = {}, onDiagnostics = {}) {
                MapScreen(view, ConnectionLabel.Connected, onReport = {}, initialTab = Kh1Tab.MagicItems)
            }
        }
    }
}
