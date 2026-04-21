package com.aichatsdk.protocols

import com.aichatsdk.models.ChatEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-owned bridge between the SDK and the actual transport
 * (typically an OkHttp WebSocket). Mirrors iOS `ChatEventTransport`.
 *
 * The SDK never opens connections itself — apps wire up a transport,
 * then call [ChatService.setEventTransport].
 */
interface ChatEventTransport {
    val isConnected: StateFlow<Boolean>

    /** Set by ChatService at attach time. Implementations must invoke it on every inbound text frame. */
    var onEvent: ((ChatEvent) -> Unit)?

    /** Set by ChatService at attach time. Implementations must invoke it on every inbound binary frame. */
    var onAudioData: ((ByteArray) -> Unit)?

    fun sendEvent(event: ChatEvent)
    fun sendAudioData(data: ByteArray)
}

interface TokenProvider {
    suspend fun currentAccessToken(): String?
}

interface ChatContextProvider {
    val contextParameters: Map<String, String>
}

interface ChatHistoryStore {
    suspend fun load(): List<com.aichatsdk.models.ChatMessage>
    suspend fun save(messages: List<com.aichatsdk.models.ChatMessage>)
    suspend fun clear()
}
