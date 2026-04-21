package com.aichatsdk.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatEventCodecTest {

    @Test fun `decode v4 session ready sample`() {
        val sample = """
            {
              "type":"session.ready",
              "id":"req_001",
              "payload":{
                "session_id":"s_a1b2",
                "server_version":"4.0.0",
                "protocol_version":4,
                "capabilities":{"tts":true,"asr":true,"opus":true,"conversation":true}
              },
              "ts":1711360000000
            }
        """.trimIndent()
        val ev = ChatEventCodec.fromJson(sample)
        assertThat(ev.type).isEqualTo(EventType.SESSION_READY)
        assertThat(ev.id).isEqualTo("req_001")
        assertThat(ev.ts).isEqualTo(1_711_360_000_000L)
        assertThat(ev.string("session_id")).isEqualTo("s_a1b2")
        assertThat(ev.long("protocol_version")).isEqualTo(4L)
        val caps = ev.obj("capabilities")!!
        assertThat(caps["tts"]?.boolValue).isTrue()
        assertThat(caps["opus"]?.boolValue).isTrue()
    }

    @Test fun `decode asr final with reason`() {
        val sample = """
            {"type":"asr.final","payload":{"turn_id":"t_001","text":"今天天气怎么样","reason":"intent_complete"},"ts":1}
        """.trimIndent()
        val ev = ChatEventCodec.fromJson(sample)
        assertThat(ev.string("text")).isEqualTo("今天天气怎么样")
        assertThat(ev.string("reason")).isEqualTo("intent_complete")
        assertThat(ev.string("turn_id")).isEqualTo("t_001")
    }

    @Test fun `encode text send with attachments`() {
        val ev = ChatEvent.text(
            type = EventType.TEXT_SEND,
            id = "req_x",
            payload = mapOf(
                "text" to AnyValue.Str("hi"),
                "tts" to AnyValue.Bool(false),
                "attachments" to AnyValue.Arr(listOf(
                    AnyValue.Obj(mapOf(
                        "kind" to AnyValue.Str("image"),
                        "data" to AnyValue.Str("AAAA"),
                        "mime_type" to AnyValue.Str("image/jpeg"),
                    ))
                )),
            ),
        )
        val json = ChatEventCodec.toJson(ev)
        assertThat(json).contains("\"type\":\"text.send\"")
        assertThat(json).contains("\"attachments\":[")
        assertThat(json).contains("\"kind\":\"image\"")
        assertThat(json).contains("\"data\":\"AAAA\"")

        // Round-trip
        val parsed = ChatEventCodec.fromJson(json)
        assertThat(parsed.type).isEqualTo(EventType.TEXT_SEND)
        assertThat(parsed.id).isEqualTo("req_x")
        assertThat(parsed.string("text")).isEqualTo("hi")
        assertThat(parsed.bool("tts")).isFalse()
        assertThat(parsed.payload["attachments"]?.arrayValue).hasSize(1)
    }

    @Test fun `decode error event with fatal flag`() {
        val sample = """{"type":"error","payload":{"code":"auth_failed","message":"x","fatal":true}}"""
        val ev = ChatEventCodec.fromJson(sample)
        assertThat(ev.string("code")).isEqualTo("auth_failed")
        assertThat(ev.bool("fatal")).isTrue()
    }

    @Test fun `tolerates absent id and ts`() {
        val sample = """{"type":"ai.thinking","payload":{"turn_id":"t_1"}}"""
        val ev = ChatEventCodec.fromJson(sample)
        assertThat(ev.id).isNull()
        assertThat(ev.ts).isNull()
        assertThat(ev.string("turn_id")).isEqualTo("t_1")
    }

    @Test fun `encode includes ts when set`() {
        val ev = ChatEvent("speech.start", id = "1", payload = mapOf("mode" to AnyValue.Str("full_duplex")), ts = 12345L)
        val json = ChatEventCodec.toJson(ev)
        assertThat(json).contains("\"ts\":12345")
    }
}
