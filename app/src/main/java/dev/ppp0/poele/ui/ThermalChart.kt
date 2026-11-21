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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.ppp0.poele.net.Thermal

private val AIR = Color(0xFF1565C0)
private val SET = Color(0xFFE65100)
private val GRID = Color(0x1F000000)

/** Ambiante (trait plein) et consigne (paliers pointillés) sur 24 h, de −24 h à maintenant. */
@Composable
fun ThermalChart(t: Thermal, modifier: Modifier = Modifier) {
    val range = t.yRange()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(AIR, "ambiante", dashed = false)
            Legend(SET, "consigne", dashed = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.height(150.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${range.endInclusive.toInt()}°", style = small, color = muted)
                Text("${((range.start + range.endInclusive) / 2).toInt()}°", style = small, color = muted)
                Text("${range.start.toInt()}°", style = small, color = muted)
            }
            Canvas(Modifier.weight(1f).height(150.dp)) {
                val n = maxOf(t.air.size, t.setpoint.size, 2)
                fun x(i: Int) = size.width * i / (n - 1)
                fun y(v: Float) = size.height * (1 - (v - range.start) / (range.endInclusive - range.start))
                // Grille : 3 horizontales, une verticale toutes les 6 h.
                for (k in 0..2) { val gy = size.height * k / 2; drawLine(GRID, Offset(0f, gy), Offset(size.width, gy), 1f) }
                for (h in listOf(6, 12, 18)) { val gx = size.width * h / 24; drawLine(GRID, Offset(gx, 0f), Offset(gx, size.height), 1f) }
                // Consigne en paliers.
                val sp = Path(); var open = false
                t.setpoint.forEachIndexed { i, v ->
                    if (v == null) { open = false; return@forEachIndexed }
                    if (!open) { sp.moveTo(x(i), y(v)); open = true } else sp.lineTo(x(i), y(v))
                    sp.lineTo(x(minOf(i + 1, n - 1)), y(v))
                }
                drawPath(sp, SET, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                // Ambiante.
                val ap = Path(); open = false
                t.air.forEachIndexed { i, v ->
                    if (v == null) { open = false; return@forEachIndexed }
                    if (!open) { ap.moveTo(x(i), y(v)); open = true } else ap.lineTo(x(i), y(v))
                }
                drawPath(ap, AIR, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−24 h", "−18 h", "−12 h", "−6 h", "maint.").forEach { s ->
                Text(s, style = small, color = muted)
            }
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
