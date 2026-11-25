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

    /**
     * Not a wire frame: [SpikeClient] emits it on its events flow when a socket that had opened closes or fails,
     * after that socket's last frame. Whatever the mod said on that connection (its welcome) no longer holds.
     */
    data object Disconnected : ServerMsg
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

/** A `kh1.pressSequence` memory check: met when `(u8 at module base + offset) & mask == value`. [mask] null = 255. */
@Serializable
data class MemCondition(val offset: Long, val value: Int, val mask: Int? = null)

/** A step's `until`: after the release, wait up to [timeoutMs] for the condition, else the sequence ends `timeout`. */
@Serializable
data class SeqUntil(val offset: Long, val value: Int, val mask: Int? = null, val timeoutMs: Int)

/** One `kh1.pressSequence` step. [mask] 0 presses nothing (a plain wait). Skipped (no press, no gap) unless [onlyIf] holds. */
@Serializable
data class SeqStep(
    val byteIndex: Int,
    val mask: Int,
    val holdMs: Int,
    val gapMs: Int,
    val until: SeqUntil? = null,
    val onlyIf: MemCondition? = null,
)

/** `kh1.pressSequence` args. [require] is checked once, when the command arrives (`not safe now` if one fails). */
@Serializable
data class PressSequenceArgs(val require: List<MemCondition> = emptyList(), val steps: List<SeqStep>) {
    /** The args object. Absent optional fields are left out, not sent as null: the mod refuses a null field. */
    fun toJson(): JsonObject = sequenceJson.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        fun fromJson(args: JsonObject): PressSequenceArgs = sequenceJson.decodeFromJsonElement(serializer(), args)
    }
}

/** An ok `kh1.pressSequence` result's data: steps pressed and steps skipped by `onlyIf`. */
@Serializable
data class SequenceData(val steps: Int, val skipped: Int)

/** [SequenceData] of an ok result, or null for a failed one or other data. */
fun ServerMsg.Result.sequenceData(): SequenceData? = if (!ok) null else data?.let {
    try {
        protocolJson.decodeFromJsonElement(SequenceData.serializer(), it)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val sequenceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
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
