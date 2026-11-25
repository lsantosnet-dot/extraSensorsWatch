package net.extrasensors.watch.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formatação de números no padrão pt-BR, com sinal de menos tipográfico. */
object Format {
    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")
    const val MINUS = '−'
    const val NO_DATA = "—"

    private fun pattern(decimals: Int): DecimalFormat {
        val symbols = DecimalFormatSymbols(ptBr).apply {
            decimalSeparator = ','
            groupingSeparator = '.'
            minusSign = MINUS
        }
        val p = if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0"
        return DecimalFormat(p, symbols)
    }

    /** "915,2", "10.000", "−4,2". */
    fun number(value: Double, decimals: Int = 0): String {
        val s = pattern(decimals).format(value)
        // Evita "−0" / "−0,0" quando o valor arredonda para zero.
        return if (s.trimStart(MINUS).all { it == '0' || it == ',' || it == '.' }) s.trimStart(MINUS) else s
    }

    /** "+0,3", "−4,2", "0,0". */
    fun signed(value: Double, decimals: Int = 1): String {
        val s = number(value, decimals)
        val isZero = s.all { it == '0' || it == ',' || it == '.' }
        return if (value > 0 && !isZero) "+$s" else s
    }

    /** Duração: "0 min", "38 min", "1 h 12", "2 h". */
    fun duration(millis: Long): String {
        val totalMin = (millis.coerceAtLeast(0) / 60_000.0).toLong()
        if (totalMin < 60) return "$totalMin min"
        val h = totalMin / 60
        val m = totalMin % 60
        return if (m == 0L) "$h h" else "$h h " + m.toString().padStart(2, '0')
    }

    /** Duração compacta para complicação: "38m", "1h12". */
    fun durationCompact(millis: Long): String {
        val totalMin = millis.coerceAtLeast(0) / 60_000
        if (totalMin < 60) return "${totalMin}m"
        val h = totalMin / 60
        val m = totalMin % 60
        return if (m == 0L) "${h}h" else "${h}h" + m.toString().padStart(2, '0')
    }

    /** Cronômetro "24:10" ou "1:02:03". */
    fun clock(millis: Long): String {
        val s = millis.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** "faltam 1 h 20" a partir de milissegundos. */
    fun remaining(millis: Long): String {
        val totalMin = ((millis.coerceAtLeast(0) + 59_999) / 60_000)
        if (totalMin < 60) return "$totalMin min"
        val h = totalMin / 60
        val m = totalMin % 60
        return if (m == 0L) "$h h" else "$h h " + m.toString().padStart(2, '0')
    }

    fun roundHalfUp(v: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10 }
        return (abs(v) * f).roundToLong() / f * if (v < 0) -1 else 1
    }
}
