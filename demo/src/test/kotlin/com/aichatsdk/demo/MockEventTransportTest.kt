package com.aichatsdk.demo

import com.aichatsdk.demo.transport.MockEventTransport
import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.EventType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MockEventTransportTest {

    @Test fun `session init triggers session ready`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.SESSION_INIT, id = "r1", payload = mapOf(
            "protocol_version" to AnyValue.IntV(4),
        )))
        // synchronous in mock
        val ready = received.single()
        assertThat(ready.type).isEqualTo(EventType.SESSION_READY)
        assertThat(ready.string("protocol_version")).isNull() // server side uses int
        assertThat(ready.long("protocol_version")).isEqualTo(4L)
        assertThat(ready.string("session_id")).isNotNull()
    }

    @Test fun `text send emits turn started through turn done`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.TEXT_SEND, payload = mapOf(
            "text" to AnyValue.Str("hi"),
            "tts" to AnyValue.Bool(false),
        )))
        waitFor(received) { it.any { e -> e.type == EventType.TURN_DONE } }

        val types = received.map { it.type }
        assertThat(types).contains(EventType.TURN_STARTED)
        assertThat(types).contains(EventType.AI_THINKING)
        assertThat(types).contains(EventType.AI_TEXT_DELTA)
        assertThat(types).contains(EventType.AI_TEXT_DONE)
        assertThat(types).contains(EventType.TURN_DONE)
        // No TTS when tts=false
        assertThat(types).doesNotContain(EventType.TTS_START)
    }

    @Test fun `text send with tts=true includes tts phase`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.TEXT_SEND, payload = mapOf(
            "text" to AnyValue.Str("weather?"),
            "tts" to AnyValue.Bool(true),
        )))
        waitFor(received) { it.any { e -> e.type == EventType.TURN_DONE } }

        val types = received.map { it.type }
        assertThat(types).contains(EventType.TTS_START)
        assertThat(types).contains(EventType.TTS_DONE)
        val ttsStart = received.first { it.type == EventType.TTS_START }
        assertThat(ttsStart.string("content_type")).isEqualTo("audio/pcm")
    }

    @Test fun `tts_synthesize yields tts_only trigger`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.TTS_SYNTHESIZE, payload = mapOf(
            "text" to AnyValue.Str("hello"),
        )))
        waitFor(received) { it.any { e -> e.type == EventType.TURN_DONE } }
        val start = received.first { it.type == EventType.TURN_STARTED }
        assertThat(start.string("trigger")).isEqualTo("tts_only")
        val types = received.map { it.type }
        assertThat(types).doesNotContain(EventType.AI_TEXT_DELTA)
        assertThat(types).contains(EventType.TTS_START)
    }

    @Test fun `speech stop without cancel triggers full voice simulation`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.SPEECH_START, payload = mapOf(
            "mode" to AnyValue.Str("full_duplex"),
            "input_codec" to AnyValue.Str("pcm"),
            "output_codec" to AnyValue.Str("pcm"),
        )))
        mock.sendEvent(ChatEvent.text(EventType.SPEECH_STOP, payload = mapOf("action" to AnyValue.Str("stop"))))
        waitFor(received) { it.any { e -> e.type == EventType.TURN_DONE } }
        val types = received.map { it.type }
        assertThat(types).contains(EventType.SPEECH_STARTED)
        assertThat(types).contains(EventType.SPEECH_STOPPED)
        assertThat(types).contains(EventType.ASR_PARTIAL)
        assertThat(types).contains(EventType.ASR_FINAL)
        assertThat(types).contains(EventType.TURN_DONE)
    }

    @Test fun `speech stop with cancel emits speech_stopped but no turn`() = runTest {
        val mock = MockEventTransport(charDelayMs = 0)
        val received = CopyOnWriteArrayList<ChatEvent>()
        mock.onEvent = { received += it }
        mock.sendEvent(ChatEvent.text(EventType.SPEECH_START, payload = mapOf(
            "mode" to AnyValue.Str("asr_only"),
            "input_codec" to AnyValue.Str("pcm"),
            "output_codec" to AnyValue.Str("pcm"),
        )))
        mock.sendEvent(ChatEvent.text(EventType.SPEECH_STOP, payload = mapOf("action" to AnyValue.Str("cancel"))))
        delay(150)
        val types = received.map { it.type }
        assertThat(types).contains(EventType.SPEECH_STOPPED)
        assertThat(types).doesNotContain(EventType.TURN_STARTED)
    }

    private suspend fun <T> waitFor(list: List<T>, predicate: (List<T>) -> Boolean) {
        withTimeout(3_000) {
            while (!predicate(list)) delay(10)
        }
    }
}
