package com.example.servex.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val byteUnits = arrayOf("B", "KB", "MB", "GB", "TB")

fun formatBytes(bytes: Long): String {
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < byteUnits.lastIndex) {
        v /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, byteUnits[i])
}

fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

fun formatTimestamp(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
