package net.extrasensors.watch.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.log10

object Bands {
    fun lightDescription(lux: Double): String = when {
        lux < 10 -> "Escuro"
        lux < 50 -> "Penumbra"
        lux < 200 -> "Interior pouco iluminado"
        lux < 1_000 -> "Interior bem iluminado"
        lux < 10_000 -> "Externo, sombra ou nublado"
        lux < 50_000 -> "Luz forte"
        else -> "Sol direto"
    }

    /** Posição na barra logarítmica de 1 a 100.000 lx (0..1). */
    fun lightFraction(lux: Double): Float =
        (log10(lux.coerceAtLeast(1.0)) / 5.0).toFloat().coerceIn(0f, 1f)

    fun soundLabel(db: Double): String = when {
        db < 40 -> "Silencioso"
        db < 55 -> "Moderado"
        db < 70 -> "Ruidoso"
        db < 85 -> "Muito ruidoso"
        else -> "Perigoso em exposição longa"
    }

    /** Posição na barra linear de 30 a 120 dB (0..1). */
    fun soundFraction(db: Double): Float = ((db - 30.0) / 90.0).toFloat().coerceIn(0f, 1f)
}

data class TempReading(val timeMillis: Long, val celsius: Double)

sealed interface SkinBaseline {
    data class Available(val mean: Double) : SkinBaseline
    data class Insufficient(val daysWithData: Int, val required: Int) : SkinBaseline
}

object SkinTemperature {
    const val DAYS = 7
    const val MIN_DAYS = 3

    /** Média das médias diárias dos 7 dias anteriores (sem hoje). Exige 3 dias com leitura. */
    fun baseline(readings: List<TempReading>, now: Long, zone: ZoneId): SkinBaseline {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(DAYS.toLong())
        val byDay: Map<LocalDate, List<Double>> = readings
            .groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(first) && it.isBefore(today) }
            .mapValues { e -> e.value.map { it.celsius } }
        if (byDay.size < MIN_DAYS) return SkinBaseline.Insufficient(byDay.size, MIN_DAYS)
        return SkinBaseline.Available(byDay.values.map { it.average() }.average())
    }
}
