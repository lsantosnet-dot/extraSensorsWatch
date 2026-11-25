package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SunExposureTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val cfg = SunConfig(luxThreshold = 10_000f, goalMs = 2 * 3600_000L)
    private val start = LocalDateTime.of(2026, 1, 10, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val tick = 30_000L

    private fun ticks(
        from: SunState, t0: Long, count: Int, lux: Float?, onWrist: Boolean = true, step: Long = tick,
    ): Pair<SunState, List<SunTickResult>> {
        var s = from
        val results = mutableListOf<SunTickResult>()
        for (i in 1..count) {
            val r = SunExposure.onTick(s, t0 + i * step, lux, onWrist, cfg, zone)
            results += r
            s = r.state
        }
        return s to results
    }

    private fun begin(): SunState =
        SunExposure.onTick(SunExposure.initial(start, zone), start, 50_000f, true, cfg, zone).state

    @Test
    fun `sol continuo sem eventos novos do sensor conta pelo ultimo lux`() {
        // O mesmo lux repetido em cada tick = sensor "on-change" em silêncio.
        val (s, _) = ticks(begin(), start, 120, 50_000f)
        assertEquals(60 * 60_000L, s.sinceApplicationMs)
        assertEquals(60 * 60_000L, s.todayMs)
    }

    @Test
    fun `luz abaixo do limite nao conta`() {
        val (s, _) = ticks(begin(), start, 60, 8_000f)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `lacuna de 10 min e descartada`() {
        val s1 = ticks(begin(), start, 10, 50_000f).first // 5 min
        val afterGap = SunExposure.onTick(s1, start + 10 * tick + 10 * 60_000, 50_000f, true, cfg, zone)
        assertFalse(afterGap.counted)
        assertEquals(5 * 60_000L, afterGap.state.todayMs)
        // O próximo tick normal volta a contar.
        val next = SunExposure.onTick(afterGap.state, start + 10 * tick + 10 * 60_000 + tick, 50_000f, true, cfg, zone)
        assertEquals(5 * 60_000L + tick, next.state.todayMs)
    }

    @Test
    fun `fora do pulso nao conta`() {
        val (s, _) = ticks(begin(), start, 20, 80_000f, onWrist = false)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `sem leitura de lux nao conta`() {
        val (s, _) = ticks(begin(), start, 20, null)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `meta atingida dispara uma unica vez`() {
        val (_, results) = ticks(begin(), start, 300, 50_000f) // 2,5 h
        assertEquals(1, results.count { it.goalReached })
        assertEquals(240, results.indexOfFirst { it.goalReached } + 1) // exatamente em 2 h
    }

    @Test
    fun `apliquei protetor zera e permite novo alerta`() {
        val (s, _) = ticks(begin(), start, 250, 50_000f)
        assertTrue(s.goalNotified)
        val now = start + 250 * tick
        val applied = SunExposure.applySunscreen(s, now, zone)
        assertEquals(0L, applied.sinceApplicationMs)
        assertFalse(applied.goalNotified)
        assertEquals(now, applied.lastApplicationAt)
        assertEquals(s.todayMs, applied.todayMs)
        val (_, results) = ticks(applied, now, 240, 50_000f)
        assertEquals(1, results.count { it.goalReached })
    }

    @Test
    fun `virada da meia noite divide o intervalo e fecha o dia`() {
        val beforeMidnight = LocalDateTime.of(2026, 1, 10, 23, 59, 50).atZone(zone).toInstant().toEpochMilli()
        val s0 = SunState(
            epochDay = SunExposure.dayOf(beforeMidnight, zone),
            todayMs = 1000, sinceApplicationMs = 1000,
            lastTickAt = beforeMidnight, goalNotified = true,
        )
        val r = SunExposure.onTick(s0, beforeMidnight + tick, 50_000f, true, cfg, zone)
        val closed = r.closedDay
        assertNotNull(closed)
        assertEquals(1000 + 10_000L, closed!!.totalMs)
        assertEquals(20_000L, r.state.todayMs)
        assertEquals(20_000L, r.state.sinceApplicationMs)
        assertFalse(r.state.goalNotified)
        assertEquals(s0.epochDay + 1, r.state.epochDay)
    }

    @Test
    fun `normalized troca de dia sem contar tempo`() {
        val s0 = SunState(epochDay = SunExposure.dayOf(start, zone), todayMs = 5000, sinceApplicationMs = 5000)
        val n = SunExposure.normalized(s0, start + 24 * 3600_000L, zone)
        assertEquals(0L, n.todayMs)
        assertNull(SunExposure.onTick(n, start + 24 * 3600_000L, 50_000f, true, cfg, zone).closedDay)
    }
}
