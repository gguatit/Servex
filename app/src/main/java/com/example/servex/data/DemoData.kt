package com.example.servex.data

import com.example.servex.domain.model.ActionResult
import com.example.servex.domain.model.CpuInfo
import com.example.servex.domain.model.DiskInfo
import com.example.servex.domain.model.DiskIo
import com.example.servex.domain.model.DockerInfo
import com.example.servex.domain.model.HistoryPoint
import com.example.servex.domain.model.HistoryResponse
import com.example.servex.domain.model.MemoryInfo
import com.example.servex.domain.model.MetricsSnapshot
import com.example.servex.domain.model.NetworkInfo
import com.example.servex.domain.model.ProcessInfo
import com.example.servex.domain.model.ProcessList
import com.example.servex.domain.model.Server
import com.example.servex.domain.model.ServicesInfo
import kotlin.random.Random

/**
 * Synthetic data for demo mode so the whole app can be explored without an
 * agent. Values follow a mean-reverting random walk with occasional spikes and
 * a disk that fills up slowly, so the UI moves like a live server.
 */
object DemoData {
    private const val PREFIX = "demo:"
    private const val GIB = 1024L * 1024 * 1024

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
    )

    private fun specFor(server: Server): Spec = when (server.id) {
        PREFIX + "web-01" -> Spec("web-01", "Ubuntu 24.04 LTS", 37.0, 54.0, 39.8, 1_200_000, 520_000, 382_100)
        PREFIX + "db-01" -> Spec("db-01", "Ubuntu 24.04 LTS", 61.0, 80.5, 62.4, 4_800_000, 2_100_000, 1_204_800)
        else -> Spec("nas-01", "Debian 12", 44.0, 61.0, 91.5, 320_000, 150_000, 5_120_400)
    }

    private val rng = Random.Default

    // Live state per server: [cpu, mem, disk, rx, tx]. The disk only grows.
    private val live = mutableMapOf<String, DoubleArray>()

    private fun state(server: Server, spec: Spec): DoubleArray =
        live.getOrPut(server.id) {
            doubleArrayOf(spec.cpu, spec.mem, spec.disk, spec.rx.toDouble(), spec.tx.toDouble())
        }

    /** Mean-reverting random walk; [spikeChance] adds occasional bursts. */
    private fun wander(
        v: Double,
        base: Double,
        pull: Double,
        sigma: Double,
        lo: Double,
        hi: Double,
        spikeChance: Double = 0.0,
        spike: Double = 0.0,
    ): Double {
        var next = v + (base - v) * pull + (rng.nextDouble() - 0.5) * 2.0 * sigma
        if (spikeChance > 0.0 && rng.nextDouble() < spikeChance) next += spike * rng.nextDouble()
        return next.coerceIn(lo, hi)
    }

    fun metrics(server: Server, nowMs: Long = System.currentTimeMillis()): MetricsSnapshot {
        val s = specFor(server)
        val w = state(server, s)

        w[0] = wander(w[0], s.cpu, 0.15, 3.0, 1.0, 100.0, spikeChance = 0.04, spike = 30.0)
        w[1] = wander(w[1], s.mem, 0.10, 1.5, 1.0, 100.0, spikeChance = 0.03, spike = 10.0)
        w[2] = (w[2] + rng.nextDouble() * 0.03).coerceIn(0.0, 100.0)
        w[3] = wander(w[3], s.rx.toDouble(), 0.20, s.rx * 0.30, 0.0, s.rx * 5.0)
        w[4] = wander(w[4], s.tx.toDouble(), 0.20, s.tx * 0.30, 0.0, s.tx * 5.0)

        val cpu = w[0]
        val mem = w[1]
        val disk = w[2]
        val rx = w[3].toLong()
        val tx = w[4].toLong()

        val totalMem = 16L * GIB
        val usedMem = (totalMem * (mem / 100.0)).toLong()
        val swapTotal = 4L * GIB
        val swapUsed = (swapTotal * (mem / 250.0)).toLong()
        val diskTotal = 512L * GIB
        val dataPct = (disk * 0.8).coerceIn(0.0, 100.0)

        val perCore = (0 until 8).map { i ->
            (cpu + (rng.nextDouble() - 0.5) * 14.0 - i * 0.5).coerceIn(0.0, 100.0)
        }

        val procCpu = listOf(
            ProcessInfo(4211, "nginx", cpu * 0.35, 1.2),
            ProcessInfo(8823, "postgres", cpu * 0.22, 8.4),
            ProcessInfo(1204, "dockerd", cpu * 0.14, 2.1),
            ProcessInfo(3390, "python3", cpu * 0.09, 3.7),
            ProcessInfo(5512, "systemd-journal", cpu * 0.05, 0.6),
        )
        val procMem = listOf(
            ProcessInfo(8823, "postgres", cpu * 0.22, 8.4),
            ProcessInfo(3390, "python3", cpu * 0.09, 3.7),
            ProcessInfo(1204, "dockerd", cpu * 0.14, 2.1),
            ProcessInfo(4211, "nginx", cpu * 0.35, 1.2),
            ProcessInfo(5512, "systemd-journal", cpu * 0.05, 0.6),
        )

        return MetricsSnapshot(
            ts = nowMs / 1000,
            host = s.host,
            os = s.os,
            uptimeS = s.uptime,
            cpu = CpuInfo(
                usagePct = cpu,
                cores = 8,
                load1 = cpu / 12.0,
                load5 = cpu / 16.0,
                load15 = cpu / 20.0,
                threads = 16,
                model = "AMD Ryzen 7 5700X",
                perCore = perCore,
                tempC = 38.0 + cpu * 0.35,
            ),
            memory = MemoryInfo(
                total = totalMem,
                used = usedMem,
                available = totalMem - usedMem,
                pct = mem,
                swapTotal = swapTotal,
                swapUsed = swapUsed,
                swapPct = if (swapTotal > 0) swapUsed * 100.0 / swapTotal else 0.0,
                cached = totalMem / 4,
                buffers = totalMem / 32,
            ),
            disks = listOf(
                DiskInfo("/", diskTotal, (diskTotal * (disk / 100.0)).toLong(), disk),
                DiskInfo("/data", diskTotal * 2, (diskTotal * 2 * (dataPct / 100.0)).toLong(), dataPct),
            ),
            network = listOf(
                NetworkInfo(
                    "eth0", rx, tx, rx * 3600, tx * 3600,
                    rxErrs = 0, txErrs = 0, rxDrops = 12, txDrops = 3,
                ),
            ),
            kernel = "6.8.0-45-generic",
            arch = "x86_64",
            bootTime = nowMs / 1000 - s.uptime,
            users = 2,
            diskIo = DiskIo(
                readTotal = 84_120_000_000,
                writeTotal = 41_300_000_000,
                readBps = (rx * 0.4).toLong(),
                writeBps = (tx * 0.6).toLong(),
            ),
            processes = ProcessList(byCpu = procCpu, byMem = procMem),
            services = ServicesInfo(failed = listOf("apt-daily.service"), total = 142),
            gpu = emptyList(),
            docker = DockerInfo(running = 3, stopped = 1, total = 4),
        )
    }

    fun history(metric: String, points: Int, nowMs: Long = System.currentTimeMillis()): HistoryResponse {
        val n = points.coerceIn(1, 150)
        val t0 = nowMs / 1000 - n
        val base = when (metric) {
            "memory" -> 55.0
            "disk" -> 40.0
            "network" -> 1_000_000.0
            else -> 35.0
        }
        val amp = when (metric) {
            "memory" -> 18.0
            "disk" -> 6.0
            "network" -> 700_000.0
            else -> 20.0
        }
        val sigma = amp / 8.0
        val drift = if (metric == "disk") amp / n else 0.0
        var v = base + (rng.nextDouble() - 0.5) * amp
        val pts = (0 until n).map { i ->
            v += (base - v) * 0.15 + (rng.nextDouble() - 0.5) * 2.0 * sigma + drift
            v = v.coerceIn(base - amp, base + amp)
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
