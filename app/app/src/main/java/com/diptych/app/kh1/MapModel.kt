package com.diptych.app.kh1

import com.diptych.app.net.SpikeData

data class RoomView(
    val id: Int,
    val label: String,
    val col: Int,
    val row: Int,
    val opened: Int?,
    val total: Int,
    val here: Boolean,
    val next: Boolean = false,
    val locked: Boolean = false,
)

data class WorldSummary(
    val id: Int,
    val name: String,
    val roomsTotal: Int,
    val chestsTotal: Int,
    val chestsOpened: Int? = null,
    val current: Boolean = false,
)

data class MapView(
    val worldName: String,
    val roomName: String,
    val rooms: List<RoomView>,
    val edges: List<Pair<Int, Int>>,
    val chestsOpened: Int?,
    val chestsTotal: Int,
    val party: List<PartyMember>,
    val nextHint: String? = null,
    val sora: SoraStatus = SoraStatus(),
    /** Status only until M3: true when every safety flag reads false and Sora's MP is known. Nothing sends presses. */
    val canAct: Boolean = false,
    val busyReason: String? = null,
    val worlds: List<WorldSummary> = emptyList(),
)

fun buildMapView(data: Kh1Data, state: SpikeData): MapView {
    val regions = state.regions.orEmpty()
    val world = data.world(state.world)
    val party = decodeParty(data.party, regions)
    val sora = decodeSoraStatus(data.party, regions)
    val busyReason = busyReason(state, party.firstOrNull()?.mp)
    val canAct = busyReason == null
    val worldName = world?.name ?: "World ${state.world ?: "?"}"
    if (world == null) {
        val summaries = data.worlds.map { w -> WorldSummary(w.id, w.name, w.rooms.size, w.chests.size) }
        return MapView(
            worldName = worldName,
            roomName = roomLabel(null, state.room),
            rooms = emptyList(),
            edges = emptyList(),
            chestsOpened = 0,
            chestsTotal = 0,
            party = party,
            sora = sora,
            canAct = false,
            busyReason = busyReason,
            worlds = summaries,
        )
    }

    val step = decodeStoryStep(world.story, regions)
    val lockedSet = step?.lockedRooms.orEmpty().toSet()
    val states = world.chests.map { it to chestOpened(it, regions) }
    fun count(list: List<Boolean?>): Int? = if (list.any { it == null }) null else list.count { it == true }
    val rooms = world.rooms.map { r ->
        val mine = states.filter { it.first.room == r.id }.map { it.second }
        val here = r.id == state.room
        val next = !here && step?.nextRoom == r.id
        val locked = !here && !next && r.id in lockedSet
        RoomView(
            id = r.id,
            label = r.name,
            col = r.col,
            row = r.row,
            opened = count(mine),
            total = mine.size,
            here = here,
            next = next,
            locked = locked,
        )
    }
    val openedCount = count(states.map { it.second })
    val summaries = data.worlds.map { w ->
        val isCurrent = w.id == world.id
        WorldSummary(
            id = w.id,
            name = w.name,
            roomsTotal = w.rooms.size,
            chestsTotal = w.chests.size,
            chestsOpened = if (isCurrent) openedCount else null,
            current = isCurrent,
        )
    }
    return MapView(
        worldName = worldName,
        roomName = roomLabel(world.rooms.firstOrNull { it.id == state.room }?.name, state.room),
        rooms = rooms,
        edges = world.edges.filter { it.size == 2 }.map { it[0] to it[1] },
        chestsOpened = openedCount,
        chestsTotal = world.chests.size,
        party = party,
        nextHint = step?.hint,
        sora = sora,
        canAct = canAct,
        busyReason = busyReason,
        worlds = summaries,
    )
}

const val BUSY_GAME = "Game busy (cutscene, dialogue or loading)"
const val BUSY_MENU = "Menu open"
const val BUSY_GUMMI = "Gummi Ship"
const val BUSY_UNKNOWN = "Can't tell if the game is busy"
const val BUSY_NO_MP = "Can't read Sora's MP"

/**
 * Why actions would be unsafe now, or null when every safety flag reads false and MP is known.
 * Unknown (null) counts as unsafe. inCutscene is also set while talking and loading, so it is never called a cutscene.
 */
fun busyReason(state: SpikeData, soraMp: Int?): String? = when {
    state.inCutscene == true -> BUSY_GAME
    state.inMenu == true -> BUSY_MENU
    state.inGummi == true -> BUSY_GUMMI
    state.inCutscene == null || state.inMenu == null || state.inGummi == null || state.world == null -> BUSY_UNKNOWN
    soraMp == null -> BUSY_NO_MP
    else -> null
}

private fun roomLabel(name: String?, id: Int?) = name ?: "Room ${id ?: "?"}"
