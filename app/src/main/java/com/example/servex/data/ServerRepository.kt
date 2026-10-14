package com.example.servex.data

import com.example.servex.domain.AppSettings
import com.example.servex.domain.model.ActionResult
import com.example.servex.domain.model.HistoryResponse
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class ServerRepository(
    private val serverStore: ServerStoreContract,
    private val settingsStore: SettingsStoreContract,
    private val tokenStore: TokenStoreContract,
    private val api: ServexApiContract,
) {
    /** When demo mode is on, a sandbox server list is shown instead of the stored one. */
    val servers: Flow<List<Server>> =
        combine(serverStore.servers, settingsStore.settings) { stored, settings ->
            if (settings.demoMode) DemoData.servers else stored
        }

    val settings: Flow<AppSettings> = settingsStore.settings

    suspend fun save(server: Server, token: String?) {
        serverStore.upsert(server)
        withContext(Dispatchers.IO) {
            when {
                token == null -> Unit
                token.isBlank() -> tokenStore.remove(server.id)
                else -> tokenStore.put(server.id, token)
            }
        }
    }

    suspend fun delete(id: String) {
        serverStore.delete(id)
        withContext(Dispatchers.IO) { tokenStore.remove(id) }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    private suspend fun token(server: Server): String? = withContext(Dispatchers.IO) {
        tokenStore.get(server.id)?.takeIf { it.isNotBlank() }
    }

    suspend fun metrics(server: Server): Result<MetricsSnapshot> =
        if (DemoData.isDemo(server)) {
            Result.success(DemoData.metrics(server, System.currentTimeMillis()))
        } else {
            call(server) { t -> api.metrics(server, t) }
        }

    suspend fun history(server: Server, metric: String, points: Int): Result<HistoryResponse> =
        if (DemoData.isDemo(server)) {
            Result.success(DemoData.history(server, metric, points, System.currentTimeMillis()))
        } else {
            call(server) { t -> api.history(server, t, metric, points) }
        }

    suspend fun runAction(server: Server, action: String, args: Map<String, String>): Result<ActionResult> =
        if (DemoData.isDemo(server)) {
            Result.success(DemoData.action(action, args))
        } else {
            call(server) { t -> api.action(server, t, action, args) }
        }

    suspend fun testConnection(server: Server, token: String): Result<Unit> =
        if (DemoData.isDemo(server)) {
            Result.success(Unit)
        } else {
            runCatching { api.summary(server, token); Unit }
        }

    private suspend fun <T> call(server: Server, block: suspend (String) -> T): Result<T> {
        val t = token(server) ?: return Result.failure(IllegalStateException("missing token"))
        return runCatching { block(t) }
    }
}
