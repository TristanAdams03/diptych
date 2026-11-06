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

    /** Party regions that are never dropped: one span per battle member (HP/MP) and the slots..inventory span. */
    val essentialWatch: List<Region>
        get() {
            val members = (0 until battleBlock.members).map {
                Region(battleBlock.offset + it.toLong() * battleBlock.stride, memberSpan)
            }
            val tailEnd = maxOf(
                slots.offset + slots.length,
                inventory?.let { it.offset + it.length } ?: 0L,
                magic?.let { it.levelsOffset + it.spells.size } ?: 0L,
            )
            return members + Region(slots.offset, (tailEnd - slots.offset).toInt())
        }

    /** Info-only party regions, lowest priority first: dropped before any chest when the watch is over budget. */
    val extraWatch: List<Region>
        get() = listOfNotNull(soraStats?.let { Region(it.offset, it.length) })

    /** The full party watch: battle members, the Sora stats block (Magic & Items, Party stats), slots..inventory. */
    val watch: List<Region>
        get() = essentialWatch.let { it.dropLast(1) + extraWatch + it.last() }
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

/** Watch regions kept free. 0: Sora's stats beat a spare slot; M3 raises the mod's region limit instead. */
const val WATCH_REGIONS_RESERVED = 0

/** Two regions at most this far apart may be merged into one span. */
const val WATCH_MERGE_GAP = 64

/** The regions to send, plus what was merged or dropped to fit (for the log). */
data class WatchPlan(val regions: List<Region>, val merged: Int = 0, val dropped: List<Region> = emptyList(), val chestsFit: Boolean = true)

/**
 * Merges the closest pair of regions (gap ≤ [WATCH_MERGE_GAP], merged span ≤ 256 bytes, total ≤ 1024) until at most
 * [target] remain or no pair can merge. Returns [regions] unchanged when already within [target], else sorted by
 * offset; and the number of merges.
 */
internal fun coalesce(regions: List<Region>, target: Int): Pair<List<Region>, Int> {
    if (regions.size <= target) return regions to 0
    val list = regions.sortedBy { it.offset }.toMutableList()
    var merges = 0
    while (list.size > target) {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until list.lastIndex) {
            val a = list[i]
            val b = list[i + 1]
            val gap = b.offset - (a.offset + a.length)
            val end = maxOf(a.offset + a.length, b.offset + b.length)
            val span = end - a.offset
            val total = list.sumOf { it.length.toLong() } - a.length - b.length + span
            if (gap <= WATCH_MERGE_GAP && span <= MAX_WATCH_REGION_BYTES && total <= MAX_WATCH_TOTAL_BYTES && gap < bestGap) {
                best = i
                bestGap = gap
            }
        }
        if (best < 0) break
        val a = list[best]
        val b = list.removeAt(best + 1)
        list[best] = Region(a.offset, (maxOf(a.offset + a.length, b.offset + b.length) - a.offset).toInt())
        merges++
    }
    return list to merges
}

/**
 * Party plus [world]'s watch in at most `8 - [reserve]` regions and 1024 bytes. Chests and the story byte are never
 * dropped: first close regions are merged, then the party's info-only extras (Sora stats: magic and items) go, lowest
 * priority first. Only when the chests alone cannot fit (no shipped world) is the world left out, with chestsFit false.
 */
fun planWatch(party: PartyConfig, world: WorldDef?, reserve: Int = WATCH_REGIONS_RESERVED): WatchPlan {
    val target = MAX_WATCH_REGIONS - reserve
    val worldWatch = world?.watch.orEmpty()
    val extras = party.extraWatch
    for (dropCount in 0..extras.size) {
        val kept = extras.drop(dropCount)
        val all = party.essentialWatch.let { it.dropLast(1) + kept + it.last() } + worldWatch
        val (regions, merges) = coalesce(all, target)
        if (regions.size <= target && withinWatchLimits(regions)) {
            return WatchPlan(regions, merges, extras.take(dropCount))
        }
    }
    if (world == null) return WatchPlan(coalesce(party.essentialWatch, target).first, dropped = extras)
    return planWatch(party, null, reserve).let { it.copy(dropped = it.dropped + worldWatch, chestsFit = false) }
}

/** The planned regions for [world] (see [planWatch]). */
fun combinedWatch(party: PartyConfig, world: WorldDef?): List<Region> = planWatch(party, world).regions
