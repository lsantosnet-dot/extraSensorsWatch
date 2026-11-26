package net.extrasensors.shared

import kotlin.math.ln
import kotlin.math.sqrt

/** Situação da medição de HRV em relação à sua própria faixa recente. */
enum class HrvStatus(val label: String) { BELOW("Abaixo da sua faixa"), NORMAL("Na sua faixa"), ABOVE("Acima da sua faixa") }

/** Faixa pessoal: média e desvio de ln(RMSSD) das medições dos 7 dias anteriores. */
data class HrvBaseline(val meanLn: Double, val sdLn: Double, val count: Int) {
    val lowMs: Double get() = kotlin.math.exp(meanLn - sdLn)
    val highMs: Double get() = kotlin.math.exp(meanLn + sdLn)
    val meanMs: Double get() = kotlin.math.exp(meanLn)

    fun status(rmssdMs: Double): HrvStatus {
        val v = ln(rmssdMs)
        return when {
            v < meanLn - sdLn -> HrvStatus.BELOW
            v > meanLn + sdLn -> HrvStatus.ABOVE
            else -> HrvStatus.NORMAL
        }
    }
}

object HrvStats {
    const val DAYS = 7
    const val MIN_MEASUREMENTS = 3
    private const val DAY_MS = 86_400_000L

    /**
     * Faixa pessoal a partir das medições anteriores a [beforeMillis] nos últimos 7 dias.
     * Usa ln(RMSSD), prática comum porque o RMSSD tem distribuição assimétrica.
     * O desvio tem um piso de 0,05 para a faixa não ficar estreita demais com poucas medições.
     */
    fun baseline(measurements: List<HrvMeasurement>, beforeMillis: Long): HrvBaseline? {
        val window = measurements.filter { it.timeMillis < beforeMillis && it.timeMillis >= beforeMillis - DAYS * DAY_MS && it.rmssdMs > 0 }
        if (window.size < MIN_MEASUREMENTS) return null
        val l = window.map { ln(it.rmssdMs) }
        val mean = l.average()
        val sd = sqrt(l.sumOf { (it - mean) * (it - mean) } / (l.size - 1))
        return HrvBaseline(mean, maxOf(sd, 0.05), window.size)
    }
}
