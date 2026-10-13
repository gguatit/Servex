package com.example.servex.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.servex.data.ServerRepository
import com.example.servex.domain.AlertThreshold
import com.example.servex.domain.ServerStatus
import com.example.servex.domain.StatusCalculator
import com.example.servex.domain.model.HistoryPoint
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.Server
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun toFloats(points: List<HistoryPoint>): List<Float> = points.map { it.v.toFloat() }

data class DetailUiState(
    val server: Server? = null,
    val status: ServerStatus = ServerStatus.OFFLINE,
    val metrics: MetricsSnapshot? = null,
    val error: String? = null,
    val history: Map<String, List<Float>> = emptyMap(),
)

class DetailViewModel(
    private val repository: ServerRepository,
    private val serverId: String,
    private val refreshIntervalS: Int,
) : ViewModel() {
    private val state = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = state.asStateFlow()
    private var loop: Job? = null

    init {
        viewModelScope.launch {
            val server = repository.servers.first().firstOrNull { it.id == serverId }
            state.update { it.copy(server = server) }
            if (server != null) startPolling(server)
        }
    }

    private fun startPolling(server: Server) {
        loop?.cancel()
        loop = viewModelScope.launch {
            while (true) {
                val settings = repository.settings.first()
                val threshold = AlertThreshold(settings.warningPct, settings.criticalPct)
                repository.metrics(server).fold(
                    onSuccess = { m ->
                        state.update {
                            it.copy(metrics = m, status = StatusCalculator.fromMetrics(m, threshold), error = null)
                        }
                    },
                    onFailure = { e ->
                        state.update { it.copy(status = ServerStatus.OFFLINE, error = e.message) }
                    },
                )
                loadHistory(server)
                delay(server.pollIntervalS.coerceAtLeast(1) * 1000L)
            }
        }
    }

    private suspend fun loadHistory(server: Server) {
        val map = mutableMapOf<String, List<Float>>()
        for (metric in listOf("cpu", "memory", "disk", "network")) {
            repository.history(server, metric, 60).onSuccess { map[metric] = toFloats(it.points) }
        }
        state.update { it.copy(history = map) }
    }

    fun runAction(server: Server, action: String, args: Map<String, String>) =
        viewModelScope.launch {
            repository.runAction(server, action, args).fold(
                onSuccess = { r -> state.update { it.copy(error = r.output ?: "ok") } },
                onFailure = { e -> state.update { it.copy(error = e.message) } },
            )
        }

    override fun onCleared() {
        loop?.cancel()
        super.onCleared()
    }
}
