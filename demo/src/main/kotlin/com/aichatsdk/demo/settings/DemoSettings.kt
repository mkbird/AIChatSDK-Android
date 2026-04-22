package com.aichatsdk.demo.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aichatsdk.models.CodecType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "demo_settings")

private val KEY_SERVER = stringPreferencesKey("server_url")
private val KEY_TOKEN = stringPreferencesKey("token")
private val KEY_FAMILY = stringPreferencesKey("family_id")
private val KEY_CODEC = stringPreferencesKey("codec")

data class DemoSettingsSnapshot(
    val serverURL: String,
    val token: String,
    val familyId: String,
    val codec: CodecType,
)

class DemoSettings(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        DemoSettingsSnapshot(DEFAULT_SERVER, DEFAULT_TOKEN, DEFAULT_FAMILY, CodecType.Opus)
    )
    val state: StateFlow<DemoSettingsSnapshot> = _state

    init { scope.launch { reload() } }

    suspend fun reload() {
        val p = context.settingsStore.data.first()
        _state.value = DemoSettingsSnapshot(
            serverURL = p[KEY_SERVER]?.takeUnless { it.isBlank() } ?: DEFAULT_SERVER,
            token = p[KEY_TOKEN] ?: DEFAULT_TOKEN,
            familyId = p[KEY_FAMILY] ?: DEFAULT_FAMILY,
            codec = CodecType.fromWire(p[KEY_CODEC]),
        )
    }

    fun snapshot(): DemoSettingsSnapshot = _state.value

    suspend fun update(serverURL: String? = null, token: String? = null, familyId: String? = null, codec: CodecType? = null) {
        val safeCodec = codec?.let {
            val opusOk = runCatching { com.aichatsdk.demo.DemoApp.instance.opusAvailable }.getOrDefault(true)
            if (it == CodecType.Opus && !opusOk) CodecType.Pcm else it
        }
        context.settingsStore.edit {
            serverURL?.takeIf { v -> v.isNotBlank() }?.let { v -> it[KEY_SERVER] = v }
            token?.let { v -> it[KEY_TOKEN] = v }
            familyId?.let { v -> it[KEY_FAMILY] = v }
            safeCodec?.let { v -> it[KEY_CODEC] = v.wire }
        }
        reload()
    }

    fun reset() {
        scope.launch {
            context.settingsStore.edit { it.clear() }
            reload()
        }
    }

    companion object {
        const val DEFAULT_SERVER = "http://10.4.1.50:8000"
        const val DEFAULT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhMDdkMGIwYi03Yjk3LTRlNmItOTYxYy02ZjM0MDk1MzlkMDAiLCJleHAiOjE3NzUwMzg0ODMsInR5cGUiOiJhY2Nlc3MifQ.JZ_GpoIXvcTBGG96aBenyrG56e-ALjTtPOsVkW3aCS8"
        const val DEFAULT_FAMILY = "dfc7fe07-78fd-4337-ae89-45c42273e65b"
    }
}
