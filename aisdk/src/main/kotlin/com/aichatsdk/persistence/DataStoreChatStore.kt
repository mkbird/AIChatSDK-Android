package com.aichatsdk.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aichatsdk.models.ChatMessage
import com.aichatsdk.models.MoshiBuilder
import com.aichatsdk.protocols.ChatHistoryStore
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first

/** DataStore-backed history. Mirrors iOS UserDefaultsChatStore. */
class DataStoreChatStore(
    private val context: Context,
    private val storeName: String = "aichat_history",
    private val storageKey: String = "chat_messages",
    private val limit: Int = 100,
) : ChatHistoryStore {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = storeName)
    private val key = stringPreferencesKey(storageKey)
    private val moshi = MoshiBuilder.create()
    private val type = Types.newParameterizedType(List::class.java, ChatMessage::class.java)
    private val adapter = moshi.adapter<List<ChatMessage>>(type)

    override suspend fun load(): List<ChatMessage> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[key] ?: return emptyList()
        return runCatching { adapter.fromJson(raw) ?: emptyList() }.getOrElse { emptyList() }
    }

    override suspend fun save(messages: List<ChatMessage>) {
        val trimmed = if (messages.size > limit) messages.takeLast(limit) else messages
        val json = adapter.toJson(trimmed)
        context.dataStore.edit { it[key] = json }
    }

    override suspend fun clear() {
        context.dataStore.edit { it.remove(key) }
    }
}
