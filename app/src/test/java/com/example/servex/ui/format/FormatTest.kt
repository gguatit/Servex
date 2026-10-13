package com.example.servex.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun bytesZero() {
        assertEquals("0.0 B", formatBytes(0))
    }

    @Test fun bytesBelowKb() {
        assertEquals("512.0 B", formatBytes(512))
    }

    @Test fun bytesKb() {
        assertEquals("1.5 KB", formatBytes(1536))
    }

    @Test fun bytesGb() {
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test fun bytesTb() {
        assertEquals("2.0 TB", formatBytes(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test fun rateAddsPerSecond() {
        assertEquals("1.0 KB/s", formatRate(1024))
        assertEquals("0.0 B/s", formatRate(0))
    }
}
