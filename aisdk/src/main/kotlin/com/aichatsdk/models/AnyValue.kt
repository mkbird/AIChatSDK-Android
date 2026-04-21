package com.aichatsdk.models

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

/**
 * Type-erased JSON value used as the dynamic payload type for [ChatEvent].
 * Mirrors iOS `AnyCodableValue` so v4 protocol payloads can be parsed without
 * pre-defined schemas.
 */
sealed class AnyValue {
    object Null : AnyValue()
    data class Bool(val value: Boolean) : AnyValue()
    data class IntV(val value: Long) : AnyValue()
    data class DoubleV(val value: Double) : AnyValue()
    data class Str(val value: String) : AnyValue()
    data class Arr(val items: List<AnyValue>) : AnyValue()
    data class Obj(val entries: Map<String, AnyValue>) : AnyValue()

    val stringValue: String? get() = (this as? Str)?.value
    val intValue: Long? get() = when (this) {
        is IntV -> value
        is DoubleV -> value.toLong()
        else -> null
    }
    val doubleValue: Double? get() = when (this) {
        is DoubleV -> value
        is IntV -> value.toDouble()
        else -> null
    }
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<AnyValue>? get() = (this as? Arr)?.items
    val objectValue: Map<String, AnyValue>? get() = (this as? Obj)?.entries

    fun toRaw(): Any? = when (this) {
        is Null -> null
        is Bool -> value
        is IntV -> value
        is DoubleV -> value
        is Str -> value
        is Arr -> items.map { it.toRaw() }
        is Obj -> entries.mapValues { it.value.toRaw() }
    }

    companion object {
        fun of(raw: Any?): AnyValue = when (raw) {
            null -> Null
            is Boolean -> Bool(raw)
            is Int -> IntV(raw.toLong())
            is Long -> IntV(raw)
            is Float -> DoubleV(raw.toDouble())
            is Double -> DoubleV(raw)
            is Number -> DoubleV(raw.toDouble())
            is String -> Str(raw)
            is List<*> -> Arr(raw.map { of(it) })
            is Map<*, *> -> Obj(raw.entries.associate { (k, v) -> k.toString() to of(v) })
            else -> Str(raw.toString())
        }

        fun str(v: String) = Str(v)
        fun int(v: Long) = IntV(v)
        fun int(v: Int) = IntV(v.toLong())
        fun bool(v: Boolean) = Bool(v)
        fun obj(map: Map<String, AnyValue>) = Obj(map)
        fun arr(list: List<AnyValue>) = Arr(list)
    }
}

class AnyValueJsonAdapter : JsonAdapter<AnyValue>() {
    override fun fromJson(reader: JsonReader): AnyValue {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> { reader.nextNull<Any>(); AnyValue.Null }
            JsonReader.Token.BOOLEAN -> AnyValue.Bool(reader.nextBoolean())
            JsonReader.Token.NUMBER -> {
                // Try long first to preserve integer fidelity
                val s = reader.nextString()
                s.toLongOrNull()?.let { AnyValue.IntV(it) }
                    ?: AnyValue.DoubleV(s.toDouble())
            }
            JsonReader.Token.STRING -> AnyValue.Str(reader.nextString())
            JsonReader.Token.BEGIN_ARRAY -> {
                val items = mutableListOf<AnyValue>()
                reader.beginArray()
                while (reader.hasNext()) items += fromJson(reader)
                reader.endArray()
                AnyValue.Arr(items)
            }
            JsonReader.Token.BEGIN_OBJECT -> {
                val map = LinkedHashMap<String, AnyValue>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    map[name] = fromJson(reader)
                }
                reader.endObject()
                AnyValue.Obj(map)
            }
            else -> { reader.skipValue(); AnyValue.Null }
        }
    }

    override fun toJson(writer: JsonWriter, value: AnyValue?) {
        when (value) {
            null, AnyValue.Null -> writer.nullValue()
            is AnyValue.Bool -> writer.value(value.value)
            is AnyValue.IntV -> writer.value(value.value)
            is AnyValue.DoubleV -> writer.value(value.value)
            is AnyValue.Str -> writer.value(value.value)
            is AnyValue.Arr -> {
                writer.beginArray()
                value.items.forEach { toJson(writer, it) }
                writer.endArray()
            }
            is AnyValue.Obj -> {
                writer.beginObject()
                value.entries.forEach { (k, v) ->
                    writer.name(k)
                    toJson(writer, v)
                }
                writer.endObject()
            }
        }
    }
}

object MoshiBuilder {
    fun create(): Moshi = Moshi.Builder()
        .add(AnyValue::class.java, AnyValueJsonAdapter())
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
}
