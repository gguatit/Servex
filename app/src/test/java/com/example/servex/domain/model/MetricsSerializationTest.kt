package com.example.servex.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun decodesExtendedMetrics() {
        val body = """
            {
              "ts": 100, "host": "web", "os": "Ubuntu 24.04",
              "uptime_s": 5000, "kernel": "6.8.0-45", "arch": "x86_64",
              "boot_time": 1700000000, "users": 3,
              "cpu": {
                "usage_pct": 37.2, "cores": 8, "load1": 1.1, "load5": 0.9, "load15": 0.7,
                "threads": 16, "model": "Ryzen 7", "per_core": [10.0, 20.0, 30.0], "temp_c": 51.5
              },
              "memory": {
                "total": 1000, "used": 400, "available": 600, "pct": 40.0,
                "swap_total": 200, "swap_used": 50, "swap_pct": 25.0,
                "cached": 120, "buffers": 30
              },
              "disks": [{"mount": "/", "total": 500, "used": 200, "pct": 40.0}],
              "network": [{"iface": "eth0", "rx_bps": 1, "tx_bps": 2, "rx_total": 3, "tx_total": 4,
                            "rx_errs": 5, "tx_errs": 6, "rx_drops": 7, "tx_drops": 8}],
              "disk_io": {"read_total": 11, "write_total": 22, "read_bps": 33, "write_bps": 44},
              "processes": {
                "by_cpu": [{"pid": 42, "name": "nginx", "cpu_pct": 9.5, "mem_pct": 1.2}],
                "by_mem": [{"pid": 7, "name": "db", "cpu_pct": 3.0, "mem_pct": 8.5}]
              },
              "services": {"failed": ["apt-daily.service"], "total": 142},
              "gpu": [{"name": "RTX 3060", "util_pct": 20.0, "mem_pct": 35.0, "temp_c": 60.0}],
              "docker": {"running": 3, "stopped": 1, "total": 4}
            }
        """.trimIndent()

        val m = json.decodeFromString<MetricsSnapshot>(body)

        assertEquals("6.8.0-45", m.kernel)
        assertEquals("x86_64", m.arch)
        assertEquals(1700000000L, m.bootTime)
        assertEquals(3, m.users)
        assertEquals(16, m.cpu.threads)
        assertEquals("Ryzen 7", m.cpu.model)
        assertEquals(listOf(10.0, 20.0, 30.0), m.cpu.perCore)
        assertEquals(51.5, m.cpu.tempC!!, 0.001)
        assertEquals(200L, m.memory.swapTotal)
        assertEquals(50L, m.memory.swapUsed)
        assertEquals(25.0, m.memory.swapPct!!, 0.001)
        assertEquals(120L, m.memory.cached)
        assertEquals(30L, m.memory.buffers)
        assertEquals(5, m.network[0].rxErrs)
        assertEquals(8, m.network[0].txDrops)
        assertEquals(33L, m.diskIo!!.readBps)
        assertEquals(44L, m.diskIo!!.writeBps)
        assertEquals("nginx", m.processes!!.byCpu[0].name)
        assertEquals(9.5, m.processes!!.byCpu[0].cpuPct!!, 0.001)
        assertEquals("db", m.processes!!.byMem[0].name)
        assertEquals(listOf("apt-daily.service"), m.services!!.failed)
        assertEquals(142, m.services!!.total)
        assertEquals("RTX 3060", m.gpu[0].name)
        assertEquals(20.0, m.gpu[0].utilPct!!, 0.001)
        assertEquals(3, m.docker!!.running)
        assertEquals(4, m.docker!!.total)
    }

    @Test fun decodesOldMinimalMetricsWithDefaults() {
        val body = """
            {"ts":5,"host":"web","os":"Ubuntu","uptime_s":10,
             "cpu":{"usage_pct":37.2,"cores":8},
             "memory":{"total":100,"used":40,"available":60,"pct":40.0},
             "disks":[],"network":[]}
        """.trimIndent()

        val m = json.decodeFromString<MetricsSnapshot>(body)

        assertEquals(37.2, m.cpu.usagePct, 0.001)
        assertNull(m.cpu.threads)
        assertNull(m.cpu.model)
        assertTrue(m.cpu.perCore.isEmpty())
        assertNull(m.cpu.tempC)
        assertNull(m.memory.swapTotal)
        assertNull(m.memory.cached)
        assertNull(m.memory.buffers)
        assertNull(m.kernel)
        assertNull(m.arch)
        assertNull(m.bootTime)
        assertNull(m.users)
        assertNull(m.diskIo)
        assertNull(m.processes)
        assertNull(m.services)
        assertTrue(m.gpu.isEmpty())
        assertNull(m.docker)
    }
}
