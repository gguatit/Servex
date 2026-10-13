package com.example.servex.data

import com.example.servex.domain.AppSettings
import com.example.servex.domain.model.ActionResult
import com.example.servex.domain.model.HistoryResponse
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.Server
import com.example.servex.domain.model.ServerSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {
    private val failingApi = object : ServexApiContract {
        override suspend fun summary(server: Server, token: String) = ServerSummary()
        override suspend fun metrics(server: Server, token: String): MetricsSnapshot =
            throw ApiException(401, "unauthorized")
        override suspend fun history(server: Server, token: String, metric: String, points: Int) = HistoryResponse()
        override suspend fun action(server: Server, token: String, action: String, args: Map<String, String>) = ActionResult(true)
        override suspend fun health(server: Server, token: String) = true
    }

    @Test fun metrics_failureIsRecoverableResult() = runBlocking {
        val repo = ServerRepository(FakeServerStore(), FakeSettingsStore(), FakeTokenStore("tok"), failingApi)
        val r = repo.metrics(Server("1", "s", "http://x"))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ApiException)
    }

    @Test fun missingToken_failsWithoutCall() = runBlocking {
        val repo = ServerRepository(FakeServerStore(), FakeSettingsStore(), FakeTokenStore(null), failingApi)
        assertTrue(repo.metrics(Server("1", "s", "http://x")).isFailure)
    }
}

class FakeServerStore : ServerStoreContract {
    private val list = mutableListOf<Server>()
    override val servers = MutableStateFlow<List<Server>>(emptyList())

    override suspend fun upsert(server: Server) {
        list.removeAll { it.id == server.id }
        list.add(server)
        servers.value = list.toList()
    }

    override suspend fun delete(id: String) {
        list.removeAll { it.id == id }
        servers.value = list.toList()
    }
}

class FakeSettingsStore : SettingsStoreContract {
    override val settings = MutableStateFlow(AppSettings())

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settings.value = transform(settings.value)
    }
}

class FakeTokenStore(private val token: String?) : TokenStoreContract {
    override fun get(id: String): String? = token
    override fun put(id: String, token: String) {}
    override fun remove(id: String) {}
}
