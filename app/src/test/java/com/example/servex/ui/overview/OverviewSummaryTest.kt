package com.example.servex.ui.overview

import com.example.servex.domain.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewSummaryTest {
    @Test fun countsByStatus() {
        val s = summarize(listOf(ServerStatus.NORMAL, ServerStatus.NORMAL, ServerStatus.OFFLINE))
        assertEquals(2, s.normal)
        assertEquals(0, s.warning)
        assertEquals(0, s.error)
        assertEquals(1, s.offline)
    }

    @Test fun emptyIsAllZero() {
        assertEquals(StatusSummary(0, 0, 0, 0), summarize(emptyList()))
    }
}
