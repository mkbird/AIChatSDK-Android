package com.aichatsdk.demo

import android.app.Application
import com.aichatsdk.config.AIChatConfig
import com.aichatsdk.demo.providers.DemoChatContext
import com.aichatsdk.demo.providers.DemoTokenProvider
import com.aichatsdk.demo.settings.DemoSettings
import com.aichatsdk.demo.transport.DemoEventTransport
import com.aichatsdk.audio.isOpusAvailable
import com.aichatsdk.models.CodecType
import com.aichatsdk.models.SpeechConfig
import com.aichatsdk.persistence.DataStoreChatStore
import com.aichatsdk.protocols.ChatEventTransport
import com.aichatsdk.services.ChatService

class DemoApp : Application() {

    lateinit var settings: DemoSettings
        private set
    lateinit var chatService: ChatService
        private set

    private var transport: DemoEventTransport? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = DemoSettings(this)
        chatService = ChatService(
            AIChatConfig(
                serverBaseURL = settings.snapshot().serverURL,
                tokenProvider = DemoTokenProvider(settings),
                contextProvider = DemoChatContext(settings),
                historyStore = DataStoreChatStore(this),
                deviceId = "sdk-demo",
                defaultSpeechConfig = SpeechConfig(),
            )
        )
        connect()
    }

    /** Returns the configured codec, falling back to PCM if Opus native lib is unavailable. */
    fun effectiveCodec(): CodecType {
        val desired = settings.snapshot().codec
        return if (desired == CodecType.Opus && !isOpusAvailable()) CodecType.Pcm else desired
    }

    fun connect() {
        transport?.disconnect()
        val t = DemoEventTransport(
            baseURL = settings.snapshot().serverURL,
            token = { settings.snapshot().token },
            familyId = { settings.snapshot().familyId },
        )
        t.onReconnect = { chatService.initiateSession() }
        transport = t
        chatService.setEventTransport(t as ChatEventTransport)
        t.connect()
        chatService.initiateSession()
    }

    companion object {
        @Volatile lateinit var instance: DemoApp
            private set
    }
}

