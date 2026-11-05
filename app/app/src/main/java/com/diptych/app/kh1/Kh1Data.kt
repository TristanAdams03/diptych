package com.diptych.app.kh1

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
data class Region(val offset: Long, val length: Int)

@Serializable
data class RoomDef(val id: Int, val name: String, val col: Int, val row: Int)

/** A chest is opened when `(byte >> bit) & 1` (bit) or `byte >= atLeast`; exactly one of the two is set. */
@Serializable
data class Chest(
    val name: String,
    val room: Int,
    val offset: Long,
    val bit: Int? = null,
    val atLeast: Int? = null,
    val contents: String? = null,
)

@Serializable
data class WorldDef(
    val id: Int,
    val name: String,
    val rooms: List<RoomDef> = emptyList(),
    val edges: List<List<Int>> = emptyList(),
    val chests: List<Chest> = emptyList(),
    val watch: List<Region> = emptyList(),
)

@Serializable
data class WorldsFile(val worlds: List<WorldDef>)

@Serializable
data class FieldDef(val at: Int, val width: Int)

@Serializable
data class PartyFields(val hp: FieldDef, val maxHp: FieldDef, val mp: FieldDef, val maxMp: FieldDef)

@Serializable
data class BattleBlock(val offset: Long, val stride: Int, val members: Int, val fields: PartyFields)

@Serializable
data class SlotsDef(val offset: Long, val length: Int, val empty: Int)

@Serializable
data class PartyConfig(val battleBlock: BattleBlock, val slots: SlotsDef, val names: Map<String, String>) {
    /** Bytes read per battle member: up to the end of the last field. */
    val memberSpan: Int
        get() = battleBlock.fields.let { listOf(it.hp, it.maxHp, it.mp, it.maxMp) }.maxOf { it.at + it.width }

    /** The party watch: one span per battle member, then the slot bytes. */
    val watch: List<Region>
        get() = (0 until battleBlock.members).map { Region(battleBlock.offset + it.toLong() * battleBlock.stride, memberSpan) } +
            Region(slots.offset, slots.length)
}

data class Kh1Data(val worlds: List<WorldDef>, val party: PartyConfig) {
    fun world(id: Int?): WorldDef? = worlds.firstOrNull { it.id == id }
}

private val dataJson = Json { ignoreUnknownKeys = true }

/** [open] maps "worlds.json" / "party.json" to a stream. */
fun loadKh1Data(open: (String) -> InputStream): Kh1Data {
    fun text(n: String) = open(n).bufferedReader().use { it.readText() }
    return Kh1Data(
        dataJson.decodeFromString(WorldsFile.serializer(), text("worlds.json")).worlds,
        dataJson.decodeFromString(PartyConfig.serializer(), text("party.json")),
    )
}

fun loadKh1Data(assets: AssetManager): Kh1Data = loadKh1Data { assets.open("kh1/$it") }
