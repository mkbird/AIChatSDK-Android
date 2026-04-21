package com.aichatsdk.demo.providers

import com.aichatsdk.demo.settings.DemoSettings
import com.aichatsdk.protocols.ChatContextProvider
import com.aichatsdk.protocols.TokenProvider

class DemoTokenProvider(private val settings: DemoSettings) : TokenProvider {
    override suspend fun currentAccessToken(): String? = settings.snapshot().token.takeIf { it.isNotBlank() }
}

class DemoChatContext(private val settings: DemoSettings) : ChatContextProvider {
    override val contextParameters: Map<String, String>
        get() = settings.snapshot().let {
            buildMap {
                put("device_id", "sdk-demo")
                if (it.familyId.isNotBlank()) put("family_id", it.familyId)
            }
        }
}
