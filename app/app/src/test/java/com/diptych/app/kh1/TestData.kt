package com.diptych.app.kh1

import com.diptych.app.net.RegionHex
import com.diptych.app.net.SpikeData
import java.io.File

object TestData {
    val data: Kh1Data by lazy { loadKh1Data { File("src/main/assets/kh1/$it").inputStream() } }

    const val SORA = "39000000390000000500000005000000"
    const val DONALD = "2a0000002a0000000800000008000000"
    const val GOOFY = "3f0000003f0000000400000004000000"
    const val PARTY_BASE = 47565900L
    const val SORA_STATS = 48141156L
    const val SLOTS = 48142319L
    const val HB_STORY = 48146542L

    fun partyRegions(slots: String? = "0102") = listOf(
        RegionHex(PARTY_BASE, SORA), RegionHex(PARTY_BASE + 256, DONALD), RegionHex(PARTY_BASE + 512, GOOFY),
        RegionHex(SLOTS, slots),
    )

    /** Realistic Sora stats + magic tiers + inventory counts matching Magic.dc.html. */
    fun soraStatusRegions(slots: String = "0102"): List<RegionHex> {
        val stats = ByteArray(113).also {
            it[0] = 42 // Level
            it[5] = 38 // AP
            it[6] = 34 // Strength
            it[7] = 32 // Defense
            it[33] = 4 // 4 item slots
            it[34] = 1 // Potion equipped
            it[35] = 2 // Hi-Potion equipped
            it[50] = 91 // Divine Rose
            it[112] = 0x3F // Fire, Blizzard, Thunder, Cure, Gravity, Stop unlocked
        }
        val tail = ByteArray(155).also {
            it[0] = slots.substring(0, 2).toInt(16).toByte()
            it[1] = slots.substring(2, 4).toInt(16).toByte()
            // magic levels at +3..+9 (0x2DE97F2 - 0x2DE97EF = 3)
            it[3 + 0] = 3 // Firaga
            it[3 + 1] = 2 // Blizzara
            it[3 + 2] = 2 // Thundara
            it[3 + 3] = 2 // Cura
            it[3 + 4] = 2 // Gravira
            it[3 + 5] = 2 // Stopra
            // inventory at +10..+154 (0x2DE97F9 - 0x2DE97EF = 10)
            it[10 + 1] = 6 // Potion x6
            it[10 + 2] = 3 // Hi-Potion x3
            it[10 + 3] = 4 // Ether x4
            it[10 + 4] = 1 // Elixir x1
            it[10 + 142] = 2 // Tent x2
        }
        return listOf(
            RegionHex(SORA_STATS, stats.joinToString("") { "%02x".format(it) }),
            RegionHex(SLOTS, tail.joinToString("") { "%02x".format(it) }),
        )
    }

    /** Hollow Bastion (15): byte +20 = 0x08, +24 = 0x05, rest zero; story byte = 60 = 0x3C (Riku I beaten; next: Castle Chapel). */
    fun hbRegions(storyByte: Int = 60): List<RegionHex> {
        val b = ByteArray(45).also { it[20] = 0x08; it[24] = 0x05 }
        return listOf(
            RegionHex(48143040L, b.joinToString("") { "%02x".format(it) }),
            RegionHex(HB_STORY, "%02x".format(storyByte)),
            RegionHex(48149007L, "00"),
        )
    }

    fun state(world: Int?, room: Int?, regions: List<RegionHex>?, seq: Long = 1) =
        SpikeData(seq = seq, ms = seq * 100, world = world, room = room, regions = regions)
}
