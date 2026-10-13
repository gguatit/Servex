package com.example.servex.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.servex.ui.theme.ServexColors

/**
 * Line chart with a numeric y-axis, auto-scaled range and a smoothed curve.
 * [unit] decides how the axis numbers are formatted (percent or per-second rate).
 */
@Composable
fun LineChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    unit: ChartUnit = ChartUnit.PERCENT,
) {
    val series = rememberAnimatedSeries(values)
    val (lo, hi) = rememberAnimatedRange(series, unit.minSpan)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = ServexColors.onSurfaceVariant, fontSize = 9.sp)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(ServexColors.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        val leftPad = 46.dp.toPx()
        val rightPad = 10.dp.toPx()
        val topPad = 12.dp.toPx()
        val bottomPad = 8.dp.toPx()
        val chartW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val span = (hi - lo).takeIf { it > 0.0001f } ?: 1f

        // grid lines + numeric labels on the left
        for (tick in niceTicks(lo, hi, 3)) {
            val y = topPad + (1f - (tick - lo) / span) * chartH
            drawLine(
                ServexColors.onSurfaceVariant.copy(alpha = 0.18f),
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1f,
            )
            val layout = measurer.measure(AnnotatedString(unit.format(tick)), labelStyle)
            drawText(
                layout,
                topLeft = Offset(leftPad - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f),
            )
        }

        val pts = chartPoints(series, chartW, chartH, 0f, lo, hi)
            .map { (x, y) -> (x + leftPad) to (y + topPad) }
        if (pts.size < 2) return@Canvas

        val line = Path().apply {
            moveTo(pts.first().first, pts.first().second)
            smoothSegments(pts).forEach { c -> cubicTo(c.c1x, c.c1y, c.c2x, c.c2y, c.ex, c.ey) }
        }
        val base = topPad + chartH
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().first, base)
            lineTo(pts.first().first, base)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)),
                startY = topPad,
                endY = base,
            ),
        )
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawLine(
            ServexColors.onSurfaceVariant.copy(alpha = 0.30f),
            start = Offset(leftPad, base),
            end = Offset(size.width - rightPad, base),
            strokeWidth = 1f,
        )
        val head = pts.last()
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(head.first, head.second))

        // current value, top-right
        val current = measurer.measure(AnnotatedString(unit.format(series.last())), labelStyle)
        drawText(
            current,
            topLeft = Offset(size.width - rightPad - current.size.width, 2.dp.toPx()),
        )
    }
}
