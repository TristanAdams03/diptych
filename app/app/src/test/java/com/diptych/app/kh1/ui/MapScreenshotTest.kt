package com.diptych.app.kh1.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.diptych.app.kh1.TestData
import com.diptych.app.kh1.buildMapView
import com.diptych.app.net.RegionHex
import org.junit.Rule
import org.junit.Test

/** The Thor bottom screen: 1240×1080 px at 360 dpi (≈551×480 dp). Fixtures go through buildMapView on the real assets. */
class MapScreenshotTest {
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

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Sora full, Donald low (16 / 42, under 40%), Goofy full (the live game's party), plus Sora stats/magic/inventory. */
    private fun party() = listOf(
        RegionHex(TestData.PARTY_BASE, TestData.SORA),
        RegionHex(TestData.PARTY_BASE + 256, "100000002a0000000300000008000000"),
        RegionHex(TestData.PARTY_BASE + 512, TestData.GOOFY),
    ) + TestData.soraStatusRegions(slots = "0102")

    /** Every safety flag reads false: the status line says "Game ready". */
    private fun safe(world: Int?, room: Int?, regions: List<RegionHex>) =
        TestData.state(world, room, regions).copy(inMenu = false, inCutscene = false, inGummi = false)

    @Test fun hollowBastionLibrary() {
        // Task 4's fixture (room 6, bytes +20/+24) plus Great Crest's two chests (+12 bits 0,1) so a cleared room shows.
        val b = ByteArray(45).also { it[12] = 0x03; it[20] = 0x08; it[24] = 0x05 }
        val regions = listOf(
            RegionHex(48143040L, hex(b)),
            RegionHex(TestData.HB_STORY, "3c"),
            RegionHex(48149007L, "00"),
        ) + party()
        snap(buildMapView(TestData.data, safe(15, 5, regions)))
    }

    @Test fun magicAndItemsTab() {
        val b = ByteArray(45).also { it[12] = 0x03; it[20] = 0x08; it[24] = 0x05 }
        val regions = listOf(
            RegionHex(48143040L, hex(b)),
            RegionHex(TestData.HB_STORY, "3c"),
            RegionHex(48149007L, "00"),
        ) + party()
        val view = buildMapView(TestData.data, safe(15, 5, regions))
        paparazzi.snapshot { MapScreen(view, ConnectionLabel.Connected, onReport = {}, initialTab = Kh1Tab.MagicItems) }
    }

    @Test fun endOfTheWorldPanned() {
        // Room 21 (World Terminus (Atlantica)) is col 6, row 5: the far bottom-right of a 7×6 grid.
        val b = ByteArray(25).also { it[0] = 0x0f; it[4] = 0x0f; it[8] = 0x07; it[20] = 0x01 }
        val regions = listOf(RegionHex(48143088L, hex(b)), RegionHex(48146543L, "05")) + party()
        // inCutscene set: the busy reason must show next to the Next hint.
        snap(buildMapView(TestData.data, safe(16, 21, regions).copy(inCutscene = true)))
    }

    @Test fun unknownWorld() {
        snap(buildMapView(TestData.data, TestData.state(99, 2, party())))
    }

    @Test fun unsupported() {
        paparazzi.snapshot { MapScreen(null, ConnectionLabel.Unsupported, onReport = {}) }
    }

    @Test fun waiting() {
        paparazzi.snapshot { MapScreen(null, ConnectionLabel.Waiting, onReport = {}) }
    }

    private fun snap(view: com.diptych.app.kh1.MapView) {
        paparazzi.snapshot { MapScreen(view, ConnectionLabel.Connected, onReport = {}) }
    }
}
