package net.extrasensors.phone

import net.extrasensors.phone.data.HrvRow
import net.extrasensors.phone.stats.EdaSessionSummary
import net.extrasensors.phone.stats.Tone
import net.extrasensors.phone.stats.WeeklyStress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyStressTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 26)
    private fun at(d: LocalDate) = d.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

    /** HRV diária: [values] do mais antigo ao mais recente, terminando hoje. */
    private fun hrv(values: List<Double>) = values.mapIndexed { i, v ->
        HrvRow(at(today.minusDays((values.size - 1 - i).toLong())), v, 60.0, 60, 60_000)
    }

    private fun eda(daysAgo: Long, peaks: Double, minutes: Long = 10) = EdaSessionSummary(
        at(today.minusDays(daysAgo)), at(today.minusDays(daysAgo)) + minutes * 60_000, 0, minutes * 60_000, 2.0, peaks, emptyList(),
    )

    @Test
    fun `dois dias seguidos abaixo da faixa viram recuperacao baixa`() {
        val values = List(12) { 50.0 + (it % 3) } + listOf(30.0, 29.0)
        val r = WeeklyStress.compute(hrv(values), emptyList(), today, zone)
        assertEquals(2, r.belowStreak)
        assertTrue(r.insights.first().tone == Tone.ATTENTION)
        assertTrue(r.insights.first().text.contains("Recuperação baixa"))
    }

    @Test
    fun `recuperacao baixa e EDA alta viram estresse acumulado`() {
        val values = List(12) { 50.0 + (it % 3) } + listOf(30.0, 29.0)
        val sessions = listOf(eda(20, 2.0), eda(15, 2.2), eda(10, 1.8), eda(1, 4.0))
        val r = WeeklyStress.compute(hrv(values), sessions, today, zone)
        assertEquals(2.0, r.edaReference!!, 1e-9)
        assertEquals(4.0, r.edaPeaksPerMin!!, 1e-9)
        assertTrue(r.insights.first().text.startsWith("Padrão de estresse acumulado"))
    }

    @Test
    fun `semana normal com HRV subindo`() {
        val values = List(7) { 40.0 + (it % 2) } + List(7) { 44.0 + (it % 2) }
        val r = WeeklyStress.compute(hrv(values), emptyList(), today, zone)
        assertTrue(r.rmssdChange!! > 0.05)
        assertTrue(r.insights.any { it.tone == Tone.GOOD && it.text.contains("acima da semana anterior") })
    }

    @Test
    fun `poucos dados pedem mais medicoes`() {
        val r = WeeklyStress.compute(hrv(listOf(40.0, 42.0)), emptyList(), today, zone)
        assertEquals(Tone.INFO, r.insights.first().tone)
        assertTrue(r.insights.any { it.text.contains("Sem sessões de EDA") })
    }

    @Test
    fun `media de picos ponderada pela duracao`() {
        val sessions = listOf(eda(1, 1.0, minutes = 30), eda(2, 4.0, minutes = 10))
        val r = WeeklyStress.compute(emptyList(), sessions, today, zone)
        assertEquals((1.0 * 30 + 4.0 * 10) / 40, r.edaPeaksPerMin!!, 1e-9)
    }
}
