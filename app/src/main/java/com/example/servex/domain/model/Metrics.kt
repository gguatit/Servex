package com.example.servex.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CpuInfo(
    @SerialName("usage_pct") val usagePct: Double = 0.0,
    val cores: Int = 1,
    val load1: Double = 0.0,
    val load5: Double = 0.0,
    val load15: Double = 0.0,
    val threads: Int? = null,
    val model: String? = null,
    @SerialName("per_core") val perCore: List<Double> = emptyList(),
    @SerialName("temp_c") val tempC: Double? = null,
)

@Serializable
data class MemoryInfo(
    val total: Long = 0,
    val used: Long = 0,
    val available: Long = 0,
    val pct: Double = 0.0,
    @SerialName("swap_total") val swapTotal: Long? = null,
    @SerialName("swap_used") val swapUsed: Long? = null,
    @SerialName("swap_pct") val swapPct: Double? = null,
    val cached: Long? = null,
    val buffers: Long? = null,
)

@Serializable
data class DiskInfo(val mount: String = "/", val total: Long = 0, val used: Long = 0, val pct: Double = 0.0)

@Serializable
data class NetworkInfo(
    val iface: String = "",
    @SerialName("rx_bps") val rxBps: Long = 0,
    @SerialName("tx_bps") val txBps: Long = 0,
    @SerialName("rx_total") val rxTotal: Long = 0,
    @SerialName("tx_total") val txTotal: Long = 0,
    @SerialName("rx_errs") val rxErrs: Int? = null,
    @SerialName("tx_errs") val txErrs: Int? = null,
    @SerialName("rx_drops") val rxDrops: Int? = null,
    @SerialName("tx_drops") val txDrops: Int? = null,
)

@Serializable
data class DiskIo(
    @SerialName("read_total") val readTotal: Long? = null,
    @SerialName("write_total") val writeTotal: Long? = null,
    @SerialName("read_bps") val readBps: Long? = null,
    @SerialName("write_bps") val writeBps: Long? = null,
)

@Serializable
data class ProcessInfo(
    val pid: Int? = null,
    val name: String? = null,
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("mem_pct") val memPct: Double? = null,
)

@Serializable
data class ProcessList(
    @SerialName("by_cpu") val byCpu: List<ProcessInfo> = emptyList(),
    @SerialName("by_mem") val byMem: List<ProcessInfo> = emptyList(),
)

@Serializable
data class ServicesInfo(
    val failed: List<String> = emptyList(),
    val total: Int? = null,
)

@Serializable
data class GpuInfo(
    val name: String? = null,
    @SerialName("util_pct") val utilPct: Double? = null,
    @SerialName("mem_pct") val memPct: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
)

@Serializable
data class DockerInfo(
    val running: Int? = null,
    val stopped: Int? = null,
    val total: Int? = null,
)

@Serializable
data class MetricsSnapshot(
    val ts: Long = 0,
    val host: String = "",
    val os: String = "",
    @SerialName("uptime_s") val uptimeS: Long = 0,
    val cpu: CpuInfo = CpuInfo(),
    val memory: MemoryInfo = MemoryInfo(),
    val disks: List<DiskInfo> = emptyList(),
    val network: List<NetworkInfo> = emptyList(),
    val kernel: String? = null,
    val arch: String? = null,
    @SerialName("boot_time") val bootTime: Long? = null,
    val users: Int? = null,
    @SerialName("disk_io") val diskIo: DiskIo? = null,
    val processes: ProcessList? = null,
    val services: ServicesInfo? = null,
    val gpu: List<GpuInfo> = emptyList(),
    val docker: DockerInfo? = null,
)

@Serializable
data class HistoryPoint(val t: Long = 0, val v: Double = 0.0)

@Serializable
data class HistoryResponse(
    val metric: String = "cpu",
    @SerialName("interval_s") val intervalS: Double = 2.0,
    val points: List<HistoryPoint> = emptyList(),
)

@Serializable
data class ServerSummary(
    val host: String? = null,
    val os: String? = null,
    @SerialName("uptime_s") val uptimeS: Long = 0,
    val ts: Long = 0,
)
