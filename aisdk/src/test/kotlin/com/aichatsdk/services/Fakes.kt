package com.aichatsdk.services

import com.aichatsdk.config.AIChatConfig
import com.aichatsdk.models.ChatMessage
import com.aichatsdk.protocols.ChatContextProvider
import com.aichatsdk.protocols.ChatHistoryStore
import com.aichatsdk.protocols.TokenProvider

internal class FakeTokenProvider(var token: String? = "tk") : TokenProvider {
    override suspend fun currentAccessToken(): String? = token
}

internal class FakeContextProvider(val params: Map<String, String> = mapOf("device_id" to "test")) : ChatContextProvider {
    override val contextParameters: Map<String, String> get() = params
}

internal class InMemoryHistoryStore : ChatHistoryStore {
    var stored: List<ChatMessage> = emptyList()
        private set
    var saveCount = 0
    var clearCount = 0

    override suspend fun load(): List<ChatMessage> = stored
    override suspend fun save(messages: List<ChatMessage>) { stored = messages; saveCount++ }
    override suspend fun clear() { stored = emptyList(); clearCount++ }
}

internal fun fakeConfig(historyStore: ChatHistoryStore? = null) = AIChatConfig(
    serverBaseURL = "http://localhost:8000",
    tokenProvider = FakeTokenProvider(),
    contextProvider = FakeContextProvider(),
    historyStore = historyStore,
    deviceId = "test-device",
)
