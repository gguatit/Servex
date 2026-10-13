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

    override val servers: Flow<List<Server>> =
        context.serverDataStore.data.map { decode(it[key]) }

    override suspend fun upsert(server: Server) {
        context.serverDataStore.edit { prefs ->
            val current = decode(prefs[key]).filterNot { it.id == server.id } + server
            prefs[key] = encode(current)
        }
    }

    override suspend fun delete(id: String) {
        context.serverDataStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it.id == id })
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(list: List<Server>): String = json.encodeToString(list)
        fun decode(raw: String?): List<Server> =
            if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<Server>>(raw) }.getOrDefault(emptyList())
    }
}
