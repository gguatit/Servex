package com.example.servex.ui

import com.example.servex.domain.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusStyleTest {
    @Test fun everyStatusHasDistinctIcon() {
        val icons = ServerStatus.values().map { statusStyle(it).iconRes }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test fun labelKeysAreStable() {
        assertEquals("normal", statusStyle(ServerStatus.NORMAL).labelKey)
        assertEquals("warning", statusStyle(ServerStatus.WARNING).labelKey)
        assertEquals("error", statusStyle(ServerStatus.ERROR).labelKey)
        assertEquals("offline", statusStyle(ServerStatus.OFFLINE).labelKey)
    }

    @Test fun everyStatusHasADistinctColor() {
        val colors = ServerStatus.values().map { statusStyle(it).color }
        assertEquals(colors.size, colors.toSet().size)
    }
}
