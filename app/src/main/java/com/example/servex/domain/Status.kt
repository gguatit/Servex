package com.example.servex.domain

import com.example.servex.domain.model.MetricsSnapshot

enum class ServerStatus { NORMAL, WARNING, ERROR, OFFLINE }

data class AlertThreshold(val warningPct: Double = 75.0, val criticalPct: Double = 90.0)

object StatusCalculator {
    fun fromMetrics(m: MetricsSnapshot, t: AlertThreshold): ServerStatus {
        val values = buildList {
            add(m.cpu.usagePct)
            add(m.memory.pct)
            m.disks.forEach { add(it.pct) }
        }
        return when {
            values.any { it >= t.criticalPct } -> ServerStatus.ERROR
            values.any { it >= t.warningPct } -> ServerStatus.WARNING
            else -> ServerStatus.NORMAL
        }
    }

    fun worst(statuses: Iterable<ServerStatus>): ServerStatus {
        val order = listOf(ServerStatus.OFFLINE, ServerStatus.ERROR, ServerStatus.WARNING, ServerStatus.NORMAL)
        val set = statuses.toSet()
        return order.firstOrNull { it in set } ?: ServerStatus.OFFLINE
    }
}
