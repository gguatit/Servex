package com.example.servex.data

import com.example.servex.domain.model.ActionResult
import com.example.servex.domain.model.CpuInfo
import com.example.servex.domain.model.DiskInfo
import com.example.servex.domain.model.HistoryPoint
import com.example.servex.domain.model.HistoryResponse
import com.example.servex.domain.model.MemoryInfo
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.NetworkInfo
import com.example.servex.domain.model.Server
import kotlin.math.sin

/**
 * Synthetic data used by demo mode so the app can be explored without a real agent.
 * Deterministic per server so the UI looks stable instead of flickering.
 */
object DemoData {
    private const val PREFIX = "demo:"

    val servers: List<Server> = listOf(
        Server(id = PREFIX + "web-01", name = "web-01", baseUrl = "https://web-01.demo:8765"),
        Server(id = PREFIX + "db-01", name = "db-01", baseUrl = "https://db-01.demo:8765"),
        Server(id = PREFIX + "nas-01", name = "nas-01", baseUrl = "https://nas-01.demo:8765"),
    )

    fun isDemo(server: Server): Boolean = server.id.startsWith(PREFIX)

    private data class Spec(
        val host: String,
        val os: String,
        val cpu: Double,
        val mem: Double,
        val disk: Double,
        val rx: Long,
        val tx: Long,
        val uptime: Long,
        val seed: Double,
    )

    private fun specFor(server: Server): Spec = when (server.id) {
        PREFIX + "web-01" -> Spec("web-01", "Ubuntu 24.04 LTS", 37.0, 54.0, 39.8, 1_200_000, 520_000, 382_100, 0.0)
        PREFIX + "db-01" -> Spec("db-01", "Ubuntu 24.04 LTS", 61.0, 80.5, 62.4, 4_800_000, 2_100_000, 1_204_800, 2.1)
        else -> Spec("nas-01", "Debian 12", 44.0, 61.0, 91.5, 320_000, 150_000, 5_120_400, 4.2)
    }

    fun metrics(server: Server, nowMs: Long = System.currentTimeMillis()): MetricsSnapshot {
        val s = specFor(server)
        val wobble = sin(nowMs / 1000.0 / 30.0 + s.seed)
        val cpu = (s.cpu + 6.0 * wobble).coerceIn(0.0, 100.0)
        val mem = (s.mem + 4.0 * wobble).coerceIn(0.0, 100.0)
        val totalMem = 16L * 1024 * 1024 * 1024
        val usedMem = (totalMem * (mem / 100.0)).toLong()
        val diskTotal = 512L * 1024 * 1024 * 1024
        val dataPct = (s.disk * 0.8).coerceIn(0.0, 100.0)
        return MetricsSnapshot(
            ts = nowMs / 1000,
            host = s.host,
            os = s.os,
            uptimeS = s.uptime,
            cpu = CpuInfo(cpu, 8, cpu / 12.0, cpu / 16.0, cpu / 20.0),
            memory = MemoryInfo(totalMem, usedMem, totalMem - usedMem, mem),
            disks = listOf(
                DiskInfo("/", diskTotal, (diskTotal * (s.disk / 100.0)).toLong(), s.disk),
                DiskInfo("/data", diskTotal * 2, (diskTotal * 2 * (dataPct / 100.0)).toLong(), dataPct),
            ),
            network = listOf(NetworkInfo("eth0", s.rx, s.tx, s.rx * 3600, s.tx * 3600)),
        )
    }

    fun history(metric: String, points: Int, nowMs: Long = System.currentTimeMillis()): HistoryResponse {
        val n = points.coerceIn(1, 150)
        val t0 = nowMs / 1000 - n
        val pts = (0 until n).map { i ->
            val v = when (metric) {
                "memory" -> 55.0 + 18.0 * sin(i / 6.0)
                "disk" -> 40.0 + 8.0 * sin(i / 9.0)
                "network" -> 1_000_000.0 + 700_000.0 * sin(i / 4.0)
                else -> 35.0 + 20.0 * sin(i / 5.0)
            }
            HistoryPoint(t = t0 + i, v = v)
        }
        return HistoryResponse(metric = metric, intervalS = 1.0, points = pts)
    }

    fun action(action: String, args: Map<String, String>): ActionResult {
        val service = args["service"].orEmpty()
        val output = if (service.isBlank()) "(demo) $action ok" else "(demo) $action $service ok"
        return ActionResult(ok = true, output = output)
    }
}
