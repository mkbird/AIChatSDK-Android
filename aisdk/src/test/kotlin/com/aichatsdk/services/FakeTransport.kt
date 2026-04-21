package com.aichatsdk.services

import com.aichatsdk.models.ChatEvent
import com.aichatsdk.protocols.ChatEventTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory transport for unit tests — no network. */
internal class FakeTransport : ChatEventTransport {
    private val _isConnected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _isConnected

    override var onEvent: ((ChatEvent) -> Unit)? = null
    override var onAudioData: ((ByteArray) -> Unit)? = null

    val sentEvents = mutableListOf<ChatEvent>()
    val sentAudio = mutableListOf<ByteArray>()

    override fun sendEvent(event: ChatEvent) { sentEvents += event }
    override fun sendAudioData(data: ByteArray) { sentAudio += data }

    /** Simulate a server-pushed event. */
    fun receive(event: ChatEvent) { onEvent?.invoke(event) }

    /** Simulate a server-pushed binary frame. */
    fun receiveAudio(data: ByteArray) { onAudioData?.invoke(data) }

    fun setConnected(v: Boolean) { _isConnected.value = v }
}
