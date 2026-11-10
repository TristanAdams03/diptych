package com.diptych.app.shell

import com.diptych.app.net.ServerMsg
import com.diptych.app.service.ConnectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

private val WELCOME = ServerMsg.Welcome("0.1.0", 0, "kh1", "steam-1.0.0.2", true, listOf("spike", "watch"))
private val WELCOME_EGS = ServerMsg.Welcome("0.1.0", 0, "kh1", "egs-1.0.0.10", false, listOf("spike"))

private val LIVE = ConnectionSnapshot(welcome = WELCOME)
private val UNSUPPORTED = ConnectionSnapshot(welcome = WELCOME_EGS)
private val GONE = ConnectionSnapshot(welcome = null)

private val SHELF = Route.Shell(ShellTab.Shelf)
private val DEVICE = Route.Shell(ShellTab.Device)
private val KH1 = Route.Companion("kh1")

class ShellRouteTest {
    // --- Review Focus 3: KH1 connects while the user is elsewhere in the shell ---

    @Test fun aWelcomeOnTheDeviceTabHandsTheScreenToTheCompanion() {
        assertEquals(KH1, nextRoute(DEVICE, DEVICE, LIVE, 0))
    }

    @Test fun backFromTheCompanionReturnsToDevice() {
        val r = nextRoute(DEVICE, DEVICE, LIVE, 0)
        assertEquals(KH1, r)
        // The Back key and the hinge's Device both land where the user was, with "Back to game" while connected.
        assertEquals(Route.Shell(ShellTab.Device, overGame = true), leaveCompanion(DEVICE))
        assertEquals(Route.Shell(ShellTab.Device, overGame = true), hingeRoute(HingeChoice.Device, DEVICE))
        // And the game closing returns there too.
        assertEquals(DEVICE, nextRoute(r, DEVICE, GONE, DISCONNECT_GRACE_MS))
    }

    @Test fun aWelcomeOnTheFoldOutTakesOverAndTheHingeReturnsToTheFoldOut() {
        val foldOut = Route.Shell(ShellTab.Shelf, foldOut = "kh1")
        assertEquals(KH1, nextRoute(foldOut, foldOut, LIVE, 0))
        assertEquals(foldOut.copy(overGame = true), hingeRoute(HingeChoice.Shelf, foldOut))
        assertEquals(foldOut.copy(overGame = true), hingeRoute(HingeChoice.Settings, foldOut))
        assertEquals(foldOut, nextRoute(KH1, foldOut, GONE, DISCONNECT_GRACE_MS))
    }

    @Test fun theHingeShelfFromDeviceOpensTheShelf() {
        assertEquals(SHELF.copy(overGame = true), hingeRoute(HingeChoice.Shelf, DEVICE))
    }

    // --- Review Focus 4: the mod disconnects mid-game ---

    @Test fun aDisconnectShowsWaitingFor9999Ms() {
        assertEquals(KH1, nextRoute(KH1, SHELF, GONE, 9_999))
    }

    @Test fun aDisconnectReturnsToTheShellAt10000Ms() {
        assertEquals(SHELF, nextRoute(KH1, SHELF, GONE, 10_000))
        assertEquals(10_000L, DISCONNECT_GRACE_MS)
    }

    @Test fun reconnectingWithinTenSecondsKeepsTheCompanion() {
        assertEquals(KH1, nextRoute(KH1, SHELF, GONE, 5_000))
        assertEquals(KH1, nextRoute(KH1, SHELF, LIVE, 0))
    }

    @Test fun theReturnedShellNeverCarriesBackToGame() {
        assertEquals(DEVICE, nextRoute(KH1, DEVICE.copy(overGame = true), GONE, 10_000))
    }

    // --- an unsupported build ---

    @Test fun anUnsupportedWelcomeStaysOnTheShelfAndItsRibbonSaysUpdate() {
        assertEquals(SHELF, nextRoute(SHELF, SHELF, UNSUPPORTED, 0))
        val kh1 = CATALOG.first { it.id == "kh1" }
        assertEquals(GameStatus.Update, statusOf(kh1, null, UNSUPPORTED.welcome).status)
    }

    @Test fun anUnsupportedWelcomeWhileWaitingReturnsToTheShelfAtOnce() {
        assertEquals(SHELF, nextRoute(KH1, SHELF, UNSUPPORTED, 0))
    }

    @Test fun anotherGamesWelcomeDoesNotOpenTheKh1Companion() {
        val other = ConnectionSnapshot(welcome = WELCOME.copy(gameId = "kh2"))
        assertEquals(SHELF, nextRoute(SHELF, SHELF, other, 0))
    }

    // --- the hinge button: the user left a live companion ---

    @Test fun aShellOpenedFromTheHingeIsNotPulledBackWhileConnected() {
        val peek = SHELF.copy(overGame = true)
        assertEquals(peek, nextRoute(peek, SHELF, LIVE, 0))
    }

    @Test fun backToGameWaitsOutABlipThenClearsSoTheNextConnectTakesOver() {
        val peek = DEVICE.copy(overGame = true)
        assertEquals(peek, nextRoute(peek, DEVICE, GONE, 9_999))
        val cleared = nextRoute(peek, DEVICE, GONE, 10_000)
        assertEquals(DEVICE, cleared)
        assertEquals(KH1, nextRoute(cleared, DEVICE, LIVE, 0))
    }

    @Test fun backToGameClearsAtOnceWhenTheBuildTurnsOutUnsupported() {
        assertEquals(SHELF, nextRoute(SHELF.copy(overGame = true), SHELF, UNSUPPORTED, 0))
    }

    @Test fun disconnectedTimeCountsFromTheMomentTheWelcomeWentAway() {
        assertEquals(0L, disconnectedFor(live = true, lostAtMs = 1_000, nowMs = 9_000))
        assertEquals(8_000L, disconnectedFor(live = false, lostAtMs = 1_000, nowMs = 9_000))
        assertEquals(Long.MAX_VALUE, disconnectedFor(live = false, lostAtMs = null, nowMs = 9_000))
    }
}
