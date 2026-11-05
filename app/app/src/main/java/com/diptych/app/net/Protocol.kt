package com.diptych.app.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Diptych KH1 protocol v0 (protocol/kh1/PROTOCOL.md). */
const val PROTOCOL_VERSION = 0

val protocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

/** `state` topic `kh1.spike` data. Every field but [seq] and [ms] is null when unsupported or its reader faulted. */
@Serializable
data class SpikeData(
    val seq: Long,
    val ms: Long,
    val world: Int? = null,
    val room: Int? = null,
    val worldAlt: Int? = null,
    val soraHp: Int? = null,
    val soraHpMax: Int? = null,
    val inMenu: Boolean? = null,
    val inCutscene: Boolean? = null,
    val inGummi: Boolean? = null,
    val inputBytes: String? = null,
    /** Present only while a `kh1.watch` is set: one entry per watched region, in watch order. */
    val regions: List<RegionHex>? = null,
)

/** One watched region's bytes as hex, or null when the read failed. */
@Serializable
data class RegionHex(val offset: Long, val hex: String?)

/** Server -> client messages. */
sealed interface ServerMsg {
    @Serializable
    data class Welcome(
        val plugin: String,
        val protocol: Int,
        val gameId: String,
        val gameBuild: String,
        val supported: Boolean,
        val features: List<String> = emptyList(),
    ) : ServerMsg

    @Serializable
    data class State(val topic: String, val data: SpikeData) : ServerMsg

    @Serializable
    data class Result(
        val id: String = "", // `bad command` for a command without a string id carries id:""
        val ok: Boolean,
        val error: String? = null,
        val data: JsonObject? = null,
    ) : ServerMsg

    @Serializable
    data class Pong(val t: Long) : ServerMsg

    @Serializable
    data class Error(val message: String) : ServerMsg

    /** Unknown `type`, or a frame that is not a valid protocol object. */
    data class Unknown(val type: String?, val raw: String) : ServerMsg
}

/** Parse one server text frame. Never throws: anything unrecognised becomes [ServerMsg.Unknown]. */
fun parseServer(text: String): ServerMsg {
    val obj = try {
        protocolJson.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return ServerMsg.Unknown(null, text)
    val type = try {
        obj["type"]?.jsonPrimitive?.contentOrNull
    } catch (e: IllegalArgumentException) {
        null
    }
    return try {
        when (type) {
            "welcome" -> protocolJson.decodeFromJsonElement(ServerMsg.Welcome.serializer(), obj)
            "state" -> protocolJson.decodeFromJsonElement(ServerMsg.State.serializer(), obj)
            "result" -> protocolJson.decodeFromJsonElement(ServerMsg.Result.serializer(), obj)
            "pong" -> protocolJson.decodeFromJsonElement(ServerMsg.Pong.serializer(), obj)
            "error" -> protocolJson.decodeFromJsonElement(ServerMsg.Error.serializer(), obj)
            else -> ServerMsg.Unknown(type, text)
        }
    } catch (e: SerializationException) {
        ServerMsg.Unknown(type, text)
    } catch (e: IllegalArgumentException) {
        ServerMsg.Unknown(type, text)
    }
}

/** Client -> server messages. */
@OptIn(ExperimentalSerializationApi::class)
sealed interface ClientMsg {
    @Serializable
    data class Hello(val client: String, val protocol: Int = PROTOCOL_VERSION) : ClientMsg {
        @EncodeDefault @SerialName("type") val type: String = "hello"
    }

    @Serializable
    data class Subscribe(val topics: List<String>, val rateHz: Int) : ClientMsg {
        @EncodeDefault @SerialName("type") val type: String = "subscribe"
    }

    @Serializable
    data class Command(val id: String, val name: String, val args: JsonObject = JsonObject(emptyMap())) : ClientMsg {
        @EncodeDefault @SerialName("type") val type: String = "command"
    }

    @Serializable
    data class Ping(val t: Long) : ClientMsg {
        @EncodeDefault @SerialName("type") val type: String = "ping"
    }
}

fun encodeClient(msg: ClientMsg): String = when (msg) {
    is ClientMsg.Hello -> protocolJson.encodeToString(ClientMsg.Hello.serializer(), msg)
    is ClientMsg.Subscribe -> protocolJson.encodeToString(ClientMsg.Subscribe.serializer(), msg)
    is ClientMsg.Command -> protocolJson.encodeToString(ClientMsg.Command.serializer(), msg)
    is ClientMsg.Ping -> protocolJson.encodeToString(ClientMsg.Ping.serializer(), msg)
}
