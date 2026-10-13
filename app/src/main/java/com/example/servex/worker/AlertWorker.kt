package com.example.servex.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.servex.ServexApp
import com.example.servex.domain.AlertThreshold
import com.example.servex.domain.ServerStatus
import com.example.servex.domain.StatusCalculator
import kotlinx.coroutines.flow.first

fun shouldNotify(previous: ServerStatus?, current: ServerStatus): Boolean {
    if (previous == current) return false
    if (previous == null && current == ServerStatus.NORMAL) return false
    return true
}

class AlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ServexApp).container
        val settings = container.settingsStore.settings.first()
        if (!settings.alertsEnabled) return Result.success()
        val threshold = AlertThreshold(settings.warningPct, settings.criticalPct)
        val servers = container.serverStore.servers.first().filter { it.enabled }
        val last = container.lastStatusStore
        val notifier = Notifier(applicationContext)
        for (server in servers) {
            val current = container.repository.metrics(server).fold(
                onSuccess = { StatusCalculator.fromMetrics(it, threshold) },
                onFailure = { ServerStatus.OFFLINE },
            )
            if (shouldNotify(last.get(server.id), current)) notifier.notify(server.name, current)
            last.set(server.id, current)
        }
        return Result.success()
    }
}
