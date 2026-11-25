package net.extrasensors.shared

/** Valor do índice UV no início de uma hora (epoch millis). */
data class UvHour(val hourStart: Long, val uvi: Double)

object UvMath {
    const val HOUR_MS = 3_600_000L

    /** 1 unidade de índice UV = 25 mW/m² eritêmicos = 90 J/m² por hora = 0,9 SED/h. */
    const val SED_PER_UVI_HOUR = 0.9

    /** OMS: proteção recomendada a partir do índice 3. */
    const val PROTECTION_UVI = 3.0

    /** OMS: evitar o sol do meio do dia a partir do índice 8. */
    const val VERY_HIGH_UVI = 8.0

    /** Dose (SED) recebida em [durationMs] com índice UV [uvi]. */
    fun doseSed(uvi: Double, durationMs: Long): Double =
        uvi.coerceAtLeast(0.0) * SED_PER_UVI_HOUR * durationMs / HOUR_MS.toDouble()

    /**
     * Índice UV no instante [t], interpolado linearmente entre as horas da previsão.
     * Retorna null fora da faixa coberta (sem extrapolar além de 1 h).
     */
    fun interpolate(hours: List<UvHour>, t: Long): Double? {
        if (hours.isEmpty()) return null
        val sorted = hours.sortedBy { it.hourStart }
        if (t < sorted.first().hourStart || t > sorted.last().hourStart + HOUR_MS) return null
        val after = sorted.indexOfFirst { it.hourStart > t }
        if (after == -1) return sorted.last().uvi
        if (after == 0) return sorted.first().uvi
        val a = sorted[after - 1]
        val b = sorted[after]
        if (b.hourStart - a.hourStart > 2 * HOUR_MS) return a.uvi
        val f = (t - a.hourStart).toDouble() / (b.hourStart - a.hourStart)
        return a.uvi + (b.uvi - a.uvi) * f
    }

    /** Rótulo da OMS para o índice. */
    fun category(uvi: Double): String = when {
        uvi < 3 -> "Baixo"
        uvi < 6 -> "Moderado"
        uvi < 8 -> "Alto"
        uvi < 11 -> "Muito alto"
        else -> "Extremo"
    }
}

/** Tipos de pele de Fitzpatrick e a dose eritematosa mínima (MED) aproximada, em SED. */
enum class SkinType(val label: String, val description: String, val medSed: Double) {
    I("Tipo I", "sempre queima, nunca bronzeia", 2.0),
    II("Tipo II", "queima fácil, quase não bronzeia", 2.5),
    III("Tipo III", "queima às vezes, bronzeia aos poucos", 3.5),
    IV("Tipo IV", "raramente queima, bronzeia fácil", 4.5),
    V("Tipo V", "muito raramente queima", 6.0),
    VI("Tipo VI", "nunca queima", 10.0),
}
