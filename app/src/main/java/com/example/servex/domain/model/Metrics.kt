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
)

@Serializable
data class MemoryInfo(val total: Long = 0, val used: Long = 0, val available: Long = 0, val pct: Double = 0.0)

@Serializable
data class DiskInfo(val mount: String = "/", val total: Long = 0, val used: Long = 0, val pct: Double = 0.0)

@Serializable
data class NetworkInfo(
    val iface: String = "",
    @SerialName("rx_bps") val rxBps: Long = 0,
    @SerialName("tx_bps") val txBps: Long = 0,
    @SerialName("rx_total") val rxTotal: Long = 0,
    @SerialName("tx_total") val txTotal: Long = 0,
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
