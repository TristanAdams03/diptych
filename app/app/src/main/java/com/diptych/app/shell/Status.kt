package com.diptych.app.shell

import com.diptych.app.net.ServerMsg
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class GameStatus { Soon, SetUp, Ready, Update }

data class StatusInfo(val status: GameStatus, val reason: String)

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

/** Parses "major.minor[.patch]" into (major, minor); null when malformed. */
private fun majorMinor(v: String): Pair<Int, Int>? {
    val p = v.trim().split('.')
    if (p.size < 2) return null
    val a = p[0].toIntOrNull() ?: return null
    val b = p[1].toIntOrNull() ?: return null
    return a to b
}

private fun isOlderThanMin(plugin: String): Boolean {
    val have = majorMinor(plugin) ?: return true
    val min = majorMinor(MIN_PLUGIN)!!
    return have.first < min.first || (have.first == min.first && have.second < min.second)
}

fun statusOf(entry: GameEntry, seen: SeenRecord?, live: ServerMsg.Welcome?): StatusInfo {
    if (entry.port == null) return StatusInfo(GameStatus.Soon, "Coming later")
    // A live report outranks any stored one; an unsupported build is never Ready.
    if (live != null && !live.supported) return unsupported(entry)
    if (live != null) {
        return if (isOlderThanMin(live.plugin)) StatusInfo(GameStatus.Update, "Mod update available")
        else StatusInfo(GameStatus.Ready, "Mod connected")
    }
    if (seen != null) {
        if (!seen.supported) return unsupported(entry)
        if (isOlderThanMin(seen.plugin)) return StatusInfo(GameStatus.Update, "Mod update available")
        return StatusInfo(GameStatus.Ready, "Mod seen ${DATE_FMT.format(Instant.ofEpochMilli(seen.atMs))}")
    }
    return StatusInfo(GameStatus.SetUp, "Mod never seen")
}

private fun unsupported(entry: GameEntry) = StatusInfo(
    GameStatus.Update,
    "This ${entry.ribbonName} build isn't supported yet (needs Steam 1.0.0.2)",
)
