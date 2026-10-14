package com.example.servex.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampTest {
    @Test fun refreshClampedToRange() {
        assertEquals(0.2, clampRefresh(0.0), 0.001)
        assertEquals(60.0, clampRefresh(999.0), 0.001)
        assertEquals(5.0, clampRefresh(5.0), 0.001)
    }

    @Test fun pctClampedToRange() {
        assertEquals(1.0, clampPct(0.0), 0.001)
        assertEquals(100.0, clampPct(140.0), 0.001)
        assertEquals(80.0, clampPct(80.0), 0.001)
    }
}
