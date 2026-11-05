package com.diptych.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallDetectorTest {
    @Test fun pongWithoutStateIsStalledThenStateIsAlive() {
        val d = StallDetector()
        d.onState(0)
        d.onPong(1500)
        assertEquals(LoopStatus.Stalled, d.status(1600))
        d.onState(1550)
        assertEquals(LoopStatus.Alive, d.status(1600))
    }

    @Test fun noPongForFiveSecondsIsSocketDead() {
        val d = StallDetector()
        d.onPong(0)
        d.onState(0)
        assertEquals(LoopStatus.SocketDead, d.status(5200))
    }

    @Test fun nothingYetIsSocketDead() = assertEquals(LoopStatus.SocketDead, StallDetector().status(0))

    @Test fun resetStartsPongClock() {
        val d = StallDetector()
        d.reset(10_000)
        assertEquals(LoopStatus.Stalled, d.status(10_100))
        d.onState(10_200)
        assertEquals(LoopStatus.Alive, d.status(10_300))
        assertEquals(LoopStatus.SocketDead, d.status(15_200))
    }

    @Test fun fpsFromFrameDeltas() {
        val f = FpsMeter()
        assertNull(f.onFrame(0, 0))
        f.onFrame(60, 1000)
        assertEquals(60.0, f.onFrame(120, 2000)!!, 0.01)
    }

    @Test fun fpsResetsOnFrameGoingBackwards() {
        val f = FpsMeter()
        f.onFrame(1000, 0)
        f.onFrame(1060, 1000)
        assertNull(f.onFrame(5, 1100)) // new game process / reconnect: counter restarted
    }
}
