package com.aichatsdk.models

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okio.Buffer

/**
 * v4 protocol envelope. Mirrors iOS `ChatEvent`.
 *
 * JSON shape:
 * { "type": "domain.action", "id": "req_xxx" (optional), "payload": { ... }, "ts": 1711... (optional) }
 */
data class ChatEvent(
    val type: String,
    val id: String? = null,
    val payload: Map<String, AnyValue> = emptyMap(),
    val ts: Long? = null,
) {
    fun string(key: String): String? = payload[key]?.stringValue
    fun long(key: String): Long? = payload[key]?.intValue
    fun bool(key: String): Boolean? = payload[key]?.boolValue
    fun obj(key: String): Map<String, AnyValue>? = payload[key]?.objectValue

    companion object {
        fun text(type: String, id: String? = null, payload: Map<String, AnyValue> = emptyMap()): ChatEvent =
            ChatEvent(type, id, payload, System.currentTimeMillis())
    }
}

object ChatEventCodec {
    private val anyAdapter = AnyValueJsonAdapter()

    fun toJson(event: ChatEvent): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("type").value(event.type)
        event.id?.let { writer.name("id").value(it) }
        writer.name("payload")
        anyAdapter.toJson(writer, AnyValue.Obj(event.payload))
        event.ts?.let { writer.name("ts").value(it) }
        writer.endObject()
        return buffer.readUtf8()
    }

    fun fromJson(json: String): ChatEvent {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        var type = ""
        var id: String? = null
        var ts: Long? = null
        var payload: Map<String, AnyValue> = emptyMap()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = reader.nextString()
                "id" -> id = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Any>(); null } else reader.nextString()
                "ts" -> ts = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Any>(); null } else reader.nextLong()
                "payload" -> {
                    val v = anyAdapter.fromJson(reader)
                    payload = (v as? AnyValue.Obj)?.entries ?: emptyMap()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ChatEvent(type, id, payload, ts)
    }
}

/** Standard v4 event type strings — keep in lockstep with docs/protocol-v4.md */
object EventType {
    const val SESSION_INIT = "session.init"
    const val SESSION_READY = "session.ready"
    const val SESSION_CLOSE = "session.close"

    const val SPEECH_START = "speech.start"
    const val SPEECH_STARTED = "speech.started"
    const val SPEECH_STOP = "speech.stop"
    const val SPEECH_STOPPED = "speech.stopped"

    const val TEXT_SEND = "text.send"
    const val TTS_SYNTHESIZE = "tts.synthesize"

    const val TURN_STARTED = "turn.started"
    const val TURN_DONE = "turn.done"
    const val TURN_CANCELLED = "turn.cancelled"

    const val ASR_PARTIAL = "asr.partial"
    const val ASR_FINAL = "asr.final"

    const val AI_THINKING = "ai.thinking"
    const val AI_TEXT_DELTA = "ai.text.delta"
    const val AI_TEXT_DONE = "ai.text.done"

    const val TTS_START = "tts.start"
    const val TTS_DONE = "tts.done"

    const val ERROR = "error"
}
