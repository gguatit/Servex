package com.example.servex.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.servex.data.ServerRepository
import com.example.servex.domain.AlertThreshold
import com.example.servex.domain.ServerStatus
import com.example.servex.domain.StatusCalculator
import com.example.servex.domain.model.Server
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusSummary(val normal: Int, val warning: Int, val error: Int, val offline: Int)

fun summarize(statuses: List<ServerStatus>): StatusSummary = StatusSummary(
    normal = statuses.count { it == ServerStatus.NORMAL },
    warning = statuses.count { it == ServerStatus.WARNING },
    error = statuses.count { it == ServerStatus.ERROR },
    offline = statuses.count { it == ServerStatus.OFFLINE },
)

data class ServerRow(val server: Server, val status: ServerStatus, val spark: List<Float>)

data class OverviewUiState(
    val rows: List<ServerRow> = emptyList(),
    val summary: StatusSummary = StatusSummary(0, 0, 0, 0),
    val loading: Boolean = true,
    val demoMode: Boolean = false,
)

class OverviewViewModel(private val repository: ServerRepository) : ViewModel() {
    private val rows = MutableStateFlow<List<ServerRow>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val demo = MutableStateFlow(false)
    private var activeServers: List<Server> = emptyList()
    private var refreshJob: Job? = null

    val uiState: StateFlow<OverviewUiState> = combine(rows, loading, demo) { r, l, d ->
        OverviewUiState(r, summarize(r.map { it.status }), l, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState())

    init {
        viewModelScope.launch {
            repository.servers.collect { servers ->
                activeServers = servers.filter { it.enabled }
                refresh()
            }
        }
        viewModelScope.launch {
            repository.settings.map { it.demoMode }.distinctUntilChanged()
                .collect { demo.value = it }
        }
        viewModelScope.launch {
            repository.settings.map { it.refreshIntervalS }.distinctUntilChanged()
                .collectLatest { secs ->
                    while (true) {
                        delay(secs.coerceIn(1, 60) * 1000L)
                        refresh()
                    }
                }
        }
    }

    fun setDemo(on: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(demoMode = on) } }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading.value = true
            val settings = repository.settings.first()
            val threshold = AlertThreshold(settings.warningPct, settings.criticalPct)
            rows.value = activeServers.map { server ->
                val status = repository.metrics(server).fold(
                    onSuccess = { StatusCalculator.fromMetrics(it, threshold) },
                    onFailure = { ServerStatus.OFFLINE },
                )
                val spark = repository.history(server, "cpu", 30)
                    .getOrNull()?.points?.map { it.v.toFloat() }.orEmpty()
                ServerRow(server, status, spark)
            }
            loading.value = false
        }
    }
}
