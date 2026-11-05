package com.diptych.app.kh1

import com.diptych.app.net.ServerMsg
import com.diptych.app.net.SpikeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The slice of the client the session needs; implemented by SpikeClient. */
interface Kh1Link {
    /** Sends `kh1.watch`; returns the command id, or null when not connected. */
    fun watch(regions: List<Region>): String?
    val events: Flow<ServerMsg>
}

/**
 * Keeps the mod's watch matched to the current world and turns state frames into a [MapView].
 * The mod forgets the watch per connection, so it is re-sent after every `welcome`.
 * Frame regions whose offsets are not in the latest watch sent are dropped (they belong to a previous watch),
 * so an old world's chests never show under the new world. Collection is sequential on [scope].
 */
class Kh1Session(
    private val link: Kh1Link,
    private val data: Kh1Data,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val _view = MutableStateFlow<MapView?>(null)
    val view: StateFlow<MapView?> = _view.asStateFlow()

    private var watchable = false
    // All state below is touched only from the single sequential collector.
    private var watchedWorld: Int? = null
    private var hasWatch = false // a watch for [watchedWorld] was accepted by the link
    private var sentOffsets: Set<Long> = emptySet()
    private var lastState: SpikeData? = null

    init {
        scope.launch { link.events.collect(::onMsg) }
    }

    private fun onMsg(m: ServerMsg) {
        when (m) {
            is ServerMsg.Welcome -> {
                watchable = m.supported && "watch" in m.features
                sentOffsets = emptySet()
                hasWatch = false
                if (watchable) sendWatch(lastState?.world) else publish()
            }
            is ServerMsg.State -> {
                if (m.topic != "kh1.spike") return
                val w = m.data.world
                lastState = m.data
                // A failed send leaves hasWatch false (or the old world), so the next frame retries.
                if (watchable && w != null && (!hasWatch || w != watchedWorld)) sendWatch(w) else publish()
            }
            else -> Unit
        }
    }

    private fun sendWatch(w: Int?) {
        val def = data.world(w)
        val regions = combinedWatch(data.party, def) ?: run {
            log("kh1 watch for world $w exceeds the watch limits; watching party only")
            data.party.watch
        }
        if (link.watch(regions) != null) {
            sentOffsets = regions.map { it.offset }.toSet()
            watchedWorld = w
            hasWatch = true
        }
        publish()
    }

    private fun publish() {
        val s = lastState ?: return
        val kept = s.regions?.filter { it.offset in sentOffsets }
        _view.value = buildMapView(data, s.copy(regions = kept))
    }
}
