package com.diptych.app.shell

import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.net.ServerMsg
import com.diptych.app.service.ConnectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private val KH1 = CATALOG.first { it.id == "kh1" }
private val SEEN = SeenRecord("0.1.0", "steam-1.0.0.2", true, Instant.parse("2026-11-08T20:15:00Z").toEpochMilli())
private val WELCOME = ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true)

class ShellLogicTest {
    // --- Shelf ---

    @Test fun theStatusLineCountsReadyGames() {
        assertEquals("1 of 3 ready", readyLine(shelfGames(mapOf("kh1" to SEEN), null)))
        assertEquals("0 of 3 ready", readyLine(shelfGames(emptyMap(), null)))
    }

    @Test fun theStatusLineSaysWhenGameNativeIsMissing() {
        assertEquals("GameNative 1.3.0", gameNativeLine("1.3.0"))
        assertEquals("GameNative not found", gameNativeLine(null))
        assertEquals("Focus Lock: check", FOCUS_LOCK_LINE)
    }

    @Test fun theMainButtonFollowsTheStatus() {
        assertEquals(MainAction.Play, mainActionFor(GameStatus.Ready))
        assertEquals(MainAction.SetUp, mainActionFor(GameStatus.SetUp))
        assertEquals(MainAction.Update, mainActionFor(GameStatus.Update))
        assertNull(mainActionFor(GameStatus.Soon))
        assertEquals(listOf("Play", "Set up", "Update"), MainAction.entries.map { it.label })
    }

    @Test fun aLiveWelcomeOnlyCountsForTheGameItNames() {
        val games = shelfGames(emptyMap(), WELCOME).associate { it.entry.id to it.status.status }
        assertEquals(GameStatus.Ready, games["kh1"])
        assertEquals(GameStatus.Soon, games["kh2"])
        assertEquals(GameStatus.Soon, games["ttw"])
    }

    // --- Fold-out ---

    @Test fun beforeYouPlayIsUntickedUntilTheModIsSeen() {
        val items = beforeYouPlay(KH1, null)
        assertEquals(
            listOf(
                "Mod zip downloaded",
                "In GameNative: KH 1.5+2.5 → Mods → Import",
                "Add `WINEDLLOVERRIDES` = `dinput8=n,b` in Environment",
                "Start KH1 once; Diptych ticks this off",
            ),
            items.map { it.text },
        )
        assertTrue(items.none { it.done })
        assertEquals("Get it from the release page", items[0].linkLabel)
        assertEquals(KH1.modAsset, items[0].linkUrl)
    }

    @Test fun aSeenModTicksEverythingButTheZip() {
        val items = beforeYouPlay(KH1, SEEN)
        assertFalse("M1 never has the zip itself", items[0].done)
        assertTrue(items.drop(1).all { it.done })
    }

    @Test fun modCheckShowsTheSeenPluginAndBuild() {
        assertEquals(listOf("Plugin 0.1.0 · build steam-1.0.0.2", "Last seen 2026-11-08"), modCheckLines(SEEN))
        assertTrue(modCheckLines(null).single().contains("not seen yet"))
    }

    @Test fun codeRunsAreMarked() {
        val s = withCode("Add `A` = `b` x", androidx.compose.ui.text.SpanStyle())
        assertEquals("Add A = b x", s.text)
        assertEquals(listOf("A", "b"), s.spanStyles.map { s.text.substring(it.start, it.end) })
    }

    @Test fun theFoldOutTagNamesTheStatus() {
        assertEquals("NEEDS SETUP", foldOutTag(GameStatus.SetUp))
        assertEquals("✓ READY", foldOutTag(GameStatus.Ready))
        assertEquals("UPDATE", foldOutTag(GameStatus.Update))
    }

    // --- Companion banner ---

    @Test fun theCompanionBannerReadsTheLiveWelcome() {
        assertEquals(ConnectionLabel.Waiting, companionLabel(ConnectionSnapshot()))
        assertEquals(ConnectionLabel.Connected, companionLabel(ConnectionSnapshot(welcome = WELCOME)))
        assertEquals(ConnectionLabel.Unsupported, companionLabel(ConnectionSnapshot(welcome = WELCOME.copy(supported = false))))
    }

    // --- OLED care ---

    @Test fun pixelShiftWalksA2DpSquareOncePer8Minutes() {
        assertEquals(0 to 0, pixelShift(0))
        assertEquals(8, SHIFT_CYCLE.distinct().size)
        assertTrue(SHIFT_CYCLE.all { (x, y) -> x in 0..2 && y in 0..2 })
        // Neighbouring positions are 1 dp apart, so no minute jumps across the square.
        (SHIFT_CYCLE + SHIFT_CYCLE.first()).zipWithNext().forEach { (a, b) ->
            assertEquals(1, Math.abs(a.first - b.first) + Math.abs(a.second - b.second))
        }
        assertEquals(pixelShift(3), pixelShift(3 + 8L * 1000))
        assertEquals(60_000L, SHIFT_PERIOD_MS)
    }

    @Test fun theShellDimsAfterFiveIdleMinutesWithoutAGame() {
        assertFalse(idleDimmed(connected = false, lastActivityMs = 0, nowMs = 299_999))
        assertTrue(idleDimmed(connected = false, lastActivityMs = 0, nowMs = 300_000))
        assertFalse(idleDimmed(connected = true, lastActivityMs = 0, nowMs = 3_000_000))
        assertEquals(0.9f, IDLE_DIM_ALPHA)
    }
}
