package com.diptych.app

import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.net.ServerMsg
import com.diptych.app.net.SpikeData

/** Everything the spike screen shows. */
data class SpikeUi(
    val connected: Boolean = false,
    val welcome: ServerMsg.Welcome? = null,
    val state: SpikeData? = null,
    val loop: LoopStatus = LoopStatus.SocketDead,
    /** `seq` delta per second: the fps-equivalent for state frames (~rateHz while the game loop runs). */
    val seqRate: Double? = null,
    val rttMs: Long? = null,
    val lastResult: String? = null,
    /** Lines from the last successful `kh1.log`; null until fetched. */
    val modLog: List<String>? = null,
)

/** The map screen's connection banner state: Waiting until connected with a welcome; Unsupported on other builds. */
fun connectionLabel(ui: SpikeUi): ConnectionLabel {
    val w = ui.welcome
    return when {
        !ui.connected || w == null -> ConnectionLabel.Waiting
        !w.supported -> ConnectionLabel.Unsupported
        else -> ConnectionLabel.Connected
    }
}
