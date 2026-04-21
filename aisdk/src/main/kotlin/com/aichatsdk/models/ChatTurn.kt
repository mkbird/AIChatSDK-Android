package com.aichatsdk.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ChatTurnState {
    Started, AsrListening, AiThinking, AiStreaming, TtsStreaming, Completed, Cancelled
}

/** Mirrors iOS ChatTurn — a single round-trip with the server. */
class ChatTurn(
    val id: String,
    val trigger: String,
) {
    private val _state = MutableStateFlow(ChatTurnState.Started)
    val state: StateFlow<ChatTurnState> = _state

    private val _asrText = MutableStateFlow("")
    val asrText: StateFlow<String> = _asrText

    private val _asrFinalReason = MutableStateFlow<String?>(null)
    val asrFinalReason: StateFlow<String?> = _asrFinalReason

    private val _aiFullText = MutableStateFlow("")
    val aiFullText: StateFlow<String> = _aiFullText

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking

    fun moveTo(s: ChatTurnState) { _state.value = s }
    fun setAsr(text: String) { _asrText.value = text }
    fun setAsrFinal(text: String, reason: String?) {
        _asrText.value = text
        _asrFinalReason.value = reason
    }
    fun appendAi(delta: String) { _aiFullText.value = _aiFullText.value + delta }
    fun setAiFull(text: String) { _aiFullText.value = text }
    fun setThinking(v: Boolean) { _isThinking.value = v }
}
