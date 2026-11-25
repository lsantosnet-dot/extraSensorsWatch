package net.extrasensors.phone.stats

import net.extrasensors.phone.data.SkinRow
import net.extrasensors.phone.data.SunBucketRow
import net.extrasensors.phone.data.SunDayRow
import net.extrasensors.phone.data.UvRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Preset(val label: String, val days: Int) {
    TODAY("Hoje", 1),
    WEEK("7 dias", 7),
    MONTH("30 dias", 30),
    QUARTER("90 dias", 90),
    YEAR("1 ano", 365),
    CUSTOM("Período", 0),
}

/** Intervalo [firstDay, lastDay] em dias locais. Em períodos de um dia, o gráfico é por hora. */
data class Period(val firstDay: LocalDate, val lastDay: LocalDate) {
    val hourly: Boolean get() = firstDay == lastDay
    val dayCount: Int get() = (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt()
    fun from(zone: ZoneId): Long = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    fun to(zone: ZoneId): Long = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        fun of(preset: Preset, today: LocalDate): Period =
            Period(today.minusDays((preset.days.coerceAtLeast(1) - 1).toLong()), today)
    }
}

/** Um ponto do gráfico de sol: uma hora ("Hoje") ou um dia. */
data class SunPoint(val start: Long, val exposedMs: Long, val doseSed: Double, val uv: Double?)

data class SunSummary(
    val totalMs: Long,
    val averagePerDayMs: Long,
    val doseSed: Double,
    val sunscreenCount: Int,
    val highUvDays: Int,
    val hasData: Boolean,
)

/** Um ponto do gráfico de pele: uma leitura ("Hoje") ou a média do dia com mínima e máxima. */
data class SkinPoint(val start: Long, val mean: Double, val min: Double, val max: Double, val count: Int)

data class SkinSummary(val mean: Double, val min: Double, val max: Double, val count: Int)

object Stats {
    private const val HOUR = 3_600_000L

    private fun dayOf(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    fun sunSeries(
        period: Period,
        buckets: List<SunBucketRow>,
        days: List<SunDayRow>,
        uv: List<UvRow>,
        zone: ZoneId,
    ): List<SunPoint> {
        if (period.hourly) {
            val dayStart = period.firstDay.atStartOfDay(zone)
            return (0 until 24).map { h ->
                val s = dayStart.plusHours(h.toLong()).toInstant().toEpochMilli()
                val e = s + HOUR
                val inHour = buckets.filter { it.bucketStart in s until e }
                SunPoint(
                    start = s,
                    exposedMs = inHour.sumOf { it.exposedMs },
                    doseSed = inHour.sumOf { it.doseSed },
                    uv = uv.firstOrNull { it.hourStart == s }?.uvi ?: inHour.mapNotNull { it.maxUv }.maxOrNull(),
                )
            }
        }
        val bucketsByDay = buckets.groupBy { dayOf(it.bucketStart, zone) }
        val uvByDay = uv.groupBy { dayOf(it.hourStart, zone) }
        val totals = days.associateBy { it.epochDay }
        return (0 until period.dayCount).map { i ->
            val d = period.firstDay.plusDays(i.toLong())
            val b = bucketsByDay[d].orEmpty()
            // O total diário do relógio é a fonte principal; os blocos cobrem dias sem total.
            val exposed = maxOf(totals[d.toEpochDay()]?.totalMs ?: 0L, b.sumOf { it.exposedMs })
            val uvMax = uvByDay[d]?.maxOfOrNull { it.uvi } ?: b.mapNotNull { it.maxUv }.maxOrNull()
            SunPoint(d.atStartOfDay(zone).toInstant().toEpochMilli(), exposed, b.sumOf { it.doseSed }, uvMax)
        }
    }

    fun sunSummary(points: List<SunPoint>, period: Period, sunscreenCount: Int, today: LocalDate): SunSummary {
        val total = points.sumOf { it.exposedMs }
        // A média considera só os dias do período que já começaram.
        val elapsedDays = (minOf(period.lastDay, today).toEpochDay() - period.firstDay.toEpochDay() + 1)
            .coerceAtLeast(1)
        val highUvDays = if (period.hourly) {
            if (points.any { (it.uv ?: 0.0) >= 8.0 }) 1 else 0
        } else {
            points.count { (it.uv ?: 0.0) >= 8.0 }
        }
        return SunSummary(
            totalMs = total,
            averagePerDayMs = total / elapsedDays,
            doseSed = points.sumOf { it.doseSed },
            sunscreenCount = sunscreenCount,
            highUvDays = highUvDays,
            hasData = total > 0 || points.any { it.uv != null },
        )
    }

    fun skinSeries(period: Period, readings: List<SkinRow>, zone: ZoneId): List<SkinPoint> {
        if (period.hourly) return readings.map { SkinPoint(it.timeMillis, it.celsius, it.celsius, it.celsius, 1) }
        return readings.groupBy { dayOf(it.timeMillis, zone) }.toSortedMap().map { (d, rs) ->
            val v = rs.map { it.celsius }
            SkinPoint(d.atStartOfDay(zone).toInstant().toEpochMilli(), v.average(), v.min(), v.max(), v.size)
        }
    }

    fun skinSummary(readings: List<SkinRow>): SkinSummary? {
        if (readings.isEmpty()) return null
        val v = readings.map { it.celsius }
        return SkinSummary(v.average(), v.min(), v.max(), v.size)
    }
}
