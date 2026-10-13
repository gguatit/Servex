package com.example.servex.domain

data class AppSettings(
    val refreshIntervalS: Int = 5,
    val warningPct: Double = 75.0,
    val criticalPct: Double = 90.0,
    val alertsEnabled: Boolean = false,
)
