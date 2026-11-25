package net.extrasensors.phone

import net.extrasensors.phone.data.SkinRow
import net.extrasensors.phone.data.SunBucketRow
import net.extrasensors.phone.data.SunDayRow
import net.extrasensors.phone.data.UvRow
import net.extrasensors.phone.stats.Period
import net.extrasensors.phone.stats.Preset
import net.extrasensors.phone.stats.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 20)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `periodos predefinidos`() {
        assertEquals(Period(today, today), Period.of(Preset.TODAY, today))
        assertEquals(7, Period.of(Preset.WEEK, today).dayCount)
        assertEquals(today.minusDays(29), Period.of(Preset.MONTH, today).firstDay)
        assertTrue(Period.of(Preset.TODAY, today).hourly)
        assertFalse(Period.of(Preset.WEEK, today).hourly)
    }

    @Test
    fun `serie por hora em Hoje`() {
        val buckets = listOf(
            SunBucketRow(at(today, 10, 0), 300_000, 0.5, 7.0),
            SunBucketRow(at(today, 10, 5), 120_000, 0.2, 7.5),
            SunBucketRow(at(today, 14, 30), 60_000, 0.1, null),
        )
        val uv = listOf(UvRow(at(today, 10), 7.2))
        val s = Stats.sunSeries(Period(today, today), buckets, emptyList(), uv, zone)
        assertEquals(24, s.size)
        assertEquals(420_000L, s[10].exposedMs)
        assertEquals(0.7, s[10].doseSed, 1e-9)
        assertEquals(7.2, s[10].uv!!, 1e-9)
        assertEquals(60_000L, s[14].exposedMs)
        assertNull(s[3].uv)
    }

    @Test
    fun `serie diaria usa o total do relogio e o UV maximo do dia`() {
        val d1 = today.minusDays(1)
        val buckets = listOf(SunBucketRow(at(d1, 11), 600_000, 1.2, 9.0))
        val days = listOf(SunDayRow(d1.toEpochDay(), 1_800_000), SunDayRow(today.toEpochDay(), 60_000))
        val uv = listOf(UvRow(at(d1, 9), 4.0), UvRow(at(d1, 12), 10.1), UvRow(at(today, 12), 6.0))
        val p = Period(today.minusDays(6), today)
        val s = Stats.sunSeries(p, buckets, days, uv, zone)
        assertEquals(7, s.size)
        assertEquals(1_800_000L, s[5].exposedMs) // total diário maior que a soma dos blocos
        assertEquals(1.2, s[5].doseSed, 1e-9)
        assertEquals(10.1, s[5].uv!!, 1e-9)
        assertEquals(60_000L, s[6].exposedMs)
        val sum = Stats.sunSummary(s, p, sunscreenCount = 2, today = today)
        assertEquals(1_860_000L, sum.totalMs)
        assertEquals(1_860_000L / 7, sum.averagePerDayMs)
        assertEquals(1, sum.highUvDays)
        assertEquals(2, sum.sunscreenCount)
    }

    @Test
    fun `media diaria conta so dias ja iniciados`() {
        val p = Period(today.minusDays(1), today.plusDays(5)) // personalizado indo além de hoje
        val s = Stats.sunSeries(p, emptyList(), listOf(SunDayRow(today.toEpochDay(), 3_600_000)), emptyList(), zone)
        assertEquals(1_800_000L, Stats.sunSummary(s, p, 0, today).averagePerDayMs)
    }

    @Test
    fun `pele por dia com minimo e maximo`() {
        val r = listOf(
            SkinRow(at(today.minusDays(1), 8), 33.0), SkinRow(at(today.minusDays(1), 20), 34.0),
            SkinRow(at(today, 9), 33.6),
        )
        val s = Stats.skinSeries(Period(today.minusDays(6), today), r, zone)
        assertEquals(2, s.size)
        assertEquals(33.5, s[0].mean, 1e-9)
        assertEquals(33.0, s[0].min, 1e-9)
        assertEquals(34.0, s[0].max, 1e-9)
        val sum = Stats.skinSummary(r)!!
        assertEquals(3, sum.count)
        assertNull(Stats.skinSummary(emptyList()))
    }

    @Test
    fun `UV no sol vem da dose e nao do maximo previsto do dia`() {
        // Caso real: 4 min de sol às 7h30 com UV ~1, num dia de máximo previsto 9,6.
        val d = today
        val buckets = listOf(SunBucketRow(at(d, 7, 30), 240_000, 0.06, 1.1))
        val uv = listOf(UvRow(at(d, 7), 0.8), UvRow(at(d, 12), 9.6))
        val p = Period(today.minusDays(6), today)
        val s = Stats.sunSeries(p, buckets, emptyList(), uv, zone)
        val pt = s.last()
        assertEquals(9.6, pt.uv!!, 1e-9) // previsão máxima do dia
        assertEquals(1.0, pt.exposureUv!!, 1e-9) // 0,06 SED / (0,9 × 4/60 h)
        assertFalse(pt.exposedHighUv)
        assertEquals(0, Stats.sunSummary(s, p, 0, today).highUvDays)
    }
}
