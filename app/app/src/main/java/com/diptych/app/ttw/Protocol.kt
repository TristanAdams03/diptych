package com.diptych.app.ttw

import com.diptych.app.net.ServerMsg
import com.diptych.app.net.protocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Catalog id of Tale of Two Wastelands. */
const val TTW_ID = "ttw"

/** The xNVSE plugin's port (protocol/ttw/PROTOCOL.md). KH1's mod is on 7970. */
const val TTW_PORT = 7960
const val TTW_URL = "ws://127.0.0.1:$TTW_PORT"
const val TTW_TOPIC = "spike"
const val CMD_EQUIP = "spike.equipFirstWeapon"
const val CMD_DUMP_WORLD = "spike.dumpWorld"

/** The game's HUD colours, each `[r,g,b]`. */
@Serializable
data class TtwColors(
    val hudMain: List<Int>,
    val hudAlt: List<Int>,
    val terminal: List<Int>,
    val pipboy: List<Int>,
    val menu: List<Int>,
) {
    fun named(): List<Pair<String, List<Int>>> =
        listOf("hudMain" to hudMain, "hudAlt" to hudAlt, "terminal" to terminal, "pipboy" to pipboy, "menu" to menu)
}

/** `state` topic `spike` data. Everything after [saveLoaded] is null with no save loaded or when its reader faulted. */
@Serializable
data class TtwState(
    val frame: Long,
    val ms: Long,
    val saveLoaded: Boolean,
    val hp: Double? = null,
    val hpMax: Double? = null,
    val hardcore: Boolean? = null,
    val bInDCWasteland: Int? = null,
    val bInNVWasteland: Int? = null,
    val worldspace: String? = null,
    val cell: String? = null,
    val interior: Boolean? = null,
    val colors: TtwColors? = null,
)

/** Plugin -> app frames. The client -> plugin frames are the shared [com.diptych.app.net.ClientMsg]. */
sealed interface TtwMsg {
    @Serializable
    data class Welcome(
        val plugin: String,
        val protocol: Int,
        val game: String,
        val xnvse: String,
        val ttw: Boolean,
        val features: List<String> = emptyList(),
    ) : TtwMsg {
        /**
         * The welcome the connection engine and the shell read, in the shape every game's link reports.
         * `supported` is [ttw]: the plugin says false until DeferredInit has seen TaleOfTwoWastelands.esm, and for
         * good in a New Vegas without TTW.
         */
        fun toLink(): ServerMsg.Welcome =
            ServerMsg.Welcome(plugin, protocol, TTW_ID, "$game · xNVSE $xnvse", ttw, features)
    }

    @Serializable
    data class State(val topic: String, val data: TtwState) : TtwMsg

    @Serializable
    data class Result(
        val id: String = "", // `bad command` for a command without a string id carries no usable id
        val ok: Boolean,
        val error: String? = null,
        val data: JsonObject? = null,
    ) : TtwMsg

    @Serializable
    data class Pong(val t: Long) : TtwMsg

    @Serializable
    data class Error(val message: String) : TtwMsg

    /** Unknown `type`, or a frame that is not a valid protocol object. */
    data class Unknown(val type: String?, val raw: String) : TtwMsg
}

/** Parse one plugin text frame. Never throws: anything unrecognised becomes [TtwMsg.Unknown]. */
fun parseTtw(text: String): TtwMsg {
    val obj = try {
        protocolJson.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return TtwMsg.Unknown(null, text)
    val type = try {
        obj["type"]?.jsonPrimitive?.contentOrNull
    } catch (e: IllegalArgumentException) {
        null
    }
    return try {
        when (type) {
            "welcome" -> protocolJson.decodeFromJsonElement(TtwMsg.Welcome.serializer(), obj)
            "state" -> protocolJson.decodeFromJsonElement(TtwMsg.State.serializer(), obj)
            "result" -> protocolJson.decodeFromJsonElement(TtwMsg.Result.serializer(), obj)
            "pong" -> protocolJson.decodeFromJsonElement(TtwMsg.Pong.serializer(), obj)
            "error" -> protocolJson.decodeFromJsonElement(TtwMsg.Error.serializer(), obj)
            else -> TtwMsg.Unknown(type, text)
        }
    } catch (e: SerializationException) {
        TtwMsg.Unknown(type, text)
    } catch (e: IllegalArgumentException) {
        TtwMsg.Unknown(type, text)
    }
}
