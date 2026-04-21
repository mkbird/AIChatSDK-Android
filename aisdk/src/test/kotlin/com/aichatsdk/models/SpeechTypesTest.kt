package com.aichatsdk.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeechTypesTest {
    @Test fun `wire mapping for SpeechMode and CodecType`() {
        assertThat(SpeechMode.FullDuplex.wire).isEqualTo("full_duplex")
        assertThat(SpeechMode.AsrOnly.wire).isEqualTo("asr_only")
        assertThat(SpeechMode.fromWire("full_duplex")).isEqualTo(SpeechMode.FullDuplex)
        assertThat(SpeechMode.fromWire("unknown")).isEqualTo(SpeechMode.FullDuplex) // default

        assertThat(CodecType.Opus.wire).isEqualTo("opus")
        assertThat(CodecType.Pcm.wire).isEqualTo("pcm")
        assertThat(CodecType.fromWire("opus")).isEqualTo(CodecType.Opus)
        assertThat(CodecType.fromWire("garbage")).isEqualTo(CodecType.Pcm)
    }

    @Test fun `default SpeechConfig matches protocol defaults`() {
        val cfg = SpeechConfig()
        assertThat(cfg.mode).isEqualTo(SpeechMode.FullDuplex)
        assertThat(cfg.inputSampleRate).isEqualTo(16_000)
        assertThat(cfg.outputSampleRate).isEqualTo(24_000)
        assertThat(cfg.ttsEnabled).isTrue()
    }
}

class ChatMessageTest {
    @Test fun `default fields are sensible`() {
        val m = ChatMessage(text = "hi", role = Role.USER)
        assertThat(m.id).isNotEmpty()
        assertThat(m.timestamp).isGreaterThan(0L)
        assertThat(m.isUser).isTrue()
        assertThat(m.hasImages).isFalse()
        assertThat(m.attachments).isEmpty()
    }

    @Test fun `hasImages detects image attachment`() {
        val m = ChatMessage(
            text = "",
            role = Role.USER,
            attachments = listOf(ChatAttachment("image", "AAAA", "image/jpeg")),
        )
        assertThat(m.hasImages).isTrue()
    }
}

class ChatTurnTest {
    @Test fun `state transitions and asr accumulation`() {
        val t = ChatTurn("t_1", "voice")
        assertThat(t.state.value).isEqualTo(ChatTurnState.Started)
        t.moveTo(ChatTurnState.AsrListening)
        t.setAsr("今")
        t.setAsr("今天")
        t.setAsrFinal("今天天气", "intent_complete")
        assertThat(t.asrText.value).isEqualTo("今天天气")
        assertThat(t.asrFinalReason.value).isEqualTo("intent_complete")

        t.moveTo(ChatTurnState.AiStreaming)
        t.appendAi("你好")
        t.appendAi(", world")
        assertThat(t.aiFullText.value).isEqualTo("你好, world")

        t.moveTo(ChatTurnState.Completed)
        assertThat(t.state.value).isEqualTo(ChatTurnState.Completed)
    }
}
