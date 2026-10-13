package com.example.servex.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRangeTest {
    @Test fun emptyRangeHasWidth() {
        val (lo, hi) = niceRange(emptyList(), 20f)
        assertTrue(hi > lo)
    }

    @Test fun rangeCoversValues() {
        val (lo, hi) = niceRange(listOf(30f, 45f))
        assertTrue(lo <= 30f)
        assertTrue(hi >= 45f)
    }

    @Test fun minSpanKeepsSmallChangesVisible() {
        val (lo, hi) = niceRange(listOf(50f, 51f), 20f)
        assertTrue(hi - lo >= 20f)
    }

    @Test fun ticksAreEvenlySpaced() {
        assertEquals(listOf(0f, 50f, 100f), niceTicks(0f, 100f, 3))
    }

    @Test fun smoothSegmentsCountsOnePerGap() {
        val pts = listOf(0f to 0f, 10f to 10f, 20f to 0f)
        assertEquals(2, smoothSegments(pts).size)
    }

    @Test fun smoothSegmentsEmptyForSinglePoint() {
        assertTrue(smoothSegments(listOf(0f to 0f)).isEmpty())
    }

    @Test fun chartPointsClampToRange() {
        val pts = chartPoints(listOf(-5f, 150f), 100f, 50f, 0f, 0f, 100f)
        assertEquals(50f, pts[0].second, 0.01f)
        assertEquals(0f, pts[1].second, 0.01f)
    }
}
