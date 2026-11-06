package com.diptych.app.kh1

import com.diptych.app.net.RegionHex

data class PartyMember(val name: String, val hp: Int?, val maxHp: Int?, val mp: Int?, val maxMp: Int?)

private fun hexBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    return ByteArray(hex.length / 2) { i ->
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        ((hi shl 4) or lo).toByte()
    }
}

/** [n] bytes at [offset] from one region that fully covers them; null if uncovered or that region's hex is null. */
internal fun readBytes(regions: List<RegionHex>, offset: Long, n: Int): ByteArray? {
    for (r in regions) {
        val bytes = r.hex?.let(::hexBytes) ?: continue
        if (offset >= r.offset && offset + n <= r.offset + bytes.size) {
            return bytes.copyOfRange((offset - r.offset).toInt(), (offset - r.offset).toInt() + n)
        }
    }
    return null
}

private fun u32(b: ByteArray, at: Int, width: Int): Int? =
    if (width != 4 || at + 4 > b.size) null
    else ((b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
        ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24))
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() // unsigned on the wire; never negative

/**
 * Member 0 is always Sora; member 1 and 2 are named by slot byte 0 and 1. A slot byte equal to `empty` omits the member;
 * an id missing from `names` is "Guest". Unreadable slot bytes omit members 1 and 2 (unknown, not empty).
 */
fun decodeParty(party: PartyConfig, regions: List<RegionHex>): List<PartyMember> {
    val slots = readBytes(regions, party.slots.offset, party.slots.length)
    val f = party.battleBlock.fields
    val out = mutableListOf<PartyMember>()
    for (i in 0 until party.battleBlock.members) {
        val name = if (i == 0) party.names["0"] ?: "Sora" else {
            val id = slots?.getOrNull(i - 1)?.toInt()?.and(0xFF) ?: continue
            if (id == party.slots.empty) continue
            party.names[id.toString()] ?: "Guest"
        }
        val b = readBytes(regions, party.battleBlock.offset + i.toLong() * party.battleBlock.stride, party.memberSpan)
        out += PartyMember(
            name,
            b?.let { u32(it, f.hp.at, f.hp.width) }, b?.let { u32(it, f.maxHp.at, f.maxHp.width) },
            b?.let { u32(it, f.mp.at, f.mp.width) }, b?.let { u32(it, f.maxMp.at, f.maxMp.width) },
        )
    }
    return out
}

/** Null when the chest's byte is not covered by a region with data. */
fun chestOpened(chest: Chest, regions: List<RegionHex>): Boolean? {
    val v = (readBytes(regions, chest.offset, 1) ?: return null)[0].toInt() and 0xFF
    return when {
        chest.bit != null -> (v shr chest.bit) and 1 == 1
        chest.atLeast != null -> v >= chest.atLeast
        else -> null
    }
}

/** First [StoryStep] whose `below` exceeds the world's story progress byte, or null when absent/unreadable/completed. */
fun decodeStoryStep(story: StoryDef?, regions: List<RegionHex>): StoryStep? {
    if (story == null || story.steps.isEmpty()) return null
    val v = (readBytes(regions, story.offset, 1) ?: return null)[0].toInt() and 0xFF
    return story.steps.firstOrNull { v < it.below }
}

data class LearnedSpell(
    val key: String,
    val name: String,
    val mpCost: Int,
    val needsTarget: Boolean,
)

data class StockItem(
    val id: Int,
    val name: String,
    val count: Int,
    val equipped: Boolean,
    val needsTarget: Boolean,
)

data class SoraStatus(
    val level: Int? = null,
    val ap: Int? = null,
    val strength: Int? = null,
    val defense: Int? = null,
    val weapon: String? = null,
    val spells: List<LearnedSpell> = emptyList(),
    val items: List<StockItem> = emptyList(),
    /** False when the stats block is not watched or unreadable: spells and items are unknown, not empty. */
    val known: Boolean = false,
)

private fun ByteArray.u8(at: Int): Int? = if (at in indices) this[at].toInt() and 0xFF else null

/** Decodes Sora's stats, equipped weapon, unlocked magic tiers, and the items in his equipped slots. */
fun decodeSoraStatus(party: PartyConfig, regions: List<RegionHex>): SoraStatus {
    val statsDef = party.soraStats ?: return SoraStatus()
    val stats = readBytes(regions, statsDef.offset, statsDef.length)
    val level = stats?.u8(statsDef.levelAt)?.takeIf { it > 0 }
    val ap = stats?.u8(statsDef.apAt)
    val strength = stats?.u8(statsDef.strengthAt)
    val defense = stats?.u8(statsDef.defenseAt)
    val weaponId = stats?.u8(statsDef.weaponAt)
    val weapon = weaponId?.let { party.inventory?.weapons?.get(it.toString()) }

    val unlockMask = stats?.u8(statsDef.magicUnlockAt)
    val spells = party.magic?.let { magic ->
        val mask = unlockMask ?: return@let emptyList()
        val levels = readBytes(regions, magic.levelsOffset, magic.spells.size)
        magic.spells.mapNotNull { spell ->
            if (((mask shr spell.bit) and 1) == 0 || spell.tiers.isEmpty()) return@mapNotNull null
            val rawTier = levels?.u8(spell.bit) ?: 1
            val tierIdx = (rawTier - 1).coerceIn(0, spell.tiers.lastIndex)
            LearnedSpell(spell.key, spell.tiers[tierIdx], spell.mpCost, spell.needsTarget)
        }
    }.orEmpty()

    val equippedCounts = mutableMapOf<Int, Int>()
    if (stats != null) {
        val slotCount = (stats.u8(statsDef.itemCountAt) ?: 0).coerceIn(0, statsDef.itemsMax)
        for (i in 0 until slotCount) {
            val id = stats.u8(statsDef.itemsAt + i) ?: continue
            if (id in 1..254) equippedCounts[id] = (equippedCounts[id] ?: 0) + 1
        }
    }

    val items = party.inventory?.let { inv ->
        val stock = readBytes(regions, inv.offset, inv.length)
        inv.items.mapNotNull { def ->
            val stockCount = stock?.u8(def.id) ?: 0
            val eqCount = equippedCounts[def.id] ?: 0
            val total = maxOf(stockCount, eqCount)
            // Only Sora's equipped slots (spec §7): stocked-only and save-point items (Tent etc.) are left out.
            if (eqCount <= 0 || total <= 0) null
            else StockItem(def.id, def.name, total, eqCount > 0, def.needsTarget)
        }
    }.orEmpty()

    return SoraStatus(
        level = level,
        ap = ap,
        strength = strength,
        defense = defense,
        weapon = weapon,
        spells = spells,
        items = items,
        known = stats != null,
    )
}
