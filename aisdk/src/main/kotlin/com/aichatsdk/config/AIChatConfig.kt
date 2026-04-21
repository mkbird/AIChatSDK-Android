package com.aichatsdk.config

import com.aichatsdk.models.SpeechConfig
import com.aichatsdk.protocols.ChatContextProvider
import com.aichatsdk.protocols.ChatHistoryStore
import com.aichatsdk.protocols.TokenProvider

/** Mirrors iOS AIChatConfig. */
data class AIChatConfig(
    val serverBaseURL: String,
    val chatEndpoint: String = "/api/chat/message",
    val tokenProvider: TokenProvider,
    val contextProvider: ChatContextProvider,
    val historyStore: ChatHistoryStore? = null,
    val historyLimit: Int = 100,
    val enableSpeech: Boolean = true,
    val defaultSpeechConfig: SpeechConfig = SpeechConfig(),
    val protocolVersion: Int = 4,
    val deviceId: String,
    val userId: Long? = null,
    val clientVersion: String = "1.0.0",
)
