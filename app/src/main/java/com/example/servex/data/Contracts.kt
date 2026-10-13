package com.example.servex.data

import com.example.servex.domain.AppSettings
import com.example.servex.domain.model.*
import kotlinx.coroutines.flow.Flow

interface ServexApiContract {
    suspend fun summary(server: Server, token: String): ServerSummary
    suspend fun metrics(server: Server, token: String): MetricsSnapshot
    suspend fun history(server: Server, token: String, metric: String, points: Int): HistoryResponse
    suspend fun action(server: Server, token: String, action: String, args: Map<String, String>): ActionResult
    suspend fun health(server: Server, token: String): Boolean
}

interface SettingsStoreContract {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface ServerStoreContract {
    val servers: Flow<List<Server>>
    suspend fun upsert(server: Server)
    suspend fun delete(id: String)
}

interface TokenStoreContract {
    fun get(id: String): String?
    fun put(id: String, token: String)
    fun remove(id: String)
}
