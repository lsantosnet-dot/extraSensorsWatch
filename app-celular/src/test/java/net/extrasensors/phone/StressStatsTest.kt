package net.extrasensors.phone

import net.extrasensors.phone.data.EdaPointRow
import net.extrasensors.phone.data.EdaSessionRow
import net.extrasensors.phone.data.HrvRow
import net.extrasensors.phone.stats.StressStats
import net.extrasensors.shared.HrvStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StressStatsTest {
    private val day = 86_400_000L

    @Test
    fun `faixa usa os 7 dias anteriores mesmo fora do periodo`() {
        val base = 100 * day
        val rows = (1..5).map { HrvRow(base - it * day, 45.0 + it, 60.0, 60, 60_000) } + HrvRow(base + 1000, 25.0, 70.0, 60, 60_000)
        val s = StressStats.hrvSeries(rows, from = base, to = base + day)
        assertEquals(1, s.size)
        assertNotNull(s[0].low)
        assertEquals(HrvStatus.BELOW, s[0].status)
        val sum = StressStats.hrvSummary(s)
        assertEquals(25.0, sum.meanRmssd!!, 1e-9)
        assertEquals(1, sum.count)
    }

    @Test
    fun `primeiras medicoes ficam sem faixa`() {
        val s = StressStats.hrvSeries(listOf(HrvRow(10 * day, 40.0, 60.0, 60, 60_000)), 0, 20 * day)
        assertNull(s[0].status)
    }

    @Test
    fun `sessao de EDA com picos por minuto`() {
        val start = 1_000_000L
        val pts = (0 until 30).map { EdaPointRow(start + it * 10_000L, start, 2.0 + it * 0.01, 1.9, 2.5, if (it % 10 == 0) 1 else 0) }
        val s = StressStats.edaSessions(listOf(EdaSessionRow(start, start + 300_000, 0)), pts, now = start + 400_000).single()
        assertEquals(300_000L, s.durationMs)
        assertEquals(3 / 5.0, s.peaksPerMin!!, 1e-9) // 3 picos em 5 min
        assertEquals(pts.map { it.mean }.average(), s.meanLevel!!, 1e-9)
    }
}
