package com.example.servex.data

import com.example.servex.domain.AppSettings
import com.example.servex.domain.model.ActionResult
import com.example.servex.domain.model.HistoryResponse
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.Server
import kotlinx.coroutines.flow.Flow

class ServerRepository(
    private val serverStore: ServerStoreContract,
    private val settingsStore: SettingsStoreContract,
    private val tokenStore: TokenStoreContract,
    private val api: ServexApiContract,
) {
    val servers: Flow<List<Server>> = serverStore.servers
    val settings: Flow<AppSettings> = settingsStore.settings

    suspend fun save(server: Server, token: String?) {
        serverStore.upsert(server)
        if (token != null) tokenStore.put(server.id, token)
    }

    suspend fun delete(id: String) {
        serverStore.delete(id)
        tokenStore.remove(id)
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    private fun token(server: Server): String? = tokenStore.get(server.id)?.takeIf { it.isNotBlank() }

    suspend fun metrics(server: Server): Result<MetricsSnapshot> =
        call(server) { api.metrics(it, token(server)!!) }

    suspend fun history(server: Server, metric: String, points: Int): Result<HistoryResponse> =
        call(server) { api.history(it, token(server)!!, metric, points) }

    suspend fun runAction(server: Server, action: String, args: Map<String, String>): Result<ActionResult> =
        call(server) { api.action(it, token(server)!!, action, args) }

    suspend fun testConnection(server: Server, token: String): Result<Unit> =
        runCatching { api.summary(server, token); Unit }

    private inline fun <T> call(server: Server, block: (Server) -> T): Result<T> {
        token(server) ?: return Result.failure(IllegalStateException("missing token"))
        return runCatching { block(server) }
    }
}
