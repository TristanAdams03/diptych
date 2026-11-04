package com.diptych.app

enum class LoopStatus(val label: String) { Alive("ALIVE"), Stalled("STALLED"), SocketDead("SOCKET DEAD") }

/**
 * Tells a frozen game main loop apart from a dead socket.
 * `state` frames come from the main loop; `pong` comes from the plugin's network thread.
 * - no pong for [socketDeadMs] -> SocketDead
 * - pongs flowing but no state for [thresholdMs] -> Stalled
 * - otherwise Alive
 */
class StallDetector(val thresholdMs: Long = 1000, val socketDeadMs: Long = 5000) {
    private var lastState: Long? = null
    private var lastPong: Long? = null

    /** New connection: forget old frames and start the pong clock now. */
    @Synchronized fun reset(nowMs: Long) {
        lastState = null
        lastPong = nowMs
    }

    @Synchronized fun onState(nowMs: Long) { lastState = nowMs }

    @Synchronized fun onPong(nowMs: Long) { lastPong = nowMs }

    @Synchronized fun status(nowMs: Long): LoopStatus {
        val pong = lastPong ?: return LoopStatus.SocketDead
        if (nowMs - pong > socketDeadMs) return LoopStatus.SocketDead
        val state = lastState ?: return LoopStatus.Stalled
        return if (nowMs - state > thresholdMs) LoopStatus.Stalled else LoopStatus.Alive
    }
}

/** Game frames per second from `frame` counter deltas over wall time (sliding window). */
class FpsMeter(private val windowMs: Long = 2000) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // frame, wallMs

    @Synchronized fun reset() = samples.clear()

    /** Returns fps over the current window, or null until two samples span some time. */
    @Synchronized fun onFrame(frame: Long, nowMs: Long): Double? {
        samples.lastOrNull()?.let { (f, _) -> if (frame < f) samples.clear() }
        samples.addLast(frame to nowMs)
        while (samples.size > 2 && nowMs - samples[1].second >= windowMs) samples.removeFirst()
        val (f0, t0) = samples.first()
        val dt = nowMs - t0
        if (samples.size < 2 || dt <= 0) return null
        return (frame - f0) * 1000.0 / dt
    }
}
