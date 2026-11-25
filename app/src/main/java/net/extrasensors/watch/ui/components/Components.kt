package net.extrasensors.watch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text

object Palette {
    val Background = Color(0xFF000000)
    val Primary = Color(0xFFE6EAF0)
    val Secondary = Color(0xFF9AA4B2)
    val Support = Color(0xFFC3CAD4)
    val Surface = Color(0xFF12171E)
    val Border = Color(0xFF2A313C)

    val Baro = Color(0xFF7AB8FF)
    val Rain = Color(0xFF4DD0E1)
    val Climb = Color(0xFFF472B6)
    val Light = Color(0xFFFFC857)
    val Sun = Color(0xFFFF8F3F)
    val Sound = Color(0xFFFF8A7A)
    val Skin = Color(0xFF6EE7B7)
    val Sweat = Color(0xFFB69CFF)
}

private val Tabular = "tnum"

/** Diâmetro da tela em dp (telas redondas: largura = altura). */
@Composable
fun screenDp(): Dp = LocalConfiguration.current.screenWidthDp.dp

/**
 * Página centralizada no círculo: rola se o conteúdo não couber, com margens que mantêm
 * o conteúdo dentro da tela redonda.
 */
@Composable
fun RoundPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        val h = maxHeight
        val w = maxWidth
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = h)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = w * 0.12f, vertical = h * 0.12f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
fun PageLabel(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.16.em,
        textAlign = TextAlign.Center,
    )
}

/** Número principal (~21% do diâmetro) com unidade ao lado, números tabulares. */
@Composable
fun BigValue(value: String, unit: String?, sizeFraction: Float = 0.21f, description: String? = null) {
    val density = LocalDensity.current
    val maxSize: TextUnit = with(density) { (screenDp() * sizeFraction).toSp() }
    val unitSize: TextUnit = with(density) { (screenDp() * 0.05f).toSp() }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { contentDescription = description ?: "$value ${unit.orEmpty()}" },
    ) {
        BasicText(
            text = value,
            maxLines = 1,
            style = TextStyle(
                color = Palette.Primary,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = Tabular,
                letterSpacing = (-0.03).em,
                lineHeight = maxSize,
            ),
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = maxSize, stepSize = 2.sp),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (unit != null) {
            Text(
                text = unit,
                color = Palette.Secondary,
                fontSize = unitSize,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false, textColor: Color? = null) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .padding(top = 6.dp)
            .then(
                if (filled) Modifier.background(color, shape)
                else Modifier.border(1.5.dp, color, shape)
            )
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = textColor ?: if (filled) Color(0xFF1A0E04) else Palette.Primary,
            fontSize = 14.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            style = TextStyle(fontFeatureSettings = Tabular),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Secondary(text: String, color: Color = Palette.Secondary, size: TextUnit = 13.sp, top: Dp = 4.dp) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        textAlign = TextAlign.Center,
        style = TextStyle(fontFeatureSettings = Tabular),
        modifier = Modifier.padding(top = top),
    )
}

/** Mini gráfico de linha (com área opcional). */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    description: String,
    height: Dp = 34.dp,
    fill: Color? = null,
    minRange: Double = 1.0,
) {
    Canvas(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(0.85f)
            .height(height)
            .semantics { contentDescription = description },
    ) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val range = maxOf(hi - lo, minRange)
        val mid = (hi + lo) / 2
        val top = mid + range / 2
        val stroke = 2.5.dp.toPx()
        val usableH = size.height - stroke
        fun x(i: Int) = i * size.width / (values.size - 1)
        fun y(v: Double) = (stroke / 2 + (top - v) / range * usableH).toFloat()
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        if (fill != null) {
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, fill)
        }
        drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Barra horizontal com marcador (escala) ou preenchimento (progresso). */
@Composable
fun Meter(
    fraction: Float,
    color: Color,
    description: String,
    marker: Boolean,
    tickAt: Float? = null,
) {
    Canvas(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(0.8f)
            .height(18.dp)
            .semantics { contentDescription = description },
    ) {
        val barH = 8.dp.toPx()
        val y = size.height / 2
        val r = barH / 2
        if (marker) {
            drawLine(
                brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.2f), color)),
                start = Offset(r, y), end = Offset(size.width - r, y),
                strokeWidth = barH, cap = StrokeCap.Round,
            )
            tickAt?.let {
                val tx = r + it.coerceIn(0f, 1f) * (size.width - 2 * r)
                drawLine(Palette.Secondary, Offset(tx, y - barH), Offset(tx, y + barH), strokeWidth = 1.5.dp.toPx())
            }
            val mx = r + fraction.coerceIn(0f, 1f) * (size.width - 2 * r)
            drawLine(Color.White, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        } else {
            drawLine(color.copy(alpha = 0.22f), Offset(r, y), Offset(size.width - r, y), strokeWidth = barH, cap = StrokeCap.Round)
            val end = r + fraction.coerceIn(0f, 1f) * (size.width - 2 * r)
            if (fraction > 0f) drawLine(color, Offset(r, y), Offset(end, y), strokeWidth = barH, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun MeterLegend(left: String, right: String) {
    Row(
        Modifier.fillMaxWidth(0.8f).padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(left, color = Palette.Secondary, fontSize = 11.sp)
        Text(right, color = Palette.Secondary, fontSize = 11.sp)
    }
}

/** Botão principal de ação, altura mínima de 48 dp. */
@Composable
fun ActionButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF0E0A1A)),
        modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
    ) {
        Text(text, color = Palette.Primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Estado sem dado (seção 6 da especificação): nunca um número inventado. */
@Composable
fun StateMessage(
    label: String,
    color: Color,
    title: String,
    body: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    RoundPage {
        PageLabel(label, color)
        Spacer(Modifier.height(10.dp))
        Text(title, color = Palette.Primary, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (body != null) Secondary(body, top = 6.dp)
        if (actionText != null && onAction != null) SecondaryButton(actionText, onAction)
    }
}

@Composable
fun Loading(label: String, color: Color) {
    RoundPage {
        PageLabel(label, color)
        Spacer(Modifier.height(14.dp))
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Secondary("Aguardando leitura…", top = 10.dp)
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
