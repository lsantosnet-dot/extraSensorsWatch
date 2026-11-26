package net.extrasensors.phone.stats

import net.extrasensors.phone.data.SkinRow
import net.extrasensors.phone.data.SunBucketRow
import net.extrasensors.phone.data.SunDayRow
import net.extrasensors.phone.data.UvRow
import net.extrasensors.shared.UvMath
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

/**
 * Um ponto do gráfico de sol: uma hora ("Hoje") ou um dia.
 * [uv] é a previsão: o índice da hora ("Hoje") ou o máximo previsto do dia.
 * [exposureUv] é o índice médio enquanto você esteve no sol, calculado pela dose recebida.
 */
data class SunPoint(
    val start: Long,
    val exposedMs: Long,
    val doseSed: Double,
    val uv: Double?,
    val exposureUv: Double? = null,
    val exposedHighUv: Boolean = false,
)

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

    /** Índice UV médio durante a exposição: dose / (0,9 SED/h × horas expostas). */
    private fun exposureUv(exposedMs: Long, doseSed: Double, buckets: List<SunBucketRow>): Double? {
        if (exposedMs <= 0 || buckets.none { it.maxUv != null }) return null
        return doseSed / (UvMath.SED_PER_UVI_HOUR * exposedMs / HOUR.toDouble())
    }

    private fun highUv(buckets: List<SunBucketRow>) = buckets.any { (it.maxUv ?: 0.0) >= UvMath.VERY_HIGH_UVI }

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
                val exposed = inHour.sumOf { it.exposedMs }
                val dose = inHour.sumOf { it.doseSed }
                SunPoint(
                    start = s,
                    exposedMs = exposed,
                    doseSed = dose,
                    uv = uv.firstOrNull { it.hourStart == s }?.uvi ?: inHour.mapNotNull { it.maxUv }.maxOrNull(),
                    exposureUv = exposureUv(inHour.sumOf { it.exposedMs }, dose, inHour),
                    exposedHighUv = highUv(inHour),
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
            val dose = b.sumOf { it.doseSed }
            SunPoint(
                start = d.atStartOfDay(zone).toInstant().toEpochMilli(),
                exposedMs = exposed,
                doseSed = dose,
                uv = uvMax,
                exposureUv = exposureUv(b.sumOf { it.exposedMs }, dose, b),
                exposedHighUv = highUv(b),
            )
        }
    }

    fun sunSummary(points: List<SunPoint>, period: Period, sunscreenCount: Int, today: LocalDate): SunSummary {
        val total = points.sumOf { it.exposedMs }
        // A média considera só os dias do período que já começaram.
        val elapsedDays = (minOf(period.lastDay, today).toEpochDay() - period.firstDay.toEpochDay() + 1)
            .coerceAtLeast(1)
        // Conta exposição real com UV 8+, não só a previsão do dia.
        val highUvDays = if (period.hourly) {
            if (points.any { it.exposedHighUv }) 1 else 0
        } else {
            points.count { it.exposedHighUv }
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

// ---------------- Estresse: HRV e EDA ----------------

/** Medição de HRV com a faixa pessoal (7 dias anteriores) no momento da medição. */
data class HrvPoint(
    val timeMillis: Long,
    val rmssdMs: Double,
    val meanHr: Double,
    val low: Double?,
    val high: Double?,
    val status: net.extrasensors.shared.HrvStatus?,
)

data class HrvSummary(val last: HrvPoint?, val meanRmssd: Double?, val meanHr: Double?, val count: Int)

/** Sessão de EDA resumida: duração, nível médio (µS), picos por minuto e a curva de 10 s. */
data class EdaSessionSummary(
    val startedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val durationMs: Long,
    val meanLevel: Double?,
    val peaksPerMin: Double?,
    val points: List<net.extrasensors.phone.data.EdaPointRow>,
)

object StressStats {
    /**
     * [all] deve incluir os 7 dias antes do período, para a faixa das primeiras medições.
     * Só as medições dentro de [from, to) viram pontos.
     */
    fun hrvSeries(all: List<net.extrasensors.phone.data.HrvRow>, from: Long, to: Long): List<HrvPoint> {
        val m = all.map { net.extrasensors.shared.HrvMeasurement(it.timeMillis, it.rmssdMs, it.meanHr, it.beats, it.durationMs) }
        return all.filter { it.timeMillis in from until to }.map { r ->
            val b = net.extrasensors.shared.HrvStats.baseline(m, r.timeMillis)
            HrvPoint(r.timeMillis, r.rmssdMs, r.meanHr, b?.lowMs, b?.highMs, b?.status(r.rmssdMs))
        }
    }

    fun hrvSummary(points: List<HrvPoint>): HrvSummary = HrvSummary(
        last = points.lastOrNull(),
        meanRmssd = points.takeIf { it.isNotEmpty() }?.map { it.rmssdMs }?.average(),
        meanHr = points.takeIf { it.isNotEmpty() }?.map { it.meanHr }?.average(),
        count = points.size,
    )

    fun edaSessions(
        sessions: List<net.extrasensors.phone.data.EdaSessionRow>,
        points: List<net.extrasensors.phone.data.EdaPointRow>,
        now: Long,
    ): List<EdaSessionSummary> {
        val bySession = points.groupBy { it.sessionStart }
        return sessions.map { s ->
            val pts = bySession[s.startedAt].orEmpty()
            val end = s.endedAt ?: pts.lastOrNull()?.let { it.timeMillis + 10_000 } ?: now
            val minutes = (end - s.startedAt) / 60_000.0
            EdaSessionSummary(
                startedAt = s.startedAt,
                endedAt = s.endedAt,
                source = s.source,
                durationMs = end - s.startedAt,
                meanLevel = pts.takeIf { it.isNotEmpty() }?.map { it.mean }?.average(),
                peaksPerMin = if (pts.isNotEmpty() && minutes > 0) pts.sumOf { it.peaks } / minutes else null,
                points = pts,
            )
        }
    }
}
