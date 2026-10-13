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
import androidx.compose.ui.unit.dp
import com.example.servex.ui.theme.ServexColors

@Composable
fun LineChart(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val series = rememberAnimatedSeries(values)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(ServexColors.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        val pad = 6.dp.toPx()
        val base = size.height - pad
        val pts = chartPoints(series, size.width, size.height, pad)
        if (pts.size < 2) return@Canvas

        val line = Path().apply {
            moveTo(pts.first().first, pts.first().second)
            pts.drop(1).forEach { lineTo(it.first, it.second) }
        }
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
                startY = pad,
                endY = base,
            ),
        )
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        drawLine(
            ServexColors.onSurfaceVariant.copy(alpha = 0.25f),
            start = Offset(pad, base),
            end = Offset(size.width - pad, base),
            strokeWidth = 1f,
        )
        val head = pts.last()
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(head.first, head.second))
    }
}
