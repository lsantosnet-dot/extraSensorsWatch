package net.extrasensors.watch.domain

import kotlin.math.pow

/** Fórmulas barométricas (as mesmas de android.hardware.SensorManager.getAltitude). */
object Barometry {
    const val STANDARD_P0 = 1013.25

    /** Altitude em metros para pressão [p] (hPa) e pressão ao nível do mar [p0]. */
    fun altitude(p0: Double, p: Double): Double =
        44330.0 * (1.0 - (p / p0).pow(1.0 / 5.255))

    /** p0 que faz a altitude calculada a partir de [p] ser igual a [altitudeMeters]. */
    fun p0FromAltitude(p: Double, altitudeMeters: Double): Double =
        p / (1.0 - altitudeMeters / 44330.0).pow(5.255)
}

/** Mediana de uma lista não vazia. */
fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
}

/** Filtro exponencial simples. */
class ExponentialFilter(private val alpha: Double) {
    var value: Double? = null
        private set

    fun add(x: Double): Double {
        val v = value?.let { it + alpha * (x - it) } ?: x
        value = v
        return v
    }

    fun reset() {
        value = null
    }
}
