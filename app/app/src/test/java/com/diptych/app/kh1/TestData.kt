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
    const val SLOTS = 48142319L

    fun partyRegions(slots: String? = "0102") = listOf(
        RegionHex(PARTY_BASE, SORA), RegionHex(PARTY_BASE + 256, DONALD), RegionHex(PARTY_BASE + 512, GOOFY),
        RegionHex(SLOTS, slots),
    )

    /** Hollow Bastion (15): byte +20 = 0x08, +24 = 0x05, rest zero. */
    fun hbRegions(): List<RegionHex> {
        val b = ByteArray(45).also { it[20] = 0x08; it[24] = 0x05 }
        return listOf(RegionHex(48143040L, b.joinToString("") { "%02x".format(it) }), RegionHex(48149007L, "00"))
    }

    fun state(world: Int?, room: Int?, regions: List<RegionHex>?, seq: Long = 1) =
        SpikeData(seq = seq, ms = seq * 100, world = world, room = room, regions = regions)
}
