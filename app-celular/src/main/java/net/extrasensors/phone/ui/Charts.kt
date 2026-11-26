package net.extrasensors.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.extrasensors.phone.stats.SkinPoint
import net.extrasensors.phone.stats.SunPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

object ChartColors {
    val Sun = Color(0xFFF57C1F)
    val SunDark = Color(0xFFFF9F52)
    val UvLight = Color(0xFF6A3FD6)
    val UvDark = Color(0xFFB69CFF)
    val SkinLight = Color(0xFF1F8A66)
    val SkinDark = Color(0xFF6EE7B7)

    @Composable fun sun() = if (isSystemInDarkTheme()) SunDark else Sun
    @Composable fun uv() = if (isSystemInDarkTheme()) UvDark else UvLight
    @Composable fun skin() = if (isSystemInDarkTheme()) SkinDark else SkinLight
}

private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")
private fun label(t: Long, hourly: Boolean, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(t).atZone(zone)
    return if (hourly) "${z.hour}h" else z.format(dayFmt)
}

private data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun DrawScope.axisText(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, TextStyle(fontSize = 11.sp, color = color))
    val dx = when {
        alignRight -> -layout.size.width.toFloat()
        center -> -layout.size.width / 2f
        else -> 0f
    }
    drawText(layout, topLeft = Offset(x + dx, y - layout.size.height / 2f))
}

private fun DrawScope.dashed(color: Color, y: Float, f: Frame) {
    drawLine(color, Offset(f.left, y), Offset(f.right, y), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
}

/** Passo "redondo" para o eixo de minutos. */
private fun niceMinutes(maxMin: Double, hourly: Boolean): Double = when {
    hourly -> 60.0
    maxMin <= 60 -> 60.0
    maxMin <= 120 -> 120.0
    else -> ceil(maxMin / 60.0) * 60.0
}

private fun minutesLabel(m: Double): String =
    if (m < 60 || m % 60 != 0.0) "${m.toInt()} min" else "${(m / 60).toInt()} h"

/**
 * Barras de minutos ao sol (eixo da esquerda) e linha do índice UV (eixo da direita),
 * com referências da OMS em UV 3 e UV 8. Toque numa coluna para ver os valores.
 */
@Composable
fun SunChart(
    points: List<SunPoint>,
    hourly: Boolean,
    zone: ZoneId,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val sun = ChartColors.sun()
    val uvColor = ChartColors.uv()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxMin = niceMinutes(points.maxOfOrNull { it.exposedMs / 60_000.0 } ?: 0.0, hourly)
    val maxUv = max(11.0, points.mapNotNull { it.uv }.maxOrNull() ?: 0.0)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics { contentDescription = "Gráfico de minutos ao sol e índice UV" }
            .pointerInput(points) {
                detectTapGestures { o ->
                    val left = 44.dp.toPx(); val right = size.width - 34.dp.toPx()
                    if (points.isEmpty() || o.x < left || o.x > right) { onSelect(null); return@detectTapGestures }
                    val slot = (right - left) / points.size
                    onSelect(((o.x - left) / slot).toInt().coerceIn(0, points.lastIndex))
                }
            },
    ) {
        val f = Frame(44.dp.toPx(), 8.dp.toPx(), size.width - 34.dp.toPx(), size.height - 24.dp.toPx())
        // Grade e eixo da esquerda (minutos).
        listOf(0.0, maxMin / 2, maxMin).forEach { m ->
            val y = (f.bottom - m / maxMin * f.height).toFloat()
            drawLine(grid, Offset(f.left, y), Offset(f.right, y), strokeWidth = 1f)
            axisText(tm, minutesLabel(m), f.left - 6.dp.toPx(), y, axis, alignRight = true)
        }
        // Eixo da direita (UV) e referências da OMS.
        fun yUv(v: Double) = (f.bottom - v / maxUv * f.height).toFloat()
        listOf(3.0 to "UV 3", 8.0 to "UV 8").forEach { (v, t) ->
            dashed(uvColor.copy(alpha = 0.55f), yUv(v), f)
            axisText(tm, t, f.right + 4.dp.toPx(), yUv(v), uvColor)
        }
        if (points.isEmpty()) return@Canvas
        val slot = f.width / points.size
        val barW = max(1f, slot * 0.62f)
        points.forEachIndexed { i, p ->
            val h = (p.exposedMs / 60_000.0 / maxMin * f.height).toFloat().coerceAtMost(f.height)
            if (h > 0) {
                val alpha = if (selected == null || selected == i) 1f else 0.35f
                drawRect(
                    sun.copy(alpha = alpha),
                    topLeft = Offset(f.left + i * slot + (slot - barW) / 2, f.bottom - h),
                    size = Size(barW, h),
                )
            }
        }
        // Linha do UV (interrompida onde não há previsão).
        val path = Path()
        var started = false
        points.forEachIndexed { i, p ->
            val uv = p.uv
            if (uv == null) { started = false; return@forEachIndexed }
            val x = f.left + i * slot + slot / 2
            if (!started) { path.moveTo(x, yUv(uv)); started = true } else path.lineTo(x, yUv(uv))
        }
        drawPath(path, uvColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (points.size <= 31) {
            points.forEachIndexed { i, p ->
                p.uv?.let { drawCircle(uvColor, 2.5.dp.toPx(), Offset(f.left + i * slot + slot / 2, yUv(it))) }
            }
        }
        // Rótulos do eixo X.
        val step = if (hourly) 6 else max(1, ceil(points.size / 6.0).toInt())
        for (i in points.indices step step) {
            axisText(tm, label(points[i].start, hourly, zone), f.left + i * slot + slot / 2, f.bottom + 12.dp.toPx(), axis, center = true)
        }
        selected?.let { i ->
            val x = f.left + i * slot + slot / 2
            drawLine(axis.copy(alpha = 0.6f), Offset(x, f.top), Offset(x, f.bottom), strokeWidth = 1.dp.toPx())
        }
    }
}

/**
 * Temperatura da pele: linha das leituras ("Hoje") ou das médias diárias com traço de mínima e
 * máxima, mais a média do período tracejada.
 */
@Composable
fun SkinChart(
    points: List<SkinPoint>,
    hourly: Boolean,
    from: Long,
    to: Long,
    zone: ZoneId,
    baseline: Double?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val color = ChartColors.skin()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lo = (points.minOfOrNull { it.min } ?: 32.0) - 0.3
    val hi = (points.maxOfOrNull { it.max } ?: 35.0) + 0.3
    val span = (to - from).coerceAtLeast(1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics { contentDescription = "Gráfico da temperatura da pele" }
            .pointerInput(points) {
                detectTapGestures { o ->
                    val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
                    if (points.isEmpty()) return@detectTapGestures
                    val xs = points.map { left + (it.start - from).toFloat() / span * (right - left) }
                    val i = xs.indices.minBy { kotlin.math.abs(xs[it] - o.x) }
                    onSelect(if (kotlin.math.abs(xs[i] - o.x) < 40.dp.toPx()) i else null)
                }
            },
    ) {
        val f = Frame(44.dp.toPx(), 8.dp.toPx(), size.width - 12.dp.toPx(), size.height - 24.dp.toPx())
        fun y(v: Double) = (f.bottom - (v - lo) / (hi - lo) * f.height).toFloat()
        fun x(t: Long) = f.left + (t - from).toFloat() / span * f.width
        listOf(lo + 0.3, (lo + hi) / 2, hi - 0.3).forEach { v ->
            drawLine(grid, Offset(f.left, y(v)), Offset(f.right, y(v)), strokeWidth = 1f)
            axisText(tm, "%.1f°".format(v).replace('.', ','), f.left - 6.dp.toPx(), y(v), axis, alignRight = true)
        }
        baseline?.let { dashed(color.copy(alpha = 0.6f), y(it), f) }
        if (points.isEmpty()) return@Canvas
        val centerShift = if (hourly) 0f else f.width / ((span / 86_400_000L).coerceAtLeast(1)) / 2
        if (!hourly) {
            points.forEach { p ->
                if (p.max > p.min) {
                    drawLine(color.copy(alpha = 0.35f), Offset(x(p.start) + centerShift, y(p.min)),
                        Offset(x(p.start) + centerShift, y(p.max)), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        val path = Path()
        points.forEachIndexed { i, p ->
            val px = x(p.start) + centerShift
            if (i == 0) path.moveTo(px, y(p.mean)) else path.lineTo(px, y(p.mean))
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEachIndexed { i, p ->
            val r = if (i == selected) 5.dp.toPx() else 3.dp.toPx()
            drawCircle(color, r, Offset(x(p.start) + centerShift, y(p.mean)))
        }
        // Rótulos do eixo X.
        if (hourly) {
            for (h in 0..18 step 6) axisText(tm, "${h}h", x(from + h * 3_600_000L), f.bottom + 12.dp.toPx(), axis, center = true)
        } else {
            val days = (span / 86_400_000L).toInt().coerceAtLeast(1)
            val step = max(1, ceil(days / 6.0).toInt())
            for (d in 0 until days step step) {
                val t = from + d * 86_400_000L
                axisText(tm, label(t, false, zone), x(t) + centerShift, f.bottom + 12.dp.toPx(), axis, center = true)
            }
        }
    }
}

// ---------------- Estresse ----------------

object StressColors {
    val HrvLight = Color(0xFF0277BD)
    val HrvDark = Color(0xFF7DD3FC)
    val EdaLight = Color(0xFFC2410C)
    val EdaDark = Color(0xFFFDBA74)
    @Composable fun hrv() = if (isSystemInDarkTheme()) HrvDark else HrvLight
    @Composable fun eda() = if (isSystemInDarkTheme()) EdaDark else EdaLight
}

/**
 * HRV: um ponto por medição (RMSSD), ligado por linha, e a faixa pessoal (7 dias anteriores)
 * sombreada em cada medição. Pontos abaixo da faixa ficam vazados.
 */
@Composable
fun HrvChart(
    points: List<net.extrasensors.phone.stats.HrvPoint>,
    from: Long,
    to: Long,
    zone: ZoneId,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val color = StressColors.hrv()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val values = points.flatMap { listOfNotNull(it.rmssdMs, it.low, it.high) }
    val lo = ((values.minOrNull() ?: 20.0) * 0.85).coerceAtLeast(0.0)
    val hi = (values.maxOrNull() ?: 80.0) * 1.1
    val span = (to - from).coerceAtLeast(1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = "Gráfico de HRV (RMSSD) com a faixa pessoal" }
            .pointerInput(points) {
                detectTapGestures { o ->
                    if (points.isEmpty()) return@detectTapGestures
                    val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
                    val xs = points.map { left + (it.timeMillis - from).toFloat() / span * (right - left) }
                    val i = xs.indices.minBy { kotlin.math.abs(xs[it] - o.x) }
                    onSelect(if (kotlin.math.abs(xs[i] - o.x) < 40.dp.toPx()) i else null)
                }
            },
    ) {
        val f = Frame(44.dp.toPx(), 8.dp.toPx(), size.width - 12.dp.toPx(), size.height - 24.dp.toPx())
        fun y(v: Double) = (f.bottom - (v - lo) / (hi - lo) * f.height).toFloat()
        fun x(t: Long) = f.left + (t - from).toFloat() / span * f.width
        listOf(lo, (lo + hi) / 2, hi).forEach { v ->
            drawLine(grid, Offset(f.left, y(v)), Offset(f.right, y(v)), strokeWidth = 1f)
            axisText(tm, "${v.toInt()} ms", f.left - 6.dp.toPx(), y(v), axis, alignRight = true)
        }
        if (points.isEmpty()) return@Canvas
        points.forEach { p ->
            if (p.low != null && p.high != null) {
                drawLine(color.copy(alpha = 0.18f), Offset(x(p.timeMillis), y(p.low)), Offset(x(p.timeMillis), y(p.high)),
                    strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeMillis), y(p.rmssdMs)) else path.lineTo(x(p.timeMillis), y(p.rmssdMs)) }
        drawPath(path, color.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEachIndexed { i, p ->
            val r = (if (i == selected) 5.dp else 3.5.dp).toPx()
            val c = Offset(x(p.timeMillis), y(p.rmssdMs))
            if (p.status == net.extrasensors.shared.HrvStatus.BELOW) drawCircle(color, r, c, style = Stroke(2.dp.toPx()))
            else drawCircle(color, r, c)
        }
        val days = (span / 86_400_000L).toInt().coerceAtLeast(1)
        val step = max(1, ceil(days / 6.0).toInt())
        for (d in 0 until days step step) {
            val t = from + d * 86_400_000L
            axisText(tm, label(t, false, zone), x(t), f.bottom + 12.dp.toPx(), axis, center = true)
        }
    }
}

/** Curva de uma sessão de EDA: média de 10 s, faixa mínimo–máximo e marcas dos picos de ativação. */
@Composable
fun EdaChart(points: List<net.extrasensors.phone.data.EdaPointRow>, start: Long, end: Long) {
    val tm = rememberTextMeasurer()
    val color = StressColors.eda()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lo = (points.minOfOrNull { it.min } ?: 0.0)
    val hi = (points.maxOfOrNull { it.max } ?: 1.0).let { if (it - lo < 0.2) lo + 0.2 else it }
    val span = (end - start).coerceAtLeast(1)
    Canvas(
        Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Condutância da pele na sessão" },
    ) {
        val f = Frame(52.dp.toPx(), 8.dp.toPx(), size.width - 12.dp.toPx(), size.height - 28.dp.toPx())
        fun y(v: Double) = (f.bottom - (v - lo) / (hi - lo) * f.height).toFloat()
        fun x(t: Long) = f.left + (t - start).toFloat() / span * f.width
        listOf(lo, (lo + hi) / 2, hi).forEach { v ->
            drawLine(grid, Offset(f.left, y(v)), Offset(f.right, y(v)), strokeWidth = 1f)
            axisText(tm, "%.2f µS".format(v).replace('.', ','), f.left - 6.dp.toPx(), y(v), axis, alignRight = true)
        }
        if (points.isEmpty()) return@Canvas
        points.forEach { p ->
            drawLine(color.copy(alpha = 0.22f), Offset(x(p.timeMillis), y(p.min)), Offset(x(p.timeMillis), y(p.max)),
                strokeWidth = max(2f, f.width / points.size), cap = StrokeCap.Butt)
            if (p.peaks > 0) drawCircle(color, 3.dp.toPx(), Offset(x(p.timeMillis), f.bottom + 6.dp.toPx()))
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeMillis), y(p.mean)) else path.lineTo(x(p.timeMillis), y(p.mean)) }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val minutes = (span / 60_000L).toInt()
        val step = max(1, ceil(minutes / 5.0).toInt())
        for (m in 0..minutes step step) {
            axisText(tm, "$m min", x(start + m * 60_000L), f.bottom + 18.dp.toPx(), axis, center = true)
        }
    }
}
