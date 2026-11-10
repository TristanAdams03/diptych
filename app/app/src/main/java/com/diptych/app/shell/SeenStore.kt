package com.diptych.app.shell

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SeenRecord(val plugin: String, val gameBuild: String, val supported: Boolean, val atMs: Long)

/** Last mod handshake seen per game id, stored as JSON in SharedPreferences. */
class SeenStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(id: String): SeenRecord? {
        val raw = prefs.getString(key(id), null) ?: return null
        return try {
            json.decodeFromString(SeenRecord.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    fun put(id: String, r: SeenRecord) {
        prefs.edit().putString(key(id), json.encodeToString(SeenRecord.serializer(), r)).apply()
    }

    private fun key(id: String) = "seen.$id"
}
