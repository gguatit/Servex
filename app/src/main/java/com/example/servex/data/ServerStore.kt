package com.example.servex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.servex.domain.model.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.serverDataStore by preferencesDataStore(name = "servex_servers")

class ServerStore(private val context: Context) : ServerStoreContract {
    private val key = stringPreferencesKey("servers_json")
    private val backupKey = stringPreferencesKey("servers_json_backup")

    override val servers: Flow<List<Server>> =
        context.serverDataStore.data.map { decode(it[key]) }

    override suspend fun upsert(server: Server) {
        context.serverDataStore.edit { prefs ->
            val raw = prefs[key]
            val current = decodeStrict(raw)
            if (current == null) prefs[backupKey] = raw ?: ""
            prefs[key] = encode((current ?: emptyList()).filterNot { it.id == server.id } + server)
        }
    }

    override suspend fun delete(id: String) {
        context.serverDataStore.edit { prefs ->
            val raw = prefs[key]
            val current = decodeStrict(raw)
            if (current == null) prefs[backupKey] = raw ?: ""
            prefs[key] = encode((current ?: emptyList()).filterNot { it.id == id })
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(list: List<Server>): String = json.encodeToString(list)

        /** Lenient: unreadable data reads as an empty list so the UI still renders. */
        fun decode(raw: String?): List<Server> =
            if (raw.isNullOrBlank()) emptyList() else decodeStrict(raw) ?: emptyList()

        /** Strict: null when the raw value is present but unreadable (so callers can back it up). */
        private fun decodeStrict(raw: String?): List<Server>? =
            if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<Server>>(raw) }.getOrNull()
    }
}
