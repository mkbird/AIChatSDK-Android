package com.aichatsdk.services

import com.aichatsdk.audio.FullDuplexAudioEngine
import com.aichatsdk.models.AnyValue
import com.aichatsdk.models.ChatEvent
import com.aichatsdk.models.EventType
import com.aichatsdk.models.SpeechConfig
import com.aichatsdk.models.SpeechMode
import com.aichatsdk.models.StopAction
import com.aichatsdk.protocols.ChatEventTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors iOS SpeechManager. Owns the [FullDuplexAudioEngine] and coordinates
 * with [ChatEventTransport] for streaming mic frames + receiving TTS audio.
 *
 * Buffers captured frames before [EventType.SPEECH_STARTED] arrives so the
 * server doesn't drop the leading audio.
 */
class SpeechManager {

    private val _isSpeechActive = MutableStateFlow(false)
    val isSpeechActive: StateFlow<Boolean> = _isSpeechActive

    private val _currentAsrText = MutableStateFlow("")
    val currentAsrText: StateFlow<String> = _currentAsrText

    private val _currentSpeechMode = MutableStateFlow<SpeechMode?>(null)
    val currentSpeechMode: StateFlow<SpeechMode?> = _currentSpeechMode

    private val _ttsPlaybackDoneCount = MutableStateFlow(0)
    val ttsPlaybackDoneCount: StateFlow<Int> = _ttsPlaybackDoneCount

    @Volatile private var transport: ChatEventTransport? = null
    private var engine: FullDuplexAudioEngine? = null
    private val started = AtomicBoolean(false)

    /** Buffer mic frames captured before the server ACKs SPEECH_STARTED. */
    private val preBuffer = ConcurrentLinkedQueue<ByteArray>()
    private val ackReceived = AtomicBoolean(false)
    @Volatile private var muteIncomingAudio = false
    @Volatile private var isTtsPlaying = false

    fun setTransport(transport: ChatEventTransport) {
        this.transport = transport
    }

    fun startSpeech(config: SpeechConfig) {
        // Interrupt any running TTS / prior session
        if (isTtsPlaying || started.get()) {
            stopInternal(action = StopAction.Cancel, sendStopEvent = started.get())
            engine?.clearPlayback()
            muteIncomingAudio = true
        }

        val eng = (engine ?: FullDuplexAudioEngine.forConfig(config).also { engine = it })
        eng.ensurePlayback()
        eng.setOnPlaybackDrained { _ttsPlaybackDoneCount.value = _ttsPlaybackDoneCount.value + 1 }

        ackReceived.set(false)
        preBuffer.clear()
        muteIncomingAudio = false

        eng.startCapture { frame ->
            if (ackReceived.get()) {
                transport?.sendAudioData(frame)
            } else {
                preBuffer.add(frame)
            }
        }

        // Tell server
        val payload = mapOf(
            "mode" to AnyValue.Str(config.mode.wire),
            "input_codec" to AnyValue.Str(config.inputCodec.wire),
            "output_codec" to AnyValue.Str(config.outputCodec.wire),
            "tts" to AnyValue.Bool(config.ttsEnabled),
        )
        transport?.sendEvent(ChatEvent.text(EventType.SPEECH_START, id = "speech_${System.currentTimeMillis()}", payload = payload))
        _isSpeechActive.value = true
        _currentSpeechMode.value = config.mode
        started.set(true)
    }

    fun stopSpeech(action: StopAction = StopAction.Stop) {
        stopInternal(action, sendStopEvent = true)
    }

    private fun stopInternal(action: StopAction, sendStopEvent: Boolean) {
        if (!started.get()) return
        engine?.stopCapture()
        if (sendStopEvent) {
            transport?.sendEvent(
                ChatEvent.text(
                    EventType.SPEECH_STOP,
                    id = "stop_${System.currentTimeMillis()}",
                    payload = mapOf("action" to AnyValue.Str(action.wire)),
                )
            )
        }
        _isSpeechActive.value = false
        started.set(false)
        if (action == StopAction.Cancel) {
            engine?.clearPlayback()
            muteIncomingAudio = true
        }
    }

    /** Audio bytes from server (TTS). */
    fun feedRemoteAudio(data: ByteArray) {
        if (muteIncomingAudio) return
        val eng = engine ?: FullDuplexAudioEngine().also { engine = it; it.ensurePlayback() }
        eng.feedAudio(data)
    }

    /** Handle inbound v4 control events relevant to speech. */
    fun handleEvent(event: ChatEvent) {
        when (event.type) {
            EventType.SPEECH_STARTED -> {
                ackReceived.set(true)
                muteIncomingAudio = false
                // flush any pre-buffered frames
                while (true) {
                    val f = preBuffer.poll() ?: break
                    transport?.sendAudioData(f)
                }
            }
            EventType.SPEECH_STOPPED -> {
                _currentSpeechMode.value = null
                _currentAsrText.value = ""
            }
            EventType.ASR_PARTIAL -> {
                _currentAsrText.value = event.string("text") ?: ""
            }
            EventType.ASR_FINAL -> {
                _currentAsrText.value = ""
            }
            EventType.TTS_START -> {
                isTtsPlaying = true
                muteIncomingAudio = false
            }
            EventType.TTS_DONE -> {
                isTtsPlaying = false
                engine?.markEndOfStream()
            }
            EventType.TURN_CANCELLED -> {
                engine?.clearPlayback()
            }
        }
    }

    fun teardown() {
        engine?.stopAll()
        engine = null
        started.set(false)
        _isSpeechActive.value = false
    }
}
