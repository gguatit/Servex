package com.example.servex.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampTest {
    @Test fun refreshClampedToRange() {
        assertEquals(1, clampRefresh(0))
        assertEquals(60, clampRefresh(999))
        assertEquals(5, clampRefresh(5))
    }

    @Test fun pctClampedToRange() {
        assertEquals(1.0, clampPct(0.0), 0.001)
        assertEquals(100.0, clampPct(140.0), 0.001)
        assertEquals(80.0, clampPct(80.0), 0.001)
    }
}
