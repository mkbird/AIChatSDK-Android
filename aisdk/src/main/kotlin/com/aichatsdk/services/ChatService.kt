package com.aichatsdk.services

import android.util.Log
import com.aichatsdk.config.AIChatConfig
import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatAttachment
import com.aichatsdk.models.ChatError
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.ChatMessage
import com.aichatsdk.models.ChatTurn
import com.aichatsdk.models.ChatTurnState
import com.aichatsdk.models.EventType
import com.aichatsdk.models.Role
import com.aichatsdk.models.SpeechConfig
import com.aichatsdk.models.StopAction
import com.aichatsdk.protocols.ChatEventTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Central orchestrator. Mirrors iOS ChatService.
 *
 * Threading: state mutations happen via [scope] on the main dispatcher to keep
 * Compose recompositions consistent.
 */
class ChatService(
    val config: AIChatConfig,
    private val rest: ChatRestClient = ChatRestClient(),
    val speech: SpeechManager = SpeechManager(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------- Public state ----------
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isTransportConnected = MutableStateFlow(false)
    val isTransportConnected: StateFlow<Boolean> = _isTransportConnected

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId

    private val _currentTurn = MutableStateFlow<ChatTurn?>(null)
    val currentTurn: StateFlow<ChatTurn?> = _currentTurn

    private val _lastError = MutableStateFlow<ChatError?>(null)
    val lastError: StateFlow<ChatError?> = _lastError

    private var transport: ChatEventTransport? = null
    private var connectionWatchJob: Job? = null

    init {
        loadHistory()
    }

    // ---------- Wiring ----------
    fun setEventTransport(t: ChatEventTransport) {
        this.transport = t
        speech.setTransport(t)
        t.onEvent = { event -> scope.launch { handleEvent(event) } }
        t.onAudioData = { data -> speech.feedRemoteAudio(data) }
        connectionWatchJob?.cancel()
        connectionWatchJob = scope.launch {
            t.isConnected.collect { _isTransportConnected.value = it }
        }
    }

    fun initiateSession() {
        val payload = mapOf(
            "protocol_version" to AnyValue.IntV(config.protocolVersion.toLong()),
            "device_id" to AnyValue.Str(config.deviceId),
            "client_version" to AnyValue.Str(config.clientVersion),
        ) + (config.userId?.let { mapOf("user_id" to AnyValue.IntV(it)) } ?: emptyMap())
        transport?.sendEvent(ChatEvent.text(EventType.SESSION_INIT, id = "init_${System.currentTimeMillis()}", payload = payload))
    }

    fun closeSession(reason: String = "user_disconnect") {
        transport?.sendEvent(
            ChatEvent.text(
                EventType.SESSION_CLOSE,
                payload = mapOf("reason" to AnyValue.Str(reason)),
            )
        )
        _sessionReady.value = false
        _sessionId.value = null
    }

    // ---------- Text I/O ----------
    fun send(text: String, attachments: List<ChatAttachment> = emptyList(), withTts: Boolean = false) {
        appendMessage(ChatMessage(text = text, role = Role.USER, attachments = attachments))
        val attPayload = if (attachments.isEmpty()) null else AnyValue.Arr(
            attachments.map { att ->
                AnyValue.Obj(buildMap {
                    put("kind", AnyValue.Str(att.kind))
                    put("data", AnyValue.Str(att.data))
                    att.mimeType?.let { put("mime_type", AnyValue.Str(it)) }
                })
            }
        )
        val payload = buildMap {
            put("text", AnyValue.Str(text))
            put("tts", AnyValue.Bool(withTts))
            attPayload?.let { put("attachments", it) }
        }
        transport?.sendEvent(ChatEvent.text(EventType.TEXT_SEND, id = "send_${System.currentTimeMillis()}", payload = payload))
    }

    fun synthesize(text: String) {
        appendMessage(ChatMessage(text = "TTS: $text", role = Role.USER))
        transport?.sendEvent(
            ChatEvent.text(
                EventType.TTS_SYNTHESIZE,
                id = "tts_${System.currentTimeMillis()}",
                payload = mapOf("text" to AnyValue.Str(text)),
            )
        )
    }

    suspend fun sendViaRest(text: String, attachments: List<ChatAttachment> = emptyList()): String {
        appendMessage(ChatMessage(text = text, role = Role.USER, attachments = attachments))
        val token = config.tokenProvider.currentAccessToken()
        val reply = rest.send(text, attachments, token, config.serverBaseURL, config.chatEndpoint, config.contextProvider.contextParameters)
        appendMessage(ChatMessage(text = reply, role = Role.ASSISTANT))
        return reply
    }

    // ---------- Voice ----------
    private var lastSpeechMode: com.aichatsdk.models.SpeechMode? = null
    fun startSpeech(config: SpeechConfig? = null) {
        val cfg = config ?: this.config.defaultSpeechConfig
        lastSpeechMode = cfg.mode
        speech.startSpeech(cfg)
    }
    fun stopSpeech(action: StopAction = StopAction.Stop) = speech.stopSpeech(action)

    // ---------- History ----------
    fun appendMessage(msg: ChatMessage) {
        val list = _messages.value.toMutableList().apply { add(msg) }
        _messages.value = if (list.size > MAX_MEMORY) list.takeLast(MAX_MEMORY) else list
        persist()
    }

    fun clearHistory() {
        _messages.value = emptyList()
        config.historyStore?.let { store ->
            scope.launch { runCatching { store.clear() } }
        }
    }

    fun loadHistory() {
        config.historyStore?.let { store ->
            scope.launch { runCatching { _messages.value = store.load() } }
        }
    }

    private fun persist() {
        config.historyStore?.let { store ->
            scope.launch { runCatching { store.save(_messages.value) } }
        }
    }

    // ---------- Event dispatch ----------
    private fun handleEvent(event: ChatEvent) {
        // forward to speech first so AEC/ack runs irrespective of session state
        speech.handleEvent(event)
        when (event.type) {
            EventType.SESSION_READY -> {
                _sessionReady.value = true
                _sessionId.value = event.string("session_id")
            }
            EventType.TURN_STARTED -> handleTurnStarted(event)
            EventType.TURN_DONE -> handleTurnDone(event, cancelled = false)
            EventType.TURN_CANCELLED -> handleTurnDone(event, cancelled = true)
            EventType.AI_THINKING -> {
                _currentTurn.value?.apply { setThinking(true); moveTo(ChatTurnState.AiThinking) }
            }
            EventType.AI_TEXT_DELTA -> {
                val delta = event.string("delta") ?: return
                Log.d(TAG, "ai.text.delta delta='${delta.take(20)}'")
                _isStreaming.value = true
                _currentTurn.value?.apply { appendAi(delta); moveTo(ChatTurnState.AiStreaming) }
                appendDeltaToLastAssistant(delta)
            }
            EventType.AI_TEXT_DONE -> {
                _isStreaming.value = false
                event.string("full_text")?.let { full ->
                    _currentTurn.value?.setAiFull(full)
                    replaceLastAssistant(full)
                }
            }
            EventType.ASR_PARTIAL -> {
                val text = event.string("text") ?: return
                _currentTurn.value?.apply { setAsr(text); moveTo(ChatTurnState.AsrListening) }
            }
            EventType.ASR_FINAL -> handleAsrFinal(event)
            EventType.TTS_START -> {
                _currentTurn.value?.moveTo(ChatTurnState.TtsStreaming)
            }
            EventType.TTS_DONE -> {
                // stays TtsStreaming until turn.done
            }
            EventType.ERROR -> {
                _lastError.value = ChatError(
                    code = event.string("code") ?: "unknown",
                    message = event.string("message") ?: "",
                    turnId = event.string("turn_id"),
                    fatal = event.bool("fatal") ?: false,
                )
            }
        }
    }

    private fun handleTurnStarted(event: ChatEvent) {
        val turnId = event.string("turn_id") ?: UUID.randomUUID().toString()
        val trigger = event.string("trigger") ?: "text"
        val turn = ChatTurn(turnId, trigger)
        _currentTurn.value = turn
        // For text trigger, immediately add empty assistant message we will fill via deltas.
        if (trigger == "text" || trigger == "tts_only") {
            appendMessage(ChatMessage(text = "", role = Role.ASSISTANT))
        }
    }

    private fun handleAsrFinal(event: ChatEvent) {
        val text = event.string("text") ?: return
        val reason = event.string("reason")
        Log.d(TAG, "asr.final text='$text' reason=$reason mode=${speech.currentSpeechMode.value} messages=${_messages.value.size}")
        _currentTurn.value?.setAsrFinal(text, reason)
        if (text.isNotBlank()) {
            appendMessage(ChatMessage(text = text, role = Role.USER))
            // pure_asr: no AI reply, don't pre-create assistant placeholder
            if (lastSpeechMode != com.aichatsdk.models.SpeechMode.PureAsr) {
                appendMessage(ChatMessage(text = "", role = Role.ASSISTANT))
            }
        }
    }

    private fun handleTurnDone(event: ChatEvent, cancelled: Boolean) {
        Log.d(TAG, "turn.done cancelled=$cancelled messages=${_messages.value.size} last='${_messages.value.lastOrNull()?.text?.take(40)}'")
        _currentTurn.value?.moveTo(if (cancelled) ChatTurnState.Cancelled else ChatTurnState.Completed)
        _currentTurn.value = null
        _isStreaming.value = false
        // Drop trailing empty assistant message if no content was filled.
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == Role.ASSISTANT && list.last().text.isBlank()) {
            list.removeAt(list.size - 1)
            _messages.value = list
        }
        persist()
    }

    private fun appendDeltaToLastAssistant(delta: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = list[idx].text + delta)
        } else {
            list.add(ChatMessage(text = delta, role = Role.ASSISTANT))
        }
        _messages.value = list
    }

    private fun replaceLastAssistant(full: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = full)
            _messages.value = list
        }
    }

    fun teardown() {
        speech.teardown()
        connectionWatchJob?.cancel()
    }

    companion object {
        private const val MAX_MEMORY = 200
        private const val TAG = "ChatService"
    }
}
