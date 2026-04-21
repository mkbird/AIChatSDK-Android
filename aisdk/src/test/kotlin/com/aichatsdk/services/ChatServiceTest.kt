package com.aichatsdk.services

import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.ChatTurnState
import com.aichatsdk.models.EventType
import com.aichatsdk.models.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatServiceTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var transport: FakeTransport
    private lateinit var service: ChatService

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        transport = FakeTransport()
        service = ChatService(fakeConfig())
        service.setEventTransport(transport)
    }

    @After fun tearDown() {
        service.teardown()
        Dispatchers.resetMain()
    }

    // ----- session lifecycle -----

    @Test fun `initiateSession sends session_init with protocol_version`() = runTest {
        service.initiateSession()
        val sent = transport.sentEvents.single()
        assertThat(sent.type).isEqualTo(EventType.SESSION_INIT)
        assertThat(sent.long("protocol_version")).isEqualTo(4L)
        assertThat(sent.string("device_id")).isEqualTo("test-device")
    }

    @Test fun `session_ready sets sessionReady and sessionId`() = runTest {
        transport.receive(ChatEvent("session.ready", payload = mapOf(
            "session_id" to AnyValue.Str("s_xxx"),
            "protocol_version" to AnyValue.IntV(4),
        )))
        assertThat(service.sessionReady.value).isTrue()
        assertThat(service.sessionId.value).isEqualTo("s_xxx")
    }

    @Test fun `closeSession sends session_close and clears state`() = runTest {
        transport.receive(ChatEvent("session.ready", payload = mapOf("session_id" to AnyValue.Str("s"))))
        service.closeSession("done")
        val close = transport.sentEvents.last()
        assertThat(close.type).isEqualTo(EventType.SESSION_CLOSE)
        assertThat(close.string("reason")).isEqualTo("done")
        assertThat(service.sessionReady.value).isFalse()
        assertThat(service.sessionId.value).isNull()
    }

    // ----- text turn lifecycle -----

    @Test fun `text turn streams deltas into trailing assistant message`() = runTest {
        service.send("hi")
        // turn.started (text trigger) → SDK appends empty assistant message
        transport.receive(ChatEvent("turn.started", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "trigger" to AnyValue.Str("text"),
        )))
        transport.receive(ChatEvent("ai.thinking", payload = mapOf("turn_id" to AnyValue.Str("t1"))))
        transport.receive(ChatEvent("ai.text.delta", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "delta" to AnyValue.Str("你好"),
        )))
        transport.receive(ChatEvent("ai.text.delta", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "delta" to AnyValue.Str("，世界"),
        )))
        transport.receive(ChatEvent("ai.text.done", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "full_text" to AnyValue.Str("你好，世界"),
        )))
        transport.receive(ChatEvent("turn.done", payload = mapOf("turn_id" to AnyValue.Str("t1"))))

        val msgs = service.messages.value
        // [user, assistant]
        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].role).isEqualTo(Role.USER)
        assertThat(msgs[0].text).isEqualTo("hi")
        assertThat(msgs[1].role).isEqualTo(Role.ASSISTANT)
        assertThat(msgs[1].text).isEqualTo("你好，世界")
        assertThat(service.isStreaming.value).isFalse()
        assertThat(service.currentTurn.value).isNull()
    }

    @Test fun `text send wire payload contains text and tts flag`() = runTest {
        service.send("ping", withTts = true)
        val ev = transport.sentEvents.single { it.type == EventType.TEXT_SEND }
        assertThat(ev.string("text")).isEqualTo("ping")
        assertThat(ev.bool("tts")).isTrue()
    }

    @Test fun `voice turn creates user and empty assistant from asr_final`() = runTest {
        transport.receive(ChatEvent("turn.started", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "trigger" to AnyValue.Str("voice"),
        )))
        transport.receive(ChatEvent("asr.partial", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "text" to AnyValue.Str("今"),
        )))
        transport.receive(ChatEvent("asr.final", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "text" to AnyValue.Str("今天天气怎么样"),
            "reason" to AnyValue.Str("intent_complete"),
        )))
        transport.receive(ChatEvent("ai.text.delta", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "delta" to AnyValue.Str("晴朗"),
        )))
        transport.receive(ChatEvent("turn.done", payload = mapOf("turn_id" to AnyValue.Str("t1"))))

        val msgs = service.messages.value
        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].role).isEqualTo(Role.USER)
        assertThat(msgs[0].text).isEqualTo("今天天气怎么样")
        assertThat(msgs[1].role).isEqualTo(Role.ASSISTANT)
        assertThat(msgs[1].text).isEqualTo("晴朗")
    }

    @Test fun `turn_cancelled drops trailing empty assistant message`() = runTest {
        transport.receive(ChatEvent("turn.started", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "trigger" to AnyValue.Str("text"),
        )))
        // No deltas — assistant remains empty.
        transport.receive(ChatEvent("turn.cancelled", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "reason" to AnyValue.Str("interrupted_by_user"),
        )))
        // service had appended only the empty assistant for text trigger; should be dropped.
        assertThat(service.messages.value.none { it.role == Role.ASSISTANT && it.text.isBlank() }).isTrue()
        assertThat(service.currentTurn.value).isNull()
        assertThat(service.isStreaming.value).isFalse()
    }

    @Test fun `error event populates lastError with fatal flag`() = runTest {
        transport.receive(ChatEvent("error", payload = mapOf(
            "code" to AnyValue.Str("auth_failed"),
            "message" to AnyValue.Str("bad token"),
            "fatal" to AnyValue.Bool(true),
        )))
        val err = service.lastError.value
        assertThat(err).isNotNull()
        assertThat(err!!.code).isEqualTo("auth_failed")
        assertThat(err.fatal).isTrue()
    }

    // ----- history -----

    @Test fun `appendMessage trims to MAX_MEMORY=200`() = runTest {
        repeat(250) { i ->
            service.appendMessage(com.aichatsdk.models.ChatMessage(text = "m$i", role = Role.USER))
        }
        assertThat(service.messages.value).hasSize(200)
        assertThat(service.messages.value.last().text).isEqualTo("m249")
        assertThat(service.messages.value.first().text).isEqualTo("m50")
    }

    @Test fun `clearHistory empties messages and store`() = runTest {
        val store = InMemoryHistoryStore()
        val cfgService = ChatService(fakeConfig(store))
        cfgService.appendMessage(com.aichatsdk.models.ChatMessage(text = "x", role = Role.USER))
        cfgService.clearHistory()
        assertThat(cfgService.messages.value).isEmpty()
        // give async store.clear a chance — UnconfinedTestDispatcher runs immediately
        assertThat(store.clearCount).isAtLeast(1)
    }

    // ----- turn FSM observer -----

    @Test fun `currentTurn transitions through expected states`() = runTest {
        transport.receive(ChatEvent("turn.started", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "trigger" to AnyValue.Str("voice"),
        )))
        val turn = service.currentTurn.value!!
        assertThat(turn.state.value).isEqualTo(ChatTurnState.Started)

        transport.receive(ChatEvent("asr.partial", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "text" to AnyValue.Str("a"),
        )))
        assertThat(turn.state.value).isEqualTo(ChatTurnState.AsrListening)

        transport.receive(ChatEvent("ai.thinking", payload = mapOf("turn_id" to AnyValue.Str("t1"))))
        assertThat(turn.state.value).isEqualTo(ChatTurnState.AiThinking)

        transport.receive(ChatEvent("ai.text.delta", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"), "delta" to AnyValue.Str("hi"),
        )))
        assertThat(turn.state.value).isEqualTo(ChatTurnState.AiStreaming)

        transport.receive(ChatEvent("tts.start", payload = mapOf(
            "turn_id" to AnyValue.Str("t1"),
            "content_type" to AnyValue.Str("audio/pcm"),
        )))
        assertThat(turn.state.value).isEqualTo(ChatTurnState.TtsStreaming)

        transport.receive(ChatEvent("turn.done", payload = mapOf("turn_id" to AnyValue.Str("t1"))))
        assertThat(turn.state.value).isEqualTo(ChatTurnState.Completed)
    }
}
