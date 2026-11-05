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
data class StoryStep(
    val below: Int,
    val hint: String,
    val nextRoom: Int? = null,
    val lockedRooms: List<Int> = emptyList(),
)

@Serializable
data class StoryDef(
    val offset: Long,
    val steps: List<StoryStep> = emptyList(),
)

@Serializable
data class WorldDef(
    val id: Int,
    val name: String,
    val rooms: List<RoomDef> = emptyList(),
    val edges: List<List<Int>> = emptyList(),
    val chests: List<Chest> = emptyList(),
    val story: StoryDef? = null,
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
data class SoraStatsDef(
    val offset: Long,
    val length: Int,
    val levelAt: Int = 0,
    val apAt: Int = 5,
    val strengthAt: Int = 6,
    val defenseAt: Int = 7,
    val itemCountAt: Int = 33,
    val itemsAt: Int = 34,
    val itemsMax: Int = 8,
    val weaponAt: Int = 50,
    val magicUnlockAt: Int = 112,
)

@Serializable
data class SpellDef(
    val key: String,
    val bit: Int,
    val mpCost: Int,
    val needsTarget: Boolean = false,
    val tiers: List<String> = emptyList(),
)

@Serializable
data class MagicConfig(
    val levelsOffset: Long,
    val spells: List<SpellDef> = emptyList(),
)

@Serializable
data class ItemDef(
    val id: Int,
    val name: String,
    val needsTarget: Boolean = false,
)

@Serializable
data class InventoryConfig(
    val offset: Long,
    val length: Int,
    val items: List<ItemDef> = emptyList(),
    val weapons: Map<String, String> = emptyMap(),
)

@Serializable
data class PartyConfig(
    val battleBlock: BattleBlock,
    val slots: SlotsDef,
    val names: Map<String, String>,
    val soraStats: SoraStatsDef? = null,
    val magic: MagicConfig? = null,
    val inventory: InventoryConfig? = null,
) {
    /** Bytes read per battle member: up to the end of the last field. */
    val memberSpan: Int
        get() = battleBlock.fields.let { listOf(it.hp, it.maxHp, it.mp, it.maxMp) }.maxOf { it.at + it.width }

    /** The party watch: one span per battle member, optional Sora stats block, and slots..inventory span. */
    val watch: List<Region>
        get() {
            val members = (0 until battleBlock.members).map {
                Region(battleBlock.offset + it.toLong() * battleBlock.stride, memberSpan)
            }
            val stats = listOfNotNull(soraStats?.let { Region(it.offset, it.length) })
            val tailEnd = maxOf(
                slots.offset + slots.length,
                inventory?.let { it.offset + it.length } ?: 0L,
                magic?.let { it.levelsOffset + it.spells.size } ?: 0L,
            )
            return members + stats + Region(slots.offset, (tailEnd - slots.offset).toInt())
        }
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

const val MAX_WATCH_REGIONS = 8
const val MAX_WATCH_REGION_BYTES = 256
const val MAX_WATCH_TOTAL_BYTES = 1024

/** True when [regions] fit the mod's watch limits: at most 8 regions, each 1..256 bytes, 1024 bytes in total. */
fun withinWatchLimits(regions: List<Region>): Boolean =
    regions.size <= MAX_WATCH_REGIONS && regions.all { it.length in 1..MAX_WATCH_REGION_BYTES } &&
        regions.sumOf { it.length } <= MAX_WATCH_TOTAL_BYTES

/** Party regions plus [world]'s watch, or null when the combination is over the limits. */
fun combinedWatch(party: PartyConfig, world: WorldDef?): List<Region>? =
    (party.watch + world?.watch.orEmpty()).takeIf(::withinWatchLimits)
