package com.example.servex.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test
    fun emptySeriesIsEmpty() {
        assertEquals(0, chartPoints(emptyList(), 100f, 50f, 4f).size)
    }

    @Test
    fun singlePointIsFiniteAndCentered() {
        val pts = chartPoints(listOf(7f), 100f, 50f, 4f)
        assertEquals(1, pts.size)
        assertTrue(pts[0].first.isFinite() && pts[0].second.isFinite())
    }

    @Test
    fun flatSeriesIsFinite() {
        val pts = chartPoints(listOf(5f, 5f, 5f), 100f, 50f, 4f)
        assertTrue(pts.all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test
    fun maxNearTopAndMinNearBottom() {
        val pts = chartPoints(listOf(0f, 10f), 100f, 50f, 4f)
        assertEquals(46f, pts[0].second, 0.001f)
        assertEquals(4f, pts[1].second, 0.001f)
    }
}
