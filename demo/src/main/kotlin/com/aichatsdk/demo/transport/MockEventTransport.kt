package com.aichatsdk.demo.transport

import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.EventType
import com.aichatsdk.protocols.ChatEventTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline simulator. Mirrors iOS MockEventTransport.
 *  - text.send / tts.synthesize: emit turn.started → ai.text.delta (per-char) → ai.text.done → tts.start → tts.done → turn.done
 *  - speech.start: emit speech.started ack
 *  - speech.stop:  emit speech.stopped + simulated asr → ai → tts pipeline
 */
class MockEventTransport(
    private val charDelayMs: Long = 30,
) : ChatEventTransport {

    private val _isConnected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _isConnected
    override var onEvent: ((ChatEvent) -> Unit)? = null
    override var onAudioData: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turnCounter = AtomicInteger(1)
    private var activeJob: Job? = null

    override fun sendEvent(event: ChatEvent) {
        DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Sent, event))
        when (event.type) {
            EventType.SESSION_INIT -> emit(
                ChatEvent.text(EventType.SESSION_READY, id = event.id, payload = mapOf(
                    "session_id" to AnyValue.Str("mock_${System.currentTimeMillis()}"),
                    "server_version" to AnyValue.Str("mock-4.0.0"),
                    "protocol_version" to AnyValue.IntV(4),
                    "capabilities" to AnyValue.Obj(mapOf(
                        "tts" to AnyValue.Bool(true),
                        "asr" to AnyValue.Bool(true),
                        "opus" to AnyValue.Bool(true),
                        "conversation" to AnyValue.Bool(true),
                    )),
                ))
            )
            EventType.SESSION_CLOSE -> { /* no-op */ }
            EventType.SPEECH_START -> emit(
                ChatEvent.text(EventType.SPEECH_STARTED, id = event.id, payload = mapOf(
                    "mode" to AnyValue.Str(event.string("mode") ?: "full_duplex"),
                    "input_codec" to AnyValue.Str(event.string("input_codec") ?: "pcm"),
                    "output_codec" to AnyValue.Str(event.string("output_codec") ?: "pcm"),
                    "input_sample_rate" to AnyValue.IntV(16000),
                    "output_sample_rate" to AnyValue.IntV(24000),
                ))
            )
            EventType.SPEECH_STOP -> {
                emit(ChatEvent.text(EventType.SPEECH_STOPPED, id = event.id))
                if (event.string("action") != "cancel") simulateTurn(asrText = "今天天气怎么样", isVoice = true)
            }
            EventType.TEXT_SEND -> simulateTurn(textIn = event.string("text"), tts = event.bool("tts") == true)
            EventType.TTS_SYNTHESIZE -> simulateTurn(textIn = event.string("text"), tts = true, ttsOnly = true)
        }
    }

    override fun sendAudioData(data: ByteArray) {
        // mock: ignore — but log for parity with real transport
        DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Sent, null, isAudio = true, audioBytes = data.size))
    }

    private fun simulateTurn(
        textIn: String? = null,
        tts: Boolean = false,
        ttsOnly: Boolean = false,
        asrText: String? = null,
        isVoice: Boolean = false,
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val turnId = "t_${turnCounter.getAndIncrement()}"
            val trigger = when { ttsOnly -> "tts_only"; isVoice -> "voice"; else -> "text" }
            emit(ChatEvent.text(EventType.TURN_STARTED, payload = mapOf(
                "turn_id" to AnyValue.Str(turnId),
                "trigger" to AnyValue.Str(trigger),
            )))
            // ASR phase for voice
            if (isVoice && asrText != null) {
                val accum = StringBuilder()
                asrText.forEach { ch ->
                    accum.append(ch)
                    emit(ChatEvent.text(EventType.ASR_PARTIAL, payload = mapOf(
                        "turn_id" to AnyValue.Str(turnId), "text" to AnyValue.Str(accum.toString()),
                    )))
                    delay(60)
                }
                emit(ChatEvent.text(EventType.ASR_FINAL, payload = mapOf(
                    "turn_id" to AnyValue.Str(turnId), "text" to AnyValue.Str(asrText),
                    "reason" to AnyValue.Str("intent_complete"),
                )))
            }
            emit(ChatEvent.text(EventType.AI_THINKING, payload = mapOf("turn_id" to AnyValue.Str(turnId))))
            // AI text streaming (always, except pure tts)
            val response = synthesizeReply(textIn ?: asrText ?: "")
            if (!ttsOnly) {
                response.forEach { ch ->
                    emit(ChatEvent.text(EventType.AI_TEXT_DELTA, payload = mapOf(
                        "turn_id" to AnyValue.Str(turnId), "delta" to AnyValue.Str(ch.toString()),
                    )))
                    delay(charDelayMs)
                }
                emit(ChatEvent.text(EventType.AI_TEXT_DONE, payload = mapOf(
                    "turn_id" to AnyValue.Str(turnId), "full_text" to AnyValue.Str(response),
                )))
            }
            // TTS phase
            if (tts) {
                emit(ChatEvent.text(EventType.TTS_START, payload = mapOf(
                    "turn_id" to AnyValue.Str(turnId),
                    "content_type" to AnyValue.Str("audio/pcm"),
                    "sample_rate" to AnyValue.IntV(24000),
                )))
                val durationMs = (response.length.coerceAtLeast(8)) * 50L
                delay(durationMs)
                emit(ChatEvent.text(EventType.TTS_DONE, payload = mapOf(
                    "turn_id" to AnyValue.Str(turnId), "audio_bytes_total" to AnyValue.IntV(durationMs * 48),
                )))
            }
            emit(ChatEvent.text(EventType.TURN_DONE, payload = mapOf("turn_id" to AnyValue.Str(turnId))))
        }
    }

    private fun synthesizeReply(input: String): String {
        if (input.isBlank()) return "你好，我在听。"
        if (input.contains("天气")) return "今天北京天气晴朗，最高温度 25 度。"
        return "我收到了：『$input』。这是一条来自 Mock 的回复。"
    }

    private fun emit(event: ChatEvent) {
        DebugEventLog.add(DebugEventLog.Entry(System.currentTimeMillis(), DebugEventLog.Direction.Received, event))
        onEvent?.invoke(event)
    }
}
