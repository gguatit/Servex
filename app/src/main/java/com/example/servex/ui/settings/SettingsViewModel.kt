package com.example.servex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.servex.data.ServerRepository
import com.example.servex.domain.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun clampRefresh(v: Int): Int = v.coerceIn(1, 60)
fun clampPct(v: Double): Double = v.coerceIn(1.0, 100.0)

class SettingsViewModel(private val repository: ServerRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private fun apply(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repository.updateSettings(transform)
    }

    fun setRefresh(v: Int) = apply { it.copy(refreshIntervalS = clampRefresh(v)) }
    fun setWarning(v: Double) = apply { it.copy(warningPct = clampPct(v)) }
    fun setCritical(v: Double) = apply { it.copy(criticalPct = clampPct(v)) }
    fun setAlerts(v: Boolean) = apply { it.copy(alertsEnabled = v) }
}
