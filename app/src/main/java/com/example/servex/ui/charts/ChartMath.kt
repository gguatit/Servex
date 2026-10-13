package com.example.servex.ui.charts

fun chartPoints(values: List<Float>, width: Float, height: Float, pad: Float): List<Pair<Float, Float>> {
    if (values.isEmpty()) return emptyList()
    val usableW = (width - 2f * pad).coerceAtLeast(1f)
    val usableH = (height - 2f * pad).coerceAtLeast(1f)
    val min = values.minOrNull() ?: 0f
    val max = values.maxOrNull() ?: 0f
    val span = (max - min).takeIf { it > 0.0001f } ?: 1f
    val step = if (values.size > 1) usableW / (values.size - 1) else 0f
    return values.mapIndexed { i, v ->
        val x = pad + step * i
        val norm = (v - min) / span
        val y = pad + (1f - norm) * usableH
        x to y
    }
}
