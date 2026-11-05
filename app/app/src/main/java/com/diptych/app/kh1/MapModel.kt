package com.diptych.app.kh1

import com.diptych.app.net.SpikeData

data class RoomView(val id: Int, val label: String, val col: Int, val row: Int, val opened: Int?, val total: Int, val here: Boolean)

data class MapView(
    val worldName: String,
    val roomName: String,
    val rooms: List<RoomView>,
    val edges: List<Pair<Int, Int>>,
    val chestsOpened: Int?,
    val chestsTotal: Int,
    val party: List<PartyMember>,
)

fun buildMapView(data: Kh1Data, state: SpikeData): MapView {
    val regions = state.regions.orEmpty()
    val world = data.world(state.world)
    val party = decodeParty(data.party, regions)
    val worldName = world?.name ?: "World ${state.world ?: "?"}"
    if (world == null) return MapView(worldName, roomLabel(null, state.room), emptyList(), emptyList(), 0, 0, party)

    val states = world.chests.map { it to chestOpened(it, regions) }
    fun count(list: List<Boolean?>): Int? = if (list.any { it == null }) null else list.count { it == true }
    val rooms = world.rooms.map { r ->
        val mine = states.filter { it.first.room == r.id }.map { it.second }
        RoomView(r.id, r.name, r.col, r.row, count(mine), mine.size, r.id == state.room)
    }
    return MapView(
        worldName,
        roomLabel(world.rooms.firstOrNull { it.id == state.room }?.name, state.room),
        rooms,
        world.edges.filter { it.size == 2 }.map { it[0] to it[1] },
        count(states.map { it.second }),
        world.chests.size,
        party,
    )
}

private fun roomLabel(name: String?, id: Int?) = name ?: "Room ${id ?: "?"}"
