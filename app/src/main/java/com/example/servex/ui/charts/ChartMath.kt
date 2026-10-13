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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Maps values into the chart box using an explicit [lo]..[hi] range. */
fun chartPoints(
    values: List<Float>,
    width: Float,
    height: Float,
    pad: Float,
    lo: Float,
    hi: Float,
): List<Pair<Float, Float>> {
    if (values.isEmpty()) return emptyList()
    val usableW = (width - 2f * pad).coerceAtLeast(1f)
    val usableH = (height - 2f * pad).coerceAtLeast(1f)
    val span = (hi - lo).takeIf { abs(it) > 0.0001f } ?: 1f
    val step = if (values.size > 1) usableW / (values.size - 1) else 0f
    return values.mapIndexed { i, v ->
        val x = pad + step * i
        val norm = ((v - lo) / span).coerceIn(0f, 1f)
        x to (pad + (1f - norm) * usableH)
    }
}

/** Convenience overload: derives the range from the values themselves. */
fun chartPoints(values: List<Float>, width: Float, height: Float, pad: Float): List<Pair<Float, Float>> {
    val lo = values.minOrNull() ?: 0f
    val hi = values.maxOrNull() ?: 0f
    return chartPoints(values, width, height, pad, lo, hi)
}

/** A "nice" step (1, 2 or 5 x 10^n) that splits a span into roughly three parts. */
fun niceStep(span: Float): Float {
    if (span <= 0f || span.isNaN()) return 1f
    val raw = span / 3f
    val magnitude = 10f.pow(floor(log10(raw.toDouble())).toFloat())
    val normalized = raw / magnitude
    val nice = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
        normalized <= 5f -> 5f
        else -> 10f
    }
    return nice * magnitude
}

/**
 * Padded, nice-rounded axis range. [minSpan] stops small wiggles from filling the
 * whole chart (percent charts pass 20 so a 1% change is not drawn as a spike).
 */
fun niceRange(values: List<Float>, minSpan: Float = 0f): Pair<Float, Float> {
    if (values.isEmpty()) return 0f to max(minSpan, 1f)
    val lo0 = values.minOrNull() ?: 0f
    val hi0 = values.maxOrNull() ?: 0f
    val span = max(max(hi0 - lo0, minSpan), 0.001f)
    val step = niceStep(span)
    val lo = floor((lo0 - span * 0.10f) / step) * step
    val hi = ceil((hi0 + span * 0.10f) / step) * step
    return if (hi - lo < minSpan) lo to (lo + minSpan) else lo to hi
}

/** [count] evenly spaced tick values across [lo]..[hi]. */
fun niceTicks(lo: Float, hi: Float, count: Int = 3): List<Float> {
    if (count < 2 || hi <= lo) return listOf(lo, hi)
    val step = (hi - lo) / (count - 1)
    return List(count) { lo + step * it }
}

/** Cubic Bezier control points for one Catmull-Rom segment. */
data class Cubic(
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val ex: Float,
    val ey: Float,
)

/** Smooth (Catmull-Rom converted to cubic Bezier) segments between points. */
fun smoothSegments(points: List<Pair<Float, Float>>): List<Cubic> {
    if (points.size < 2) return emptyList()
    val out = ArrayList<Cubic>(points.size - 1)
    for (i in 0 until points.size - 1) {
        val p0 = points[if (i - 1 >= 0) i - 1 else i]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 < points.size) i + 2 else i + 1]
        out.add(
            Cubic(
                c1x = p1.first + (p2.first - p0.first) / 6f,
                c1y = p1.second + (p2.second - p0.second) / 6f,
                c2x = p2.first - (p3.first - p1.first) / 6f,
                c2y = p2.second - (p3.second - p1.second) / 6f,
                ex = p2.first,
                ey = p2.second,
            ),
        )
    }
    return out
}

/** What the axis numbers mean, and how small a range may be. */
enum class ChartUnit(val minSpan: Float) {
    PERCENT(20f),
    RATE(0f);

    fun format(v: Float): String = when (this) {
        PERCENT -> "%d%%".format(v.toInt())
        RATE -> com.example.servex.ui.format.formatRate(v.toLong())
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

/** Animates the axis range so a rescale slides instead of jumping. */
@Composable
fun rememberAnimatedRange(values: List<Float>, minSpan: Float): Pair<Float, Float> {
    val target = remember(values, minSpan) { niceRange(values, minSpan) }
    var previous by remember { mutableStateOf(target) }
    var from by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target) {
        from = previous
        previous = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
    }

    val t = progress.value
    return (from.first + (target.first - from.first) * t) to
        (from.second + (target.second - from.second) * t)
}
