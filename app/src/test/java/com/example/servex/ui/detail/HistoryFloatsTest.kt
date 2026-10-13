package com.example.servex.ui.detail

import com.example.servex.domain.model.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFloatsTest {
    @Test fun mapsPointsToFloats() {
        val pts = listOf(HistoryPoint(1, 10.0), HistoryPoint(2, 20.5))
        assertEquals(listOf(10f, 20.5f), toFloats(pts))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals(emptyList<Float>(), toFloats(emptyList()))
    }
}
