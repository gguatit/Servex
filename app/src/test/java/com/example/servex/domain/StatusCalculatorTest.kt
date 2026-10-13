package com.example.servex.domain

import com.example.servex.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCalculatorTest {
    private val t = AlertThreshold(warningPct = 75.0, criticalPct = 90.0)

    private fun snap(cpu: Double, mem: Double, disk: Double = 10.0) = MetricsSnapshot(
        ts = 0, host = "h", os = "Linux", uptimeS = 0,
        cpu = CpuInfo(cpu, 4, 0.0, 0.0, 0.0),
        memory = MemoryInfo(100, 50, 50, mem),
        disks = listOf(DiskInfo("/", 100, 50, disk)),
        network = emptyList(),
    )

    @Test fun normal_belowWarning() = assertEquals(ServerStatus.NORMAL, StatusCalculator.fromMetrics(snap(10.0, 20.0), t))
    @Test fun warning_atWarningBoundary() = assertEquals(ServerStatus.WARNING, StatusCalculator.fromMetrics(snap(75.0, 20.0), t))
    @Test fun error_atCriticalBoundary() = assertEquals(ServerStatus.ERROR, StatusCalculator.fromMetrics(snap(90.0, 20.0), t))
    @Test fun diskDrivesStatus() = assertEquals(ServerStatus.WARNING, StatusCalculator.fromMetrics(snap(1.0, 1.0, 80.0), t))
    @Test fun worst_prefersErrorThenWarning() {
        assertEquals(ServerStatus.ERROR, StatusCalculator.worst(listOf(ServerStatus.NORMAL, ServerStatus.ERROR, ServerStatus.WARNING)))
        assertEquals(ServerStatus.OFFLINE, StatusCalculator.worst(emptyList()))
    }
}
