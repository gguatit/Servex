package com.example.servex.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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

/**
 * Smoothly blends the previously drawn series into [values] on every update, so a
 * refresh morphs the line instead of snapping to a new shape.
 */
@Composable
fun rememberAnimatedSeries(values: List<Float>): List<Float> {
    var from by remember { mutableStateOf(values) }
    var target by remember { mutableStateOf(values) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(values) {
        from = target
        target = values
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
    }

    val t = progress.value
    if (t >= 1f || from.size != target.size) return target
    return List(target.size) { i -> from[i] + (target[i] - from[i]) * t }
}
