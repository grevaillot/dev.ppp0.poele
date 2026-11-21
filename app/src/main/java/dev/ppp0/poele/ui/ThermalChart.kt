package dev.ppp0.poele.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.ppp0.poele.net.Thermal

private val AIR = Color(0xFF1565C0)
private val SET = Color(0xFFE65100)
private val GRID = Color(0x1F000000)

/**
 * Ambiante (trait plein) et consigne (pointillés), un échantillon toutes les 15 min, le dernier = maintenant.
 * Les deux courbes sont tracées de la même façon, en paliers centrés sur chaque échantillon : ce qui est
 * aligné dans les données l'est à l'écran. Repères aux heures réelles (00:00, 06:00, 12:00, 18:00).
 */
@Composable
fun ThermalChart(t: Thermal, modifier: Modifier = Modifier) {
    val range = t.yRange()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall.copy(color = muted)
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(AIR, "ambiante", dashed = false)
            Legend(SET, "consigne", dashed = true)
            Text("${t.spanMinutes / 60} h · pas de 15 min", style = small)
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 6.dp)) {
            val left = 30.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx(); val right = 4.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            val n = maxOf(t.points, 1)
            val step = w / n                                // largeur d'un échantillon
            fun xc(i: Int) = left + step * (i + 0.5f)       // centre de l'échantillon i
            fun y(v: Float) = top + h * (1 - (v - range.start) / (range.endInclusive - range.start))

            val mid = (range.start + range.endInclusive) / 2
            for (v in listOf(range.start, mid, range.endInclusive)) {
                drawLine(GRID, Offset(left, y(v)), Offset(left + w, y(v)), 1f)
                val lbl = measurer.measure("${v.toInt()}°", small)
                drawText(lbl, topLeft = Offset(left - lbl.size.width - 4.dp.toPx(), y(v) - lbl.size.height / 2))
            }
            // Repères aux heures réelles (00:00 → date, 06:00, 12:00, 18:00), calculées depuis le dernier point.
            for ((i, text) in t.ticks()) {
                drawLine(GRID, Offset(xc(i), top), Offset(xc(i), top + h), 1f)
                val lbl = measurer.measure(text, small)
                val lx = (xc(i) - lbl.size.width / 2).coerceIn(left, left + w - lbl.size.width)
                drawText(lbl, topLeft = Offset(lx, top + h + 2.dp.toPx()))
            }

            /** Paliers centrés : chaque échantillon couvre [i, i+1[ en largeur ; un trou coupe la courbe. */
            fun steps(values: List<Float?>): Path {
                val p = Path(); var prev: Float? = null
                values.forEachIndexed { i, v ->
                    val x0 = left + step * i; val x1 = x0 + step
                    if (v == null) { prev = null; return@forEachIndexed }
                    if (prev == null) p.moveTo(x0, y(v)) else p.lineTo(x0, y(v))
                    p.lineTo(x1, y(v)); prev = v
                }
                return p
            }
            drawPath(steps(t.setpoint), SET, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            drawPath(steps(t.air), AIR, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
        }
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}
